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
    })
}

/// Extract the internal name of this_class.
pub fn class_name_of(rc: &RawClass) -> Result<Sym> {
    Ok(intern(&rc.class_name(rc.this_class)?))
}

/// For pass 1: extract only the information needed for the hierarchy graph.
/// The point is to avoid touching (and interning) member names and descriptors.
pub fn extract_hierarchy(rc: &RawClass) -> Result<(Sym, Option<Sym>, Vec<Sym>)> {
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
    Ok((name, super_name, interfaces))
}

/// Enumerate symbol references from the constant pool and return only those whose owner
/// satisfies accept.
/// - Filtering is inline because most classes contribute zero accepted references, so
///   building a Vec of all references and then discarding it is wasteful.
/// - MethodHandle points to Methodref-like entries, so no special handling is needed
///   (the scan covers it naturally).
/// - InvokeDynamic NameAndType entries are bootstrap synthetic names and are out of scope.
/// - Array owners (clone on "[Ljava/lang/Object;", etc.) are unwrapped to element types;
///   primitive arrays are excluded.
pub fn extract_refs(rc: &RawClass, accept: impl Fn(Sym) -> bool) -> Result<Vec<SymbolRef>> {
    let mut refs = Vec::new();
    let mut code_ref_indices = vec![false; rc.cp().len()];
    for method in &rc.methods {
        for code_ref in &method.code_refs {
            if let Some(slot) = code_ref_indices.get_mut(code_ref.cp_index as usize) {
                *slot = true;
            }
        }
    }
    for (idx, entry) in rc.cp().iter().enumerate() {
        if let CpEntry::Class { name } = *entry {
            let raw = rc.utf8(name)?;
            if let Some(owner) = object_class_of(&raw) {
                let owner = intern(owner);
                if accept(owner) {
                    refs.push(SymbolRef {
                        kind: RefKind::Class,
                        owner,
                        member: None,
                        expected_static: None,
                        field_write: None,
                    });
                }
            }
            continue;
        }
        if code_ref_indices.get(idx).copied().unwrap_or(false) {
            continue;
        }
        if let Some(r) = ref_from_cp_entry(rc, entry, None, None, &accept)? {
            refs.push(r);
        }
    }
    for method in &rc.methods {
        for code_ref in &method.code_refs {
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
    accept: &impl Fn(Sym) -> bool,
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
    let owner = intern(&raw_owner);
    if !accept(owner) {
        return Ok(None);
    }
    let (name, descriptor) = rc.name_and_type(nat_index)?;
    Ok(Some(SymbolRef {
        kind,
        owner,
        member: Some(MemberKey::new(&name, &descriptor)),
        expected_static,
        field_write,
    }))
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
    use super::object_class_of;

    #[test]
    fn object_class_of_unwraps_arrays() {
        assert_eq!(object_class_of("foo/Bar"), Some("foo/Bar"));
        assert_eq!(object_class_of("[Lfoo/Bar;"), Some("foo/Bar"));
        assert_eq!(object_class_of("[[Lfoo/Bar;"), Some("foo/Bar"));
        assert_eq!(object_class_of("[I"), None);
        assert_eq!(object_class_of("[[J"), None);
    }
}
