use crate::classfile::{CpEntry, RawClass};
use crate::intern::{Sym, intern};
use crate::model::{ClassApi, MemberKey, RefKind, SymbolRef, build_members};
use anyhow::Result;

/// Extract the API surface from RawClass.
pub fn extract_api(rc: &RawClass) -> Result<ClassApi> {
    let name = intern(&rc.class_name(rc.this_class)?);
    // super_class = 0 only for java/lang/Object itself.
    let super_name = if rc.super_class == 0 {
        None
    } else {
        Some(intern(&rc.class_name(rc.super_class)?))
    };
    let interfaces = rc
        .interfaces
        .iter()
        .map(|&i| Ok(intern(&rc.class_name(i)?)))
        .collect::<Result<Vec<_>>>()?;

    let methods = build_members(
        rc.methods
            .iter()
            .map(|m| {
                Ok((
                    MemberKey::new(&rc.utf8(m.name_index)?, &rc.utf8(m.descriptor_index)?),
                    m.access,
                ))
            })
            .collect::<Result<Vec<_>>>()?,
    );
    let fields = build_members(
        rc.fields
            .iter()
            .map(|f| {
                Ok((
                    MemberKey::new(&rc.utf8(f.name_index)?, &rc.utf8(f.descriptor_index)?),
                    f.access,
                ))
            })
            .collect::<Result<Vec<_>>>()?,
    );

    Ok(ClassApi {
        name,
        access: rc.access,
        super_name,
        interfaces,
        methods,
        fields,
        nest_host: nest_host_of(rc)?,
    })
}

fn nest_host_of(rc: &RawClass) -> Result<Option<Sym>> {
    if rc.nest_host == 0 {
        return Ok(None);
    }
    Ok(Some(intern(&rc.class_name(rc.nest_host)?)))
}

/// Extract the internal name of this_class.
pub fn class_name_of(rc: &RawClass) -> Result<Sym> {
    Ok(intern(&rc.class_name(rc.this_class)?))
}

/// For pass 1: extract only the information needed for the hierarchy graph.
/// The point is to avoid touching (and interning) member names and descriptors.
pub fn extract_hierarchy(rc: &RawClass) -> Result<(Sym, Option<Sym>, Vec<Sym>, Option<Sym>)> {
    let name = intern(&rc.class_name(rc.this_class)?);
    let super_name = if rc.super_class == 0 {
        None
    } else {
        Some(intern(&rc.class_name(rc.super_class)?))
    };
    let interfaces = rc
        .interfaces
        .iter()
        .map(|&i| Ok(intern(&rc.class_name(i)?)))
        .collect::<Result<Vec<_>>>()?;
    Ok((name, super_name, interfaces, nest_host_of(rc)?))
}

/// Constant-pool index is referenced by an invoke/get/put opcode (so it is handled with
/// static/write context in the code-ref loop, not the plain constant-pool loop).
const CP_CODE_REF: u8 = 1;
/// Constant-pool Class index is the target of a `new` opcode (InstantiationError check).
const CP_INSTANTIATED: u8 = 2;

/// Enumerate symbol references from the constant pool and return only those whose owner
/// satisfies accept.
/// - Filtering is inline because most classes contribute zero accepted references, so
///   building a Vec of all references and then discarding it is wasteful.
/// - `accept` takes the raw (un-interned) owner name so the common reject path never
///   touches the intern pool; only accepted owners are interned.
/// - MethodHandle points to Methodref-like entries, so no special handling is needed
///   (the scan covers it naturally).
/// - InvokeDynamic NameAndType entries are bootstrap synthetic names and are out of scope.
/// - Array owners (clone on "[Ljava/lang/Object;", etc.) are unwrapped to element types;
///   primitive arrays are excluded.
pub fn extract_refs(rc: &RawClass, accept: impl Fn(&str) -> bool) -> Result<Vec<SymbolRef>> {
    let mut refs = Vec::new();
    // One byte-flag per constant-pool slot instead of two bool vectors: halves the
    // per-class allocation and zeroing, which showed up once interning stopped dominating.
    let mut cp_flags = vec![0u8; rc.cp().len()];
    for method in &rc.methods {
        for code_ref in &method.code_refs {
            // `new` targets a Class constant: it marks that constant as
            // instantiated instead of joining the member-ref handling below.
            let bit = if code_ref.opcode == 0xbb {
                CP_INSTANTIATED
            } else {
                CP_CODE_REF
            };
            if let Some(slot) = cp_flags.get_mut(code_ref.cp_index as usize) {
                *slot |= bit;
            }
        }
    }
    for (idx, entry) in rc.cp().iter().enumerate() {
        if let CpEntry::Class { name } = *entry {
            let raw = rc.utf8(name)?;
            // Test the raw name before interning: an owner outside the checked library
            // (the vast majority) is dropped without touching the intern pool.
            if let Some(owner) = object_class_of(&raw)
                && accept(owner)
            {
                refs.push(SymbolRef {
                    kind: RefKind::Class,
                    owner: intern(owner),
                    member: None,
                    expected_static: None,
                    field_write: None,
                    instantiated: (cp_flags[idx] & CP_INSTANTIATED != 0).then_some(true),
                });
            }
            continue;
        }
        if cp_flags[idx] & CP_CODE_REF != 0 {
            continue;
        }
        if let Some(r) = ref_from_cp_entry(rc, entry, None, None, &accept)? {
            refs.push(r);
        }
    }
    for method in &rc.methods {
        for code_ref in &method.code_refs {
            if code_ref.opcode == 0xbb {
                continue;
            }
            let expected_static = match code_ref.opcode {
                0xb2 | 0xb3 | 0xb8 => Some(true),
                0xb4..=0xb7 | 0xb9 => Some(false),
                _ => None,
            };
            let field_write = match code_ref.opcode {
                0xb2 | 0xb4 => Some(false),
                0xb3 | 0xb5 => Some(true),
                _ => None,
            };
            let Some(entry) = rc.cp().get(code_ref.cp_index as usize) else {
                continue;
            };
            if let Some(r) = ref_from_cp_entry(rc, entry, expected_static, field_write, &accept)? {
                refs.push(r);
            }
        }
    }
    Ok(refs)
}

fn ref_from_cp_entry(
    rc: &RawClass,
    entry: &CpEntry<'_>,
    expected_static: Option<bool>,
    field_write: Option<bool>,
    accept: &impl Fn(&str) -> bool,
) -> Result<Option<SymbolRef>> {
    let (kind, class_index, nat_index) = match *entry {
        CpEntry::Methodref {
            class,
            name_and_type,
        } => (RefKind::Method, class, name_and_type),
        CpEntry::InterfaceMethodref {
            class,
            name_and_type,
        } => (RefKind::InterfaceMethod, class, name_and_type),
        CpEntry::Fieldref {
            class,
            name_and_type,
        } => (RefKind::Field, class, name_and_type),
        _ => return Ok(None),
    };
    let raw_owner = rc.class_name(class_index)?;
    // Methods on array owners (clone, etc.) come from java/lang/Object and are out of scope.
    if raw_owner.starts_with('[') {
        return Ok(None);
    }
    // Filter by raw name first; intern the owner (and the member name/descriptor) only
    // for the few references that target the checked library.
    if !accept(&raw_owner) {
        return Ok(None);
    }
    let (name, descriptor) = rc.name_and_type(nat_index)?;
    Ok(Some(SymbolRef {
        kind,
        owner: intern(&raw_owner),
        member: Some(MemberKey::new(&name, &descriptor)),
        expected_static,
        field_write,
        instantiated: None,
    }))
}

/// Class-load edges for reachability: every Class constant (arrays unwrapped to element
/// types) plus string constants shaped like binary class names ("com.foo.Bar"), which
/// over-approximates Class.forName / ServiceLoader-style dynamic loading. Shaped strings
/// are interned unconditionally so the edge does not depend on whether the named class
/// was parsed before or after this one (determinism); non-class strings become dead
/// symbols that reachability simply never marks.
pub fn extract_edges(rc: &RawClass, self_name: Sym) -> Vec<Sym> {
    let mut edges = Vec::new();
    for entry in rc.cp() {
        let sym = match *entry {
            CpEntry::Class { name } => match rc.utf8(name) {
                Ok(raw) => object_class_of(&raw).map(intern),
                Err(_) => None,
            },
            CpEntry::Str { utf8 } => match rc.utf8(utf8) {
                Ok(raw) => slashed_class_name(&raw).map(|s| intern(&s)),
                Err(_) => None,
            },
            _ => None,
        };
        if let Some(sym) = sym
            && sym != self_name
        {
            edges.push(sym);
        }
    }
    edges.sort_unstable();
    edges.dedup();
    edges
}

/// "com.foo.Bar" -> Some("com/foo/Bar") when shaped like a binary class name:
/// dot-separated Java identifier segments (ASCII), at least one dot.
fn slashed_class_name(s: &str) -> Option<String> {
    if s.len() < 3 || s.len() > 300 || !s.contains('.') {
        return None;
    }
    for segment in s.split('.') {
        let bytes = segment.as_bytes();
        let valid = matches!(bytes.first(), Some(b) if b.is_ascii_alphabetic() || *b == b'_' || *b == b'$')
            && bytes[1..]
                .iter()
                .all(|b| b.is_ascii_alphanumeric() || *b == b'_' || *b == b'$');
        if !valid {
            return None;
        }
    }
    Some(s.replace('.', "/"))
}

/// Extract the object class name from a Class entry name.
/// "foo/Bar" -> Some("foo/Bar"), "[[Lfoo/Bar;" -> Some("foo/Bar"), "[I" -> None
fn object_class_of(raw: &str) -> Option<&str> {
    let stripped = raw.trim_start_matches('[');
    if stripped.len() == raw.len() {
        return Some(raw);
    }
    stripped.strip_prefix('L')?.strip_suffix(';')
}

#[cfg(test)]
mod tests {
    use super::{object_class_of, slashed_class_name};

    #[test]
    fn object_class_of_unwraps_arrays() {
        assert_eq!(object_class_of("foo/Bar"), Some("foo/Bar"));
        assert_eq!(object_class_of("[Lfoo/Bar;"), Some("foo/Bar"));
        assert_eq!(object_class_of("[[Lfoo/Bar;"), Some("foo/Bar"));
        assert_eq!(object_class_of("[I"), None);
        assert_eq!(object_class_of("[[J"), None);
    }

    #[test]
    fn slashed_class_name_accepts_binary_names_only() {
        assert_eq!(
            slashed_class_name("com.foo.Bar$Baz").as_deref(),
            Some("com/foo/Bar$Baz")
        );
        assert_eq!(slashed_class_name("os.name").as_deref(), Some("os/name")); // Package-shaped: dead symbol, harmless.
        assert_eq!(slashed_class_name("Bar"), None); // No dot.
        assert_eq!(slashed_class_name("com..Bar"), None); // Empty segment.
        assert_eq!(slashed_class_name("com.foo.Bar Baz"), None); // Space.
        assert_eq!(slashed_class_name("com/foo/Bar"), None); // Already slashed: not a forName argument shape.
        assert_eq!(slashed_class_name("1.2.3"), None); // Segments cannot start with digits.
        assert_eq!(slashed_class_name("a.b().c"), None);
    }
}
