use crate::index::{ApiIndex, MemberKind, Resolution};
use crate::intern::Sym;
use crate::model::{ACC_FINAL, ACC_PRIVATE, ACC_STATIC, BreakingChange, ClassName, MemberKey};

/// List APIs that existed in old but can no longer be resolved in new, plus incompatibility
/// changes on surviving members (access narrowing, static<->instance, newly final).
/// - If a member merely moved to a superclass or superinterface, runtime linking succeeds,
///   so it is not breaking (hierarchical resolution).
/// - If resolution escapes to a parent outside the index and cannot be proven, it is also
///   not reported as breaking (conservative).
/// - Private members cannot be linked from outside, so they are not reported
///   (they are still indexed and affect check resolution).
pub fn diff(old: &ApiIndex, new: &ApiIndex) -> Vec<BreakingChange> {
    let mut changes = Vec::new();
    let mut old_classes: Vec<_> = old.classes.iter().collect();
    // Sym IDs follow nondeterministic intern order, so stabilize output by string value.
    old_classes.sort_by_key(|(name, _)| name.as_str());

    for (&name, entry) in old_classes {
        if !new.contains_class(name) {
            // Fold member removals into the class removal.
            changes.push(BreakingChange::ClassRemoved { class: name });
            continue;
        }
        let new_entry = new.classes.get(&name).expect("checked above");
        if access_narrowed(entry.access, new_entry.access) {
            changes.push(BreakingChange::ClassAccessNarrowed {
                class: name,
                old_access: entry.access,
                new_access: new_entry.access,
            });
        }
        if entry.access & ACC_FINAL == 0 && new_entry.access & ACC_FINAL != 0 {
            changes.push(BreakingChange::ClassBecameFinal { class: name });
        }

        for (key, old_access) in visible_sorted(old.methods_of(entry)) {
            if let Some(new_access) = new.direct_method_access(name, key) {
                if access_narrowed(old_access, new_access) {
                    changes.push(BreakingChange::MethodAccessNarrowed {
                        class: name,
                        name: key.name,
                        descriptor: key.descriptor,
                        old_access,
                        new_access,
                    });
                }
                if old_access & ACC_STATIC != new_access & ACC_STATIC {
                    changes.push(BreakingChange::MethodStaticChanged {
                        class: name,
                        name: key.name,
                        descriptor: key.descriptor,
                        old_static: old_access & ACC_STATIC != 0,
                        new_static: new_access & ACC_STATIC != 0,
                    });
                }
                if old_access & ACC_FINAL == 0 && new_access & ACC_FINAL != 0 {
                    changes.push(BreakingChange::MethodBecameFinal {
                        class: name,
                        name: key.name,
                        descriptor: key.descriptor,
                    });
                }
            } else if new.resolve(name, key, MemberKind::Method) == Resolution::NotFound {
                changes.push(BreakingChange::MethodRemoved {
                    class: name,
                    name: key.name,
                    descriptor: key.descriptor,
                    replacement_descriptors: replacements(new, name, key, MemberKind::Method),
                });
            }
        }
        for (key, old_access) in visible_sorted(old.fields_of(entry)) {
            if let Some(new_access) = new.direct_field_access(name, key) {
                if access_narrowed(old_access, new_access) {
                    changes.push(BreakingChange::FieldAccessNarrowed {
                        class: name,
                        name: key.name,
                        descriptor: key.descriptor,
                        old_access,
                        new_access,
                    });
                }
                if old_access & ACC_STATIC != new_access & ACC_STATIC {
                    changes.push(BreakingChange::FieldStaticChanged {
                        class: name,
                        name: key.name,
                        descriptor: key.descriptor,
                        old_static: old_access & ACC_STATIC != 0,
                        new_static: new_access & ACC_STATIC != 0,
                    });
                }
                if old_access & ACC_FINAL == 0 && new_access & ACC_FINAL != 0 {
                    changes.push(BreakingChange::FieldBecameFinal {
                        class: name,
                        name: key.name,
                        descriptor: key.descriptor,
                    });
                }
            } else if new.resolve(name, key, MemberKind::Field) == Resolution::NotFound {
                changes.push(BreakingChange::FieldRemoved {
                    class: name,
                    name: key.name,
                    descriptor: key.descriptor,
                    replacement_descriptors: replacements(new, name, key, MemberKind::Field),
                });
            }
        }
    }
    changes
}

fn access_narrowed(old_access: u16, new_access: u16) -> bool {
    access_rank(new_access) < access_rank(old_access)
}

fn access_rank(access: u16) -> u8 {
    if access & crate::model::ACC_PUBLIC != 0 {
        3
    } else if access & crate::model::ACC_PROTECTED != 0 {
        2
    } else if access & ACC_PRIVATE == 0 {
        1
    } else {
        0
    }
}

/// Members excluding private ones, sorted by string value for display.
fn visible_sorted(members: &[(MemberKey, u16)]) -> Vec<(MemberKey, u16)> {
    let mut v: Vec<_> = members
        .iter()
        .copied()
        .filter(|&(_, acc)| acc & ACC_PRIVATE == 0)
        .collect();
    v.sort_by_key(|&(k, _)| (k.name.as_str(), k.descriptor.as_str()));
    v
}

/// Same-name, different-descriptor members left in the new class (signature-change hints).
fn replacements(new: &ApiIndex, class: ClassName, key: MemberKey, kind: MemberKind) -> Vec<Sym> {
    let Some(entry) = new.classes.get(&class) else {
        return vec![];
    };
    let members = match kind {
        MemberKind::Method => new.methods_of(entry),
        MemberKind::Field => new.fields_of(entry),
    };
    let mut descs: Vec<Sym> = members
        .iter()
        .filter(|(k, _)| k.name == key.name && k.descriptor != key.descriptor)
        .map(|(k, _)| k.descriptor)
        .collect();
    descs.sort_by_key(|d| d.as_str());
    descs
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::JAVA_LANG_OBJECT;
    use crate::intern::intern;
    use crate::model::{ACC_FINAL, ACC_PUBLIC, ClassApi, build_members};

    fn class(name: &str, super_name: Option<&str>, methods: &[(&str, &str, u16)]) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: super_name.map(intern),
            interfaces: vec![],
            methods: build_members(
                methods
                    .iter()
                    .map(|(n, d, acc)| (MemberKey::new(n, d), *acc)),
            ),
            fields: build_members([]),
            nest_host: None,
        }
    }

    fn class_with_fields(name: &str, fields: &[(&str, &str, u16)]) -> ClassApi {
        ClassApi {
            name: intern(name),
            access: ACC_PUBLIC,
            super_name: Some(intern(JAVA_LANG_OBJECT)),
            interfaces: vec![],
            methods: build_members([]),
            fields: build_members(
                fields
                    .iter()
                    .map(|(n, d, acc)| (MemberKey::new(n, d), *acc)),
            ),
            nest_host: None,
        }
    }

    #[test]
    fn detects_method_removal() {
        let old = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()J", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([class("a/C", Some(JAVA_LANG_OBJECT), &[])]);
        let changes = diff(&old, &new);
        assert_eq!(changes.len(), 1);
        assert!(matches!(
            &changes[0],
            BreakingChange::MethodRemoved { class, name, .. }
                if class.as_str() == "a/C" && name.as_str() == "m"
        ));
    }

    #[test]
    fn method_moved_to_superclass_is_not_breaking() {
        let old = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([
            class("a/C", Some("a/D"), &[]),
            class("a/D", Some(JAVA_LANG_OBJECT), &[("m", "()V", ACC_PUBLIC)]),
        ]);
        assert!(diff(&old, &new).is_empty());
    }

    #[test]
    fn descriptor_change_reports_replacement() {
        let old = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()J", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()I", ACC_PUBLIC)],
        )]);
        let changes = diff(&old, &new);
        assert_eq!(changes.len(), 1);
        assert!(matches!(
            &changes[0],
            BreakingChange::MethodRemoved { replacement_descriptors, .. }
                if replacement_descriptors.iter().map(|d| d.as_str()).eq(["()I"])
        ));
    }

    #[test]
    fn class_removal_folds_member_removals() {
        let old = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()V", ACC_PUBLIC), ("n", "()V", ACC_PUBLIC)],
        )]);
        let new = ApiIndex::build([]);
        let changes = diff(&old, &new);
        assert_eq!(changes.len(), 1);
        assert!(matches!(
            &changes[0],
            BreakingChange::ClassRemoved { class } if class.as_str() == "a/C"
        ));
    }

    #[test]
    fn private_members_are_not_reported() {
        let old = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()V", ACC_PRIVATE)],
        )]);
        let new = ApiIndex::build([class("a/C", Some(JAVA_LANG_OBJECT), &[])]);
        assert!(diff(&old, &new).is_empty());
    }

    #[test]
    fn detects_field_became_final() {
        let old = ApiIndex::build([class_with_fields("a/C", &[("x", "I", ACC_PUBLIC)])]);
        let new = ApiIndex::build([class_with_fields(
            "a/C",
            &[("x", "I", ACC_PUBLIC | ACC_FINAL)],
        )]);
        let changes = diff(&old, &new);
        assert!(matches!(
            &changes[0],
            BreakingChange::FieldBecameFinal { class, name, descriptor }
                if class.as_str() == "a/C"
                    && name.as_str() == "x"
                    && descriptor.as_str() == "I"
        ));
    }

    #[test]
    fn external_supertype_suppresses_removal() {
        let old = ApiIndex::build([class(
            "a/C",
            Some(JAVA_LANG_OBJECT),
            &[("m", "()V", ACC_PUBLIC)],
        )]);
        // The parent changed to a class outside the index in the new version, so this cannot be proven and is not reported.
        let new = ApiIndex::build([class("a/C", Some("ext/Base"), &[])]);
        assert!(diff(&old, &new).is_empty());
    }
}
