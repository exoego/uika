use crate::intern::{Sym, intern};
use serde::Serialize;

/// Interned JVM internal name ("kotlinx/coroutines/EventLoopKt").
pub type ClassName = Sym;

pub const ACC_PUBLIC: u16 = 0x0001;
pub const ACC_PRIVATE: u16 = 0x0002;
pub const ACC_PROTECTED: u16 = 0x0004;
pub const ACC_STATIC: u16 = 0x0008;
pub const ACC_FINAL: u16 = 0x0010;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
pub struct MemberKey {
    pub name: Sym,
    pub descriptor: Sym,
}

impl MemberKey {
    pub fn new(name: &str, descriptor: &str) -> Self {
        Self {
            name: intern(name),
            descriptor: intern(descriptor),
        }
    }
}

/// Member table. Lookups use binary search over a slice sorted by MemberKey (a u32 pair).
/// Classes usually have only a few dozen entries, so this is faster than a HashMap and avoids bucket overhead.
pub type Members = Box<[(MemberKey, u16)]>;

/// Sort and deduplicate into the Members representation.
pub fn build_members(pairs: impl IntoIterator<Item = (MemberKey, u16)>) -> Members {
    let mut v: Vec<_> = pairs.into_iter().collect();
    v.sort_unstable_by_key(|&(k, _)| k);
    v.dedup_by_key(|&mut (k, _)| k);
    v.into_boxed_slice()
}

/// API surface for one class. Bytecode visibility is preserved as-is
/// (Kotlin internal is ACC_PUBLIC). Private members are also registered and filtered at report time.
#[derive(Debug, Clone)]
pub struct ClassApi {
    pub name: ClassName,
    pub access: u16,
    pub super_name: Option<ClassName>,
    pub interfaces: Vec<ClassName>,
    pub methods: Members,
    pub fields: Members,
}

impl ClassApi {
    pub fn has_method(&self, key: MemberKey) -> bool {
        Self::contains(&self.methods, key)
    }

    pub fn has_field(&self, key: MemberKey) -> bool {
        Self::contains(&self.fields, key)
    }

    fn contains(members: &Members, key: MemberKey) -> bool {
        members.binary_search_by_key(&key, |&(k, _)| k).is_ok()
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum BreakingChange {
    ClassRemoved {
        class: ClassName,
    },
    MethodRemoved {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
        /// Hints when the new version has the same name with a different descriptor.
        replacement_descriptors: Vec<Sym>,
    },
    FieldRemoved {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
        replacement_descriptors: Vec<Sym>,
    },
    ClassAccessNarrowed {
        class: ClassName,
        old_access: u16,
        new_access: u16,
    },
    ClassBecameFinal {
        class: ClassName,
    },
    MethodAccessNarrowed {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
        old_access: u16,
        new_access: u16,
    },
    FieldAccessNarrowed {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
        old_access: u16,
        new_access: u16,
    },
    MethodStaticChanged {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
        old_static: bool,
        new_static: bool,
    },
    FieldStaticChanged {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
        old_static: bool,
        new_static: bool,
    },
    FieldBecameFinal {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
    },
    MethodBecameFinal {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RefKind {
    Method,
    InterfaceMethod,
    Field,
    Class,
}

/// Symbol reference extracted from the consumer-side constant pool.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
pub struct SymbolRef {
    pub kind: RefKind,
    pub owner: ClassName,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub member: Option<MemberKey>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expected_static: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub field_write: Option<bool>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Violation {
    /// Origin (JAR path or directory).
    pub source: Sym,
    pub source_class: ClassName,
    pub reference: SymbolRef,
    pub reason: String,
}
