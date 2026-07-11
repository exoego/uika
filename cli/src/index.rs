use crate::extract::extract_api;
use crate::input::LoadedClass;
use crate::intern::{Sym, intern};
use crate::model::{ClassApi, ClassName, MemberKey};
use rayon::prelude::*;
use rustc_hash::FxHashMap;
use std::collections::{HashSet, VecDeque};
use std::sync::OnceLock;

pub const JAVA_LANG_OBJECT: &str = "java/lang/Object";

pub fn object_sym() -> Sym {
    static SYM: OnceLock<Sym> = OnceLock::new();
    *SYM.get_or_init(|| intern(JAVA_LANG_OBJECT))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemberKind {
    Method,
    Field,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Resolution {
    Found,
    NotFound,
    /// Resolution reached a type outside the index (for example, a class from another library), so existence cannot be proven.
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ResolvedMember {
    pub owner: ClassName,
    pub access: u16,
}

/// One class in the index. Members and interfaces are stored as ranges into shared index arenas
/// (allocating a Box per class makes allocation overhead dominant at hundreds of thousands of classes).
#[derive(Debug, Clone, Copy)]
pub struct ClassEntry {
    pub access: u16,
    pub super_name: Option<ClassName>,
    interfaces: (u32, u16),
    methods: (u32, u16),
    fields: (u32, u16),
}

/// API index with class hierarchy. Member tables for all classes are stored in one concatenated arena.
pub struct ApiIndex {
    pub classes: FxHashMap<ClassName, ClassEntry>,
    members: Vec<(MemberKey, u16)>,
    interfaces: Vec<Sym>,
}

impl ApiIndex {
    pub fn new() -> Self {
        Self {
            classes: FxHashMap::default(),
            members: Vec::new(),
            interfaces: Vec::new(),
        }
    }

    pub fn build(apis: impl IntoIterator<Item = ClassApi>) -> Self {
        let mut index = Self::new();
        for api in apis {
            index.insert_if_absent(api);
        }
        index
    }

    /// Duplicate class names are first-wins (JVM classpath resolution order). Duplicates are not appended to arenas.
    pub fn insert_if_absent(&mut self, api: ClassApi) {
        if self.classes.contains_key(&api.name) {
            return;
        }
        let entry = ClassEntry {
            access: api.access,
            super_name: api.super_name,
            interfaces: append_range_sym(&mut self.interfaces, &api.interfaces),
            methods: append_range(&mut self.members, &api.methods),
            fields: append_range(&mut self.members, &api.fields),
        };
        self.classes.insert(api.name, entry);
    }

    pub fn methods_of(&self, entry: &ClassEntry) -> &[(MemberKey, u16)] {
        range(&self.members, entry.methods)
    }

    pub fn fields_of(&self, entry: &ClassEntry) -> &[(MemberKey, u16)] {
        range(&self.members, entry.fields)
    }

    pub fn interfaces_of(&self, entry: &ClassEntry) -> &[Sym] {
        range(&self.interfaces, entry.interfaces)
    }

    /// Total member-table entries (methods + fields).
    pub fn member_count(&self) -> usize {
        self.members.len()
    }

    /// Release arena growth slack after construction (Vec doubling slack).
    pub fn shrink_to_fit(&mut self) {
        self.members.shrink_to_fit();
        self.interfaces.shrink_to_fit();
        self.classes.shrink_to_fit();
    }

    /// Build an index from loaded JAR/directory results.
    /// Per-class parse failures are returned as warnings instead of failing the whole build.
    pub fn from_classes(classes: &[LoadedClass]) -> (Self, Vec<String>) {
        let results: Vec<Result<ClassApi, String>> = classes
            .par_iter()
            .map(|lc| {
                crate::classfile::RawClass::parse(&lc.bytes)
                    .and_then(|rc| extract_api(&rc))
                    .map_err(|e| format!("{}!{}: {e}", lc.source, lc.entry_name))
            })
            .collect();
        let mut index = Self::new();
        let mut warnings = Vec::new();
        for r in results {
            match r {
                Ok(api) => index.insert_if_absent(api),
                Err(w) => warnings.push(w),
            }
        }
        (index, warnings)
    }

    pub fn contains_class(&self, name: ClassName) -> bool {
        self.classes.contains_key(&name)
    }

    pub fn class_access(&self, name: ClassName) -> Option<u16> {
        self.classes.get(&name).map(|entry| entry.access)
    }

    pub fn direct_method_access(&self, class: ClassName, key: MemberKey) -> Option<u16> {
        self.classes
            .get(&class)
            .and_then(|entry| find_member(self.methods_of(entry), key))
    }

    pub fn direct_field_access(&self, class: ClassName, key: MemberKey) -> Option<u16> {
        self.classes
            .get(&class)
            .and_then(|entry| find_member(self.fields_of(entry), key))
    }

    /// Resolve against a single index. Use Scope for resolution across multiple indexes.
    pub fn resolve(&self, owner: ClassName, key: MemberKey, kind: MemberKind) -> Resolution {
        Scope::new(vec![self]).resolve(owner, key, kind)
    }
}

impl Default for ApiIndex {
    fn default() -> Self {
        Self::new()
    }
}

fn append_range(arena: &mut Vec<(MemberKey, u16)>, items: &[(MemberKey, u16)]) -> (u32, u16) {
    let start = u32::try_from(arena.len()).expect("member arena overflow");
    let len = u16::try_from(items.len()).expect("member count overflow"); // JVMS caps this at u16.
    arena.extend_from_slice(items);
    (start, len)
}

fn append_range_sym(arena: &mut Vec<Sym>, items: &[Sym]) -> (u32, u16) {
    let start = u32::try_from(arena.len()).expect("interface arena overflow");
    let len = u16::try_from(items.len()).expect("interface count overflow");
    arena.extend_from_slice(items);
    (start, len)
}

fn range<T>(arena: &[T], (start, len): (u32, u16)) -> &[T] {
    &arena[start as usize..start as usize + len as usize]
}

/// Existence check against a sorted member range (sorted by build_members).
fn find_member(members: &[(MemberKey, u16)], key: MemberKey) -> Option<u16> {
    members
        .binary_search_by_key(&key, |&(k, _)| k)
        .ok()
        .map(|idx| members[idx].1)
}

/// Lightweight hierarchy graph for the scanned classpath. It has no member tables and records
/// only class name -> (parent, interfaces, origin).
/// Classes whose members are actually needed for reference resolution (typically thousands)
/// are identified by walking this graph and then reread precisely in pass 2.
pub struct ClassGraph {
    nodes: FxHashMap<ClassName, GraphNode>,
    interfaces: Vec<Sym>,
}

#[derive(Debug, Clone, Copy)]
pub struct GraphNode {
    pub super_name: Option<ClassName>,
    interfaces: (u32, u16),
    /// Origin selected by first-wins (JAR/directory path). Reread location for pass 2.
    pub source: Sym,
}

impl ClassGraph {
    pub fn new() -> Self {
        Self {
            nodes: FxHashMap::default(),
            interfaces: Vec::new(),
        }
    }

    /// Duplicate class names are first-wins. Returns true if inserted.
    pub fn insert_if_absent(
        &mut self,
        name: ClassName,
        super_name: Option<ClassName>,
        interfaces: &[Sym],
        source: Sym,
    ) -> bool {
        if self.nodes.contains_key(&name) {
            return false;
        }
        let range = append_range_sym(&mut self.interfaces, interfaces);
        self.nodes.insert(
            name,
            GraphNode {
                super_name,
                interfaces: range,
                source,
            },
        );
        true
    }

    pub fn get(&self, name: ClassName) -> Option<&GraphNode> {
        self.nodes.get(&name)
    }

    pub fn iter(&self) -> impl Iterator<Item = (ClassName, &GraphNode)> {
        self.nodes.iter().map(|(&name, node)| (name, node))
    }

    pub fn contains(&self, name: ClassName) -> bool {
        self.nodes.contains_key(&name)
    }

    pub fn len(&self) -> usize {
        self.nodes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.nodes.is_empty()
    }

    pub fn interfaces_of(&self, node: &GraphNode) -> &[Sym] {
        range(&self.interfaces, node.interfaces)
    }

    pub fn shrink_to_fit(&mut self) {
        self.nodes.shrink_to_fit();
        self.interfaces.shrink_to_fit();
    }
}

impl Default for ClassGraph {
    fn default() -> Self {
        Self::new()
    }
}

/// Resolution scope layered over multiple ApiIndex values.
/// Actual JVM linking runs against the whole runtime classpath, so check resolves against
/// "library + scanned classpath" instead of the library alone. This avoids false positives
/// for moves to another artifact or copies bundled into fat JARs.
pub struct Scope<'a> {
    layers: Vec<&'a ApiIndex>,
}

impl<'a> Scope<'a> {
    pub fn new(layers: Vec<&'a ApiIndex>) -> Self {
        Self { layers }
    }

    fn class(&self, name: ClassName) -> Option<(&'a ApiIndex, &'a ClassEntry)> {
        self.layers
            .iter()
            .find_map(|idx| idx.classes.get(&name).map(|e| (*idx, e)))
    }

    pub fn contains_class(&self, name: ClassName) -> bool {
        self.class(name).is_some()
    }

    pub fn class_access(&self, name: ClassName) -> Option<u16> {
        self.class(name).map(|(_, entry)| entry.access)
    }

    /// Simplified JVMS 5.4.3.2 / 5.4.3.3. Check member existence by walking the owner,
    /// then the superclass chain, then superinterfaces by BFS.
    /// java/lang/Object members are resolved from built-in knowledge because Kt facade
    /// classes only extend Object; without this, real removals would be missed as Unknown
    /// after escaping the indexed scope.
    pub fn resolve(&self, owner: ClassName, key: MemberKey, kind: MemberKind) -> Resolution {
        match self.resolve_member(owner, key, kind) {
            MemberResolution::Found(_) => Resolution::Found,
            MemberResolution::NotFound => Resolution::NotFound,
            MemberResolution::Unknown => Resolution::Unknown,
        }
    }

    pub fn resolve_member(
        &self,
        owner: ClassName,
        key: MemberKey,
        kind: MemberKind,
    ) -> MemberResolution {
        let mut queue = VecDeque::from([owner]);
        let mut seen = HashSet::new();
        let mut reached_unknown = false;
        while let Some(class) = queue.pop_front() {
            if !seen.insert(class) {
                continue;
            }
            if class == object_sym() {
                if kind == MemberKind::Method && is_object_method(key) {
                    return MemberResolution::Found(ResolvedMember {
                        owner: class,
                        access: crate::model::ACC_PUBLIC,
                    });
                }
                continue;
            }
            let Some((idx, entry)) = self.class(class) else {
                reached_unknown = true;
                continue;
            };
            let members = match kind {
                MemberKind::Method => idx.methods_of(entry),
                MemberKind::Field => idx.fields_of(entry),
            };
            if let Some(access) = find_member(members, key) {
                return MemberResolution::Found(ResolvedMember {
                    owner: class,
                    access,
                });
            }
            if let Some(s) = entry.super_name {
                queue.push_back(s);
            }
            queue.extend(idx.interfaces_of(entry).iter().copied());
        }
        if reached_unknown {
            MemberResolution::Unknown
        } else {
            MemberResolution::NotFound
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemberResolution {
    Found(ResolvedMember),
    NotFound,
    Unknown,
}

fn is_object_method(key: MemberKey) -> bool {
    static METHODS: OnceLock<[MemberKey; 11]> = OnceLock::new();
    METHODS
        .get_or_init(|| {
            [
                MemberKey::new("getClass", "()Ljava/lang/Class;"),
                MemberKey::new("hashCode", "()I"),
                MemberKey::new("equals", "(Ljava/lang/Object;)Z"),
                MemberKey::new("clone", "()Ljava/lang/Object;"),
                MemberKey::new("toString", "()Ljava/lang/String;"),
                MemberKey::new("notify", "()V"),
                MemberKey::new("notifyAll", "()V"),
                MemberKey::new("wait", "()V"),
                MemberKey::new("wait", "(J)V"),
                MemberKey::new("wait", "(JI)V"),
                MemberKey::new("finalize", "()V"),
            ]
        })
        .contains(&key)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{ACC_PUBLIC, build_members};

    fn class(name: &str, super_name: Option<&str>, methods: &[(&str, &str)]) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: super_name.map(intern),
            interfaces: vec![],
            methods: build_members(
                methods
                    .iter()
                    .map(|(n, d)| (MemberKey::new(n, d), ACC_PUBLIC)),
            ),
            fields: build_members([]),
        }
    }

    #[test]
    fn resolves_method_on_class_itself() {
        let idx = ApiIndex::build([class("a/C", Some(JAVA_LANG_OBJECT), &[("m", "()V")])]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Found
        );
    }

    #[test]
    fn resolves_method_moved_to_superclass() {
        // Old: C.m / New: moved to parent D -> runtime resolution succeeds, so Found.
        let idx = ApiIndex::build([
            class("a/C", Some("a/D"), &[]),
            class("a/D", Some(JAVA_LANG_OBJECT), &[("m", "()V")]),
        ]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Found
        );
    }

    #[test]
    fn resolves_method_through_interface() {
        let mut c = class("a/C", Some(JAVA_LANG_OBJECT), &[]);
        c.interfaces = vec![intern("a/I")];
        let i = class("a/I", None, &[("m", "()V")]);
        let idx = ApiIndex::build([c, i]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Found
        );
    }

    #[test]
    fn missing_method_with_object_super_is_not_found() {
        // Kt-facade-like case: if the only parent is Object, this is conclusively NotFound, not Unknown.
        let idx = ApiIndex::build([class("a/C", Some(JAVA_LANG_OBJECT), &[])]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("gone", "()J"),
                MemberKind::Method
            ),
            Resolution::NotFound
        );
    }

    #[test]
    fn object_builtin_methods_resolve() {
        let idx = ApiIndex::build([class("a/C", Some(JAVA_LANG_OBJECT), &[])]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("toString", "()Ljava/lang/String;"),
                MemberKind::Method
            ),
            Resolution::Found
        );
    }

    #[test]
    fn external_supertype_yields_unknown() {
        // Escaping to a parent outside the index (another library) cannot be proven.
        let idx = ApiIndex::build([class("a/C", Some("ext/Base"), &[])]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Unknown
        );
    }

    #[test]
    fn unknown_owner_yields_unknown() {
        let idx = ApiIndex::build([]);
        assert_eq!(
            idx.resolve(
                intern("ext/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Unknown
        );
    }

    #[test]
    fn scope_resolves_across_layered_indexes() {
        // Resolution can still succeed if a library class hierarchy escapes to a parent from the scanned classpath.
        let lib = ApiIndex::build([class("lib/C", Some("cp/Base"), &[])]);
        let cp = ApiIndex::build([class("cp/Base", Some(JAVA_LANG_OBJECT), &[("m", "()V")])]);
        let scope = Scope::new(vec![&lib, &cp]);
        assert_eq!(
            scope.resolve(
                intern("lib/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Found
        );
        assert!(scope.contains_class(intern("cp/Base")));
    }

    #[test]
    fn duplicate_class_is_first_wins() {
        let mut first = class("a/C", Some(JAVA_LANG_OBJECT), &[("m", "()V")]);
        first.access = ACC_PUBLIC;
        let second = class("a/C", Some(JAVA_LANG_OBJECT), &[("other", "()V")]);
        let idx = ApiIndex::build([first, second]);
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("m", "()V"),
                MemberKind::Method
            ),
            Resolution::Found
        );
        assert_eq!(
            idx.resolve(
                intern("a/C"),
                MemberKey::new("other", "()V"),
                MemberKind::Method
            ),
            Resolution::NotFound
        );
    }
}
