use crate::intern::{Sym, intern};
use serde::Serialize;

/// Interned JVM internal name ("kotlinx/coroutines/EventLoopKt").
pub type ClassName = Sym;

pub const ACC_PUBLIC: u16 = 0x0001;
pub const ACC_PRIVATE: u16 = 0x0002;
pub const ACC_PROTECTED: u16 = 0x0004;
pub const ACC_STATIC: u16 = 0x0008;
pub const ACC_FINAL: u16 = 0x0010;
/// Compiler-generated bridge method (covariant returns, generic erasure).
pub const ACC_BRIDGE: u16 = 0x0040;
pub const ACC_INTERFACE: u16 = 0x0200;
pub const ACC_ABSTRACT: u16 = 0x0400;
/// Compiler-generated member not present in the source (synthetic).
pub const ACC_SYNTHETIC: u16 = 0x1000;

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
    /// NestHost attribute target (nestmate private access); None when absent.
    pub nest_host: Option<ClassName>,
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
    /// A concrete class became abstract (or an interface): instantiating it now
    /// throws InstantiationError.
    ClassBecameAbstract {
        class: ClassName,
    },
    /// class <-> interface flip: references and hierarchy edges compiled against
    /// the old kind fail with IncompatibleClassChangeError.
    ClassKindChanged {
        class: ClassName,
        /// True when the old side was the interface (interface -> class).
        old_interface: bool,
    },
    /// A concrete method became abstract: a subclass compiled without an
    /// override inherits nothing to call (AbstractMethodError).
    MethodBecameAbstract {
        class: ClassName,
        name: Sym,
        descriptor: Sym,
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

/// Why a reference or scanned class breaks. A closed set so every consumer (verdicts,
/// report rendering, merging) matches exhaustively: adding a variant is a compile error
/// at each site, never a silently unhandled string. JSON, the goldens, and the verdicts
/// stream serialize through `as_str`, and any ordering of reasons must also go through
/// `as_str` — deliberately no `Ord` derive, so nothing can sort by variant order (the
/// same determinism rule as never sorting by `Sym` id).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Reason {
    ClassRemoved,
    ClassAccessNarrowed,
    ClassBecameAbstract,
    ClassBecameFinal,
    ClassKindChanged,
    ExtendsFinalClass,
    MethodRemoved,
    MethodAccessNarrowed,
    MethodBecameAbstract,
    MethodBecameFinal,
    FieldRemoved,
    FieldAccessNarrowed,
    FieldBecameFinal,
    StaticToInstance,
    InstanceToStatic,
}

impl Reason {
    /// ADD NEW VARIANTS HERE TOO — the compiler cannot check this, since an exhaustive
    /// `match` forces a new arm and never a new array entry. A variant missing here loses
    /// its exclude-rule `kind` string, so a legitimate rule fails with `unknown kind`.
    pub const ALL: [Reason; 15] = [
        Reason::ClassRemoved,
        Reason::ClassAccessNarrowed,
        Reason::ClassBecameAbstract,
        Reason::ClassBecameFinal,
        Reason::ClassKindChanged,
        Reason::ExtendsFinalClass,
        Reason::MethodRemoved,
        Reason::MethodAccessNarrowed,
        Reason::MethodBecameAbstract,
        Reason::MethodBecameFinal,
        Reason::FieldRemoved,
        Reason::FieldAccessNarrowed,
        Reason::FieldBecameFinal,
        Reason::StaticToInstance,
        Reason::InstanceToStatic,
    ];

    /// The stable wire/report string. Everything user-visible (JSON, verdicts stream,
    /// text report, sort order) speaks this form.
    pub fn as_str(self) -> &'static str {
        match self {
            Reason::ClassRemoved => "class removed",
            Reason::ClassAccessNarrowed => "class access narrowed",
            Reason::ClassBecameAbstract => "class became abstract",
            Reason::ClassBecameFinal => "class became final",
            Reason::ClassKindChanged => "class kind changed",
            Reason::ExtendsFinalClass => "extends final class",
            Reason::MethodRemoved => "method removed",
            Reason::MethodAccessNarrowed => "method access narrowed",
            Reason::MethodBecameAbstract => "method became abstract",
            Reason::MethodBecameFinal => "method became final",
            Reason::FieldRemoved => "field removed",
            Reason::FieldAccessNarrowed => "field access narrowed",
            Reason::FieldBecameFinal => "field became final",
            Reason::StaticToInstance => "member changed from static to instance",
            Reason::InstanceToStatic => "member changed from instance to static",
        }
    }

    /// The exclude-rule `kind` spelling: `as_str` with underscores ("method_became_abstract").
    /// Derived rather than a second list of literals, so the two cannot drift. The wire form
    /// keeps its spaces — JSON, the verdicts stream and the goldens are unchanged.
    pub fn config_str(self) -> String {
        self.as_str().replace(' ', "_")
    }

    /// Accepts both spellings: `_` <-> ` ` is a 1:1 swap over this set, so normalizing is
    /// unambiguous and a `reason` pasted straight out of a report works.
    pub fn parse(s: &str) -> Option<Reason> {
        let spaced = s.replace('_', " ");
        Reason::ALL.iter().copied().find(|r| r.as_str() == spaced)
    }
}

impl Serialize for Reason {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
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
    /// Some(true) for a Class reference targeted by a `new` instruction
    /// (instantiating an abstract class or interface throws InstantiationError).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub instantiated: Option<bool>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Violation {
    /// Origin (JAR path or directory).
    pub source: Sym,
    pub source_class: ClassName,
    pub reference: SymbolRef,
    pub reason: Reason,
    /// Whether the referencing class is class-load reachable from the application.
    /// None when reachability was not computed (no --reachability / no app roots).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reachable: Option<bool>,
    /// Only for `method became abstract` violations (None on every other reason): whether
    /// any scanned class holds a method reference that could invoke the affected member.
    /// AbstractMethodError throws at invocation, not at class load, so `Some(false)` means
    /// the break is latent — the class loads and instantiates fine, and nothing in scanned
    /// bytecode can trigger the throw (reflection, JNI, and unscanned code stay invisible,
    /// so this is evidence, not proof).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub invocation_found: Option<bool>,
    /// Actionable fix hint attributing the break to the artifacts involved. Only populated by
    /// upgrade-check (where dumps carry coordinates); None otherwise.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suggestion: Option<Suggestion>,
    /// Build modules (dump module paths) whose runtime classpath exhibits this violation.
    /// Populated only by per-module upgrade-check; empty otherwise, and omitted from JSON
    /// so plain check output (and the goldens pinning it) stays byte-identical.
    #[serde(skip_serializing_if = "Vec::is_empty", default)]
    pub modules: Vec<String>,
}

/// Whether a violation's `reachable` flag puts it in the reachable group: proven-reachable
/// (`Some(true)`) and unproven (`None`) both count; only proven-not-reachable (`Some(false)`)
/// does not.
pub fn counts_as_reachable(reachable: Option<bool>) -> bool {
    reachable != Some(false)
}

/// Report/gating tier of one violation, most severe first. This is the single boundary
/// shared by the report's 💥/💤/⚠️ section split and `--fail-on reachable` gating, so the
/// CI gate always matches the displayed grouping.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tier {
    /// 💥 likely to break: the class is reachable (or unproven) and, for invocation-
    /// triggered reasons, an invocation of the affected member exists in scanned bytecode.
    Breaks,
    /// 💤 latent: the class is reachable but no scanned bytecode invokes the affected
    /// member, so the error cannot throw until something starts calling it. Warning tier —
    /// same conservative stance as Unproven (reflection and JNI stay invisible).
    Latent,
    /// ⚠️ not proven reachable: no static class-load path from the application.
    Unproven,
}

/// Positive evidence that nothing in the scan can invoke the member. Reasons without the
/// invocation axis are never latent.
pub fn is_latent(v: &Violation) -> bool {
    v.invocation_found == Some(false)
}

/// The one policy site mapping evidence to a tier. The report's section split and
/// `--fail-on reachable` both go through it, so the gate always matches what is displayed.
/// Proven-unreachable wins over latent, an unreachable class cannot even load.
///
/// `reachable_axis_valid` is false when app roots were supplied but matched nothing: every
/// violation then carries `reachable = Some(false)` with nothing behind it. The latent axis
/// survives that, its evidence comes from the bytecode rather than from roots.
pub fn tier(v: &Violation, reachable_axis_valid: bool) -> Tier {
    if reachable_axis_valid && !counts_as_reachable(v.reachable) {
        Tier::Unproven
    } else if is_latent(v) {
        Tier::Latent
    } else {
        Tier::Breaks
    }
}

/// Whether a report's reachability labels rest on anything. `Some(false)` means roots were
/// given and matched nothing.
pub fn reachable_axis_valid(app_roots_matched: Option<bool>) -> bool {
    app_roots_matched != Some(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Note this cannot check that `ALL` lists every variant — enumerating them is what
    /// `ALL` is for. See the sync note there.
    #[test]
    fn reason_strings_are_distinct_and_round_trip() {
        for r in Reason::ALL {
            assert_eq!(Reason::parse(r.as_str()), Some(r), "wire form {r:?}");
            assert_eq!(Reason::parse(&r.config_str()), Some(r), "config form {r:?}");
        }
        let mut seen: Vec<&str> = Reason::ALL.iter().map(|r| r.as_str()).collect();
        seen.sort_unstable();
        seen.dedup();
        assert_eq!(seen.len(), Reason::ALL.len(), "duplicate reason strings");
        assert_eq!(Reason::parse("not a reason"), None);
    }
}

/// Which artifacts a violation involves and how to fix it. Coordinates are "group:name[:version]".
#[derive(Debug, Clone, Serialize)]
pub struct Suggestion {
    /// Coordinate of the artifact whose class holds the broken reference. None for app build outputs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub referenced_by: Option<String>,
    /// Coordinate whose version change removed the symbol, and its before -> after versions.
    pub removed_by: String,
    pub before: String,
    pub after: String,
    /// Human-readable fix advice.
    pub advice: String,
}
