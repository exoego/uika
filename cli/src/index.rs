use crate::extract::extract_api;
use crate::input::LoadedClass;
use crate::intern::{Sym, intern};
use crate::model::{ClassApi, ClassName, MemberKey};
use rayon::prelude::*;
use rustc_hash::{FxHashMap, FxHashSet};
use std::collections::HashSet;
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
    pub nest_host: Option<ClassName>,
    interfaces: (u32, u16),
    /// Shares the interfaces arena. `None` = unsealed; a zero-length range is a sealed
    /// class permitting nothing.
    permitted: Option<(u32, u16)>,
    /// Sealing was unreadable, so this class is neither provably sealed nor provably not.
    pub sealing_unknown: bool,
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
            nest_host: api.nest_host,
            interfaces: append_range_sym(&mut self.interfaces, &api.interfaces),
            permitted: api
                .permitted
                .as_deref()
                .map(|p| append_range_sym(&mut self.interfaces, p)),
            sealing_unknown: api.sealing_unknown,
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

    /// The class's permitted subclasses, or None when it is not sealed.
    pub fn permitted_of(&self, entry: &ClassEntry) -> Option<&[Sym]> {
        entry.permitted.map(|r| range(&self.interfaces, r))
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

    /// Class names as raw process-lifetime strings, for pass-1 reference filtering.
    /// Letting `extract_refs` test candidate owners by string against this set avoids
    /// interning the tens of millions of constant-pool owners that are not in the
    /// checked library, which is where pass 1 spent most of its intern-lock time.
    pub fn class_name_set(&self) -> FxHashSet<&'static str> {
        self.classes.keys().map(|k| k.as_str()).collect()
    }

    /// Whether this entry and one from `other` expose the same API. Member slices are sorted
    /// (`find_member` binary-searches them), so equal sets compare equal within a process,
    /// which is the only place this is ever asked.
    fn same_api(&self, entry: &ClassEntry, other_index: &ApiIndex, other: &ClassEntry) -> bool {
        entry.access == other.access
            && entry.super_name == other.super_name
            && entry.nest_host == other.nest_host
            && entry.sealing_unknown == other.sealing_unknown
            && self.interfaces_of(entry) == other_index.interfaces_of(other)
            && self.permitted_of(entry) == other_index.permitted_of(other)
            && self.methods_of(entry) == other_index.methods_of(other)
            && self.fields_of(entry) == other_index.fields_of(other)
    }
}

/// Raw names of the classes in `old` a recorded reference could ever break on: those whose
/// entry differs from `new`'s, plus every subtype of one of them.
///
/// Pass 1's owner filter used to be every class in `old`. For a JDK-pair check that is the
/// whole JDK surface, so a reference to `java/lang/String` became a reference record on
/// every scanned class, and the records are what pass 1 spends its time producing. A class
/// whose entry is identical on both sides resolves identically on both sides, so recording
/// references to it can only cost.
///
/// The subtype closure is what makes the narrowing safe. `java/util/ArrayList` can be
/// untouched while the member a reference names is inherited from an `AbstractList` that
/// lost it, so a class inherits its ancestors' differences as well as carrying its own. The
/// closure runs DOWNWARD from the differing classes over a parent -> children map built from
/// both indexes, never as a memoized walk upward. A memo filled in hash-map iteration order
/// would make the accepted set depend on that order, and the output with it.
pub fn breakable_class_names(old: &ApiIndex, new: &ApiIndex) -> FxHashSet<&'static str> {
    let mut differs: FxHashSet<ClassName> = FxHashSet::default();
    for (name, entry) in &old.classes {
        let unchanged = new
            .classes
            .get(name)
            .is_some_and(|other| old.same_api(entry, new, other));
        if !unchanged {
            differs.insert(*name);
        }
    }

    let mut children: FxHashMap<ClassName, Vec<ClassName>> = FxHashMap::default();
    for index in [old, new] {
        for (name, entry) in &index.classes {
            if let Some(super_name) = entry.super_name {
                children.entry(super_name).or_default().push(*name);
            }
            for &interface in index.interfaces_of(entry) {
                children.entry(interface).or_default().push(*name);
            }
        }
    }

    let mut tainted = differs.clone();
    let mut queue: Vec<ClassName> = differs.into_iter().collect();
    while let Some(name) = queue.pop() {
        let Some(subtypes) = children.get(&name) else {
            continue;
        };
        for &subtype in subtypes {
            if tainted.insert(subtype) {
                queue.push(subtype);
            }
        }
    }

    old.classes
        .keys()
        .filter(|name| tainted.contains(name))
        .map(|name| name.as_str())
        .collect()
}

impl ApiIndex {
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
    /// Class-load edge arena for reachability. Empty unless edge collection is enabled.
    refs: Vec<Sym>,
}

#[derive(Debug, Clone, Copy)]
pub struct GraphNode {
    pub super_name: Option<ClassName>,
    /// NestHost attribute target (nestmate private access); None when absent.
    pub nest_host: Option<ClassName>,
    interfaces: (u32, u16),
    refs: (u32, u16),
    /// Origin selected by first-wins (JAR/directory path). Reread location for pass 2.
    pub source: Sym,
}

impl ClassGraph {
    pub fn new() -> Self {
        Self {
            nodes: FxHashMap::default(),
            interfaces: Vec::new(),
            refs: Vec::new(),
        }
    }

    /// Duplicate class names are first-wins. Returns true if inserted.
    pub fn insert_if_absent(
        &mut self,
        name: ClassName,
        super_name: Option<ClassName>,
        interfaces: &[Sym],
        refs: &[Sym],
        nest_host: Option<ClassName>,
        source: Sym,
    ) -> bool {
        if self.nodes.contains_key(&name) {
            return false;
        }
        let range = append_range_sym(&mut self.interfaces, interfaces);
        let refs = append_range_sym(&mut self.refs, refs);
        self.nodes.insert(
            name,
            GraphNode {
                super_name,
                nest_host,
                interfaces: range,
                refs,
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

    pub fn refs_of(&self, node: &GraphNode) -> &[Sym] {
        range(&self.refs, node.refs)
    }

    pub fn shrink_to_fit(&mut self) {
        self.nodes.shrink_to_fit();
        self.interfaces.shrink_to_fit();
        self.refs.shrink_to_fit();
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

    /// Outer None = class not in scope; inner None = class has no NestHost attribute.
    pub fn class_nest_host(&self, name: ClassName) -> Option<Option<ClassName>> {
        self.class(name).map(|(_, entry)| entry.nest_host)
    }

    /// Outer None = class not in scope; inner None = the class has no superclass.
    /// Lets hierarchy walks (e.g. the protected-access subclass check) cross edges
    /// that exist only in a resolution layer, matching what resolve_member walks.
    pub fn class_super(&self, name: ClassName) -> Option<Option<ClassName>> {
        self.class(name).map(|(_, entry)| entry.super_name)
    }

    /// Direct superclass plus interfaces of `name`, from whichever layer defines it.
    /// None when the class is in no scope layer (a hierarchy escape). The interface slice
    /// borrows the index arena (like `interfaces_of`), so walking the supertype closure
    /// through the scope allocates nothing per class.
    pub fn super_and_interfaces(
        &self,
        name: ClassName,
    ) -> Option<(Option<ClassName>, &'a [ClassName])> {
        self.class(name)
            .map(|(idx, entry)| (entry.super_name, idx.interfaces_of(entry)))
    }

    /// Access flags of a method DECLARED directly on `name` (not inherited). None when the
    /// class is absent from the scope or does not declare the method itself.
    pub fn direct_method_access(&self, name: ClassName, key: MemberKey) -> Option<u16> {
        let (idx, entry) = self.class(name)?;
        find_member(idx.methods_of(entry), key)
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
        // Constructors and the class initializer are not inherited: an
        // `invokespecial Owner.<init>` (or a `<clinit>`) binds to the exact named
        // class, never a superclass. Walking the chain for them would resolve a
        // removed constructor to a superclass copy and misreport a NoSuchMethodError
        // as access-narrowed. Resolve owner-only.
        if kind == MemberKind::Method && is_constructor(key) {
            return match self.class(owner) {
                Some((idx, entry)) => match find_member(idx.methods_of(entry), key) {
                    Some(access) => MemberResolution::Found(ResolvedMember { owner, access }),
                    None => MemberResolution::NotFound,
                },
                None => MemberResolution::Unknown,
            };
        }
        match kind {
            MemberKind::Field => self.resolve_field(owner, key, &mut HashSet::new()),
            MemberKind::Method => self.resolve_method(owner, key),
        }
    }

    /// JVMS 5.4.3.2 field resolution: the class itself, then its direct
    /// superinterfaces (recursively, before the superclass), then its superclass.
    /// The interface-first order matters when the same name+descriptor exists on
    /// both a superinterface (implicitly static final) and a superclass, so the
    /// static/access verdict attributes to the right owner. Unknown propagates: if
    /// any branch escapes analyzed scope the field could be there, so the result is
    /// Unknown unless another branch resolves it.
    fn resolve_field(
        &self,
        class: ClassName,
        key: MemberKey,
        seen: &mut HashSet<ClassName>,
    ) -> MemberResolution {
        // java/lang/Object declares no fields.
        if class == object_sym() {
            return MemberResolution::NotFound;
        }
        if !seen.insert(class) {
            return MemberResolution::NotFound;
        }
        let Some((idx, entry)) = self.class(class) else {
            return MemberResolution::Unknown;
        };
        if let Some(access) = find_member(idx.fields_of(entry), key) {
            return MemberResolution::Found(ResolvedMember {
                owner: class,
                access,
            });
        }
        // Superinterfaces have priority over the superclass, so an Unknown branch
        // here could shadow a superclass field: bail before falling to the superclass.
        for &iface in idx.interfaces_of(entry) {
            match self.resolve_field(iface, key, seen) {
                found @ MemberResolution::Found(_) => return found,
                unknown @ MemberResolution::Unknown => return unknown,
                MemberResolution::NotFound => {}
            }
        }
        match entry.super_name {
            Some(s) => self.resolve_field(s, key, seen),
            None => MemberResolution::NotFound,
        }
    }

    /// JVMS 5.4.3.3 method resolution: the full superclass chain first, then the
    /// maximally-specific superinterface methods. Walking the whole class chain
    /// before any interface matters when the same method exists on a grandparent
    /// class and on a directly-implemented interface. The interface phase is
    /// first-match rather than strict maximally-specific selection, which is enough
    /// for existence and access without the full most-specific tie-break.
    fn resolve_method(&self, owner: ClassName, key: MemberKey) -> MemberResolution {
        let mut ifaces = Vec::new();
        let mut class = Some(owner);
        let mut seen = HashSet::new();
        while let Some(c) = class {
            if !seen.insert(c) {
                break;
            }
            if c == object_sym() {
                if is_object_method(key) {
                    return MemberResolution::Found(ResolvedMember {
                        owner: c,
                        access: crate::model::ACC_PUBLIC,
                    });
                }
                break;
            }
            let Some((idx, entry)) = self.class(c) else {
                // The superclass chain has priority over interfaces, so a break in it
                // could hide a superclass method: cannot conclude, so Unknown.
                return MemberResolution::Unknown;
            };
            if let Some(access) = find_member(idx.methods_of(entry), key) {
                return MemberResolution::Found(ResolvedMember { owner: c, access });
            }
            ifaces.extend(idx.interfaces_of(entry).iter().copied());
            class = entry.super_name;
        }
        // Class chain fully walked without a match: search maximally-specific
        // superinterface methods.
        let mut iface_seen = HashSet::new();
        let mut unknown = false;
        for iface in ifaces {
            match self.resolve_iface_method(iface, key, &mut iface_seen) {
                found @ MemberResolution::Found(_) => return found,
                MemberResolution::Unknown => unknown = true,
                MemberResolution::NotFound => {}
            }
        }
        if unknown {
            MemberResolution::Unknown
        } else {
            MemberResolution::NotFound
        }
    }

    /// Search an interface and its superinterfaces for a method (first match wins).
    fn resolve_iface_method(
        &self,
        iface: ClassName,
        key: MemberKey,
        seen: &mut HashSet<ClassName>,
    ) -> MemberResolution {
        if !seen.insert(iface) {
            return MemberResolution::NotFound;
        }
        let Some((idx, entry)) = self.class(iface) else {
            return MemberResolution::Unknown;
        };
        if let Some(access) = find_member(idx.methods_of(entry), key) {
            return MemberResolution::Found(ResolvedMember {
                owner: iface,
                access,
            });
        }
        let mut unknown = false;
        for &super_iface in idx.interfaces_of(entry) {
            match self.resolve_iface_method(super_iface, key, seen) {
                found @ MemberResolution::Found(_) => return found,
                MemberResolution::Unknown => unknown = true,
                MemberResolution::NotFound => {}
            }
        }
        if unknown {
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

/// `<init>` (constructor) and `<clinit>` (class initializer): never inherited.
fn is_constructor(key: MemberKey) -> bool {
    let name = key.name.as_str();
    name == "<init>" || name == "<clinit>"
}

pub(crate) fn is_object_method(key: MemberKey) -> bool {
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
            nest_host: None,
            permitted: None,
            sealing_unknown: false,
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

    fn field_class(
        name: &str,
        super_name: Option<&str>,
        interfaces: &[&str],
        fields: &[(&str, &str, u16)],
    ) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: super_name.map(intern),
            interfaces: interfaces.iter().map(|i| intern(i)).collect(),
            methods: build_members([]),
            fields: build_members(fields.iter().map(|(n, d, a)| (MemberKey::new(n, d), *a))),
            nest_host: None,
            permitted: None,
            sealing_unknown: false,
        }
    }

    fn resolved_owner(idx: &ApiIndex, owner: &str, key: MemberKey, kind: MemberKind) -> String {
        match Scope::new(vec![idx]).resolve_member(intern(owner), key, kind) {
            MemberResolution::Found(m) => m.owner.as_str().to_string(),
            other => panic!("expected Found, got {other:?}"),
        }
    }

    #[test]
    fn field_resolution_prefers_superinterface_over_superclass() {
        // JVMS 5.4.3.2: a field on both a superinterface and the superclass resolves
        // to the interface. Getting this wrong attributes the static/access verdict to
        // the wrong owner (interface fields are implicitly static final).
        use crate::model::{ACC_FINAL, ACC_STATIC};
        let mut c = field_class("a/C", Some("a/Base"), &["a/I"], &[]);
        c.access = ACC_PUBLIC;
        let base = field_class(
            "a/Base",
            Some(JAVA_LANG_OBJECT),
            &[],
            &[("x", "I", ACC_PUBLIC)],
        );
        let mut i = field_class(
            "a/I",
            None,
            &[],
            &[("x", "I", ACC_PUBLIC | ACC_STATIC | ACC_FINAL)],
        );
        i.access = ACC_PUBLIC;
        let idx = ApiIndex::build([c, base, i]);
        assert_eq!(
            resolved_owner(&idx, "a/C", MemberKey::new("x", "I"), MemberKind::Field),
            "a/I"
        );
    }

    #[test]
    fn method_resolution_prefers_superclass_chain_over_interface() {
        // JVMS 5.4.3.3: the full superclass chain is searched before any interface,
        // so a method on a grandparent class wins over one on a directly-implemented
        // interface.
        let mut c = class("a/C", Some("a/Mid"), &[]);
        c.interfaces = vec![intern("a/I")];
        let mid = class("a/Mid", Some("a/Grand"), &[]);
        let grand = class("a/Grand", Some(JAVA_LANG_OBJECT), &[("m", "()V")]);
        let i = class("a/I", None, &[("m", "()V")]);
        let idx = ApiIndex::build([c, mid, grand, i]);
        assert_eq!(
            resolved_owner(&idx, "a/C", MemberKey::new("m", "()V"), MemberKind::Method),
            "a/Grand"
        );
    }

    #[test]
    fn field_resolution_is_unknown_when_an_interface_branch_escapes() {
        // The field is on the superclass, but an unscanned superinterface could also
        // declare it, so the interface-first search cannot conclude and yields Unknown.
        let mut c = field_class("a/C", Some("a/Base"), &["ext/I"], &[]);
        c.access = ACC_PUBLIC;
        let base = field_class(
            "a/Base",
            Some(JAVA_LANG_OBJECT),
            &[],
            &[("x", "I", ACC_PUBLIC)],
        );
        let idx = ApiIndex::build([c, base]);
        assert_eq!(
            Scope::new(vec![&idx]).resolve_member(
                intern("a/C"),
                MemberKey::new("x", "I"),
                MemberKind::Field
            ),
            MemberResolution::Unknown
        );
    }
}
