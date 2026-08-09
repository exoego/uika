//! Explicit suppression of known false positives (e.g. reflection-only member access).
//!
//! Reachability (reach.rs) only ever deprioritizes a violation, never drops it, because it
//! cannot see reflection driven by external configuration. An exclude list is the opposite: an
//! operator explicitly asserts "I know about this reference and it is not a real break", so
//! dropping it here is fine as long as the assertion stays visible -- a committed TOML file with
//! a required reason, a suppressed count in the report, and a warning for entries that matched
//! nothing (so the list does not silently rot as the checked libraries change).
//!
//! Rules scope by `owner` (class or trailing-`*` package prefix), by `kind` (a
//! `Reason::config_str` string, or a spelling from before that reason was split, which then
//! names every reason it became), or by both. A kind-only rule is the policy knob "this
//! category is acceptable for us everywhere"; owner+kind pins one category on one library.
//! Kind matching lives here rather than in `--fail-on` so the gate stays a pure tier
//! threshold that always matches the displayed grouping (model::tier).
//!
//! A kind rule inherits the waiver semantics of the rest of the file: matches are DROPPED,
//! not merely un-gated. Right for "we never want to see this category", wrong for "keep
//! showing it, just do not fail the build", which has no mechanism here.

use crate::model::{Reason, Violation};
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ExcludeFile {
    #[serde(default)]
    exclude: Vec<RawEntry>,
}

/// Unknown keys are rejected, not ignored: every scoping field is optional, so a misspelled
/// `onwer` would deserialize to `None` and silently WIDEN the rule to every owner. It would
/// still match, so the unused-rule warning could not catch it either.
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawEntry {
    /// Class or package (trailing-`*` prefix) the rule is scoped to. Optional when `kind`
    /// is present: a kind-only rule suppresses one violation kind across every owner.
    owner: Option<String>,
    member: Option<String>,
    descriptor: Option<String>,
    /// Violation kind, in the snake_case config spelling (e.g. "method_became_abstract").
    /// `Reason::parse_kinds` also accepts the spaced form the report and `--json` print, and
    /// a spelling from before its reason was split. Combines with `owner`, where both
    /// present means both must match.
    kind: Option<String>,
    reason: String,
}

#[derive(Debug)]
enum OwnerPattern {
    Exact(String),
    /// Original text stripped of its trailing '*'; matches by prefix.
    Prefix(String),
}

impl OwnerPattern {
    fn matches(&self, owner: &str) -> bool {
        match self {
            OwnerPattern::Exact(s) => s == owner,
            OwnerPattern::Prefix(p) => owner.starts_with(p.as_str()),
        }
    }
}

impl std::fmt::Display for OwnerPattern {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            OwnerPattern::Exact(s) => write!(f, "{s}"),
            OwnerPattern::Prefix(p) => write!(f, "{p}*"),
        }
    }
}

#[derive(Debug)]
pub struct ExcludeRule {
    /// None (only with a kind) matches every owner.
    owner: Option<OwnerPattern>,
    /// Member name. None excludes the owner outright, matching class-level violations too
    /// (e.g. "class removed").
    member: Option<String>,
    /// Descriptor of the specific overload. None (with a member name) covers every overload,
    /// which over-suppresses when only one overload is a known false positive: pinning the
    /// descriptor keeps a real break on a sibling overload reported.
    descriptor: Option<String>,
    /// Violation kinds the rule is pinned to. Empty matches every kind. More than one only
    /// from a spelling that named a category before it was split, e.g. "class_kind_changed".
    kinds: Vec<Reason>,
    reason: String,
}

impl ExcludeRule {
    fn describe(&self) -> String {
        let mut parts = Vec::new();
        if let Some(owner) = &self.owner {
            parts.push(match (&self.member, &self.descriptor) {
                (Some(m), Some(d)) => format!("{owner}#{m} {d}"),
                (Some(m), None) => format!("{owner}#{m}"),
                (None, _) => format!("{owner}"),
            });
        }
        if !self.kinds.is_empty() {
            // Canonical spelling rather than the one the rule was written in, so a rule
            // written in the spaced form, or in a spelling since split, is echoed in the
            // form the docs use. Each kind is quoted on its own, because a rule names one
            // kind and a joined list would not load if it were pasted back.
            let shown: Vec<String> = self
                .kinds
                .iter()
                .map(|k| format!("\"{}\"", k.config_str()))
                .collect();
            parts.push(format!("kind {}", shown.join(" or ")));
        }
        format!("{} ({})", parts.join(" "), self.reason)
    }
}

/// Parse one exclude file's TOML content into rules.
fn parse(content: &str) -> Result<Vec<ExcludeRule>> {
    let file: ExcludeFile = toml::from_str(content).context("invalid TOML")?;
    file.exclude.into_iter().map(compile).collect()
}

fn compile(entry: RawEntry) -> Result<ExcludeRule> {
    // Best identifier for error messages, whichever scoping field the rule has.
    let label = entry
        .owner
        .clone()
        .or_else(|| entry.kind.clone())
        .unwrap_or_default();
    if entry.reason.trim().is_empty() {
        bail!(
            "exclude rule \"{label}\" is missing a reason (reason must explain why the violation is a known false positive)"
        );
    }
    if entry.owner.is_none() && entry.kind.is_none() {
        bail!(
            "exclude rule needs an owner, a kind, or both (owner scopes it to a class or package, kind to one violation kind such as \"method_became_abstract\")"
        );
    }
    if entry.member.is_some() && entry.owner.is_none() {
        bail!(
            "exclude rule \"{label}\": member requires an owner (a member name only means something on a class)"
        );
    }
    if entry.descriptor.is_some() && entry.member.is_none() {
        bail!(
            "exclude rule \"{label}\": descriptor requires a member (a descriptor pins one overload of a named member)"
        );
    }
    let kinds = entry
        .kind
        .as_deref()
        .map(|k| {
            Reason::parse_kinds(k).ok_or_else(|| {
                anyhow::anyhow!(
                    "exclude rule \"{label}\": unknown kind \"{k}\"; valid kinds: {}",
                    Reason::ALL
                        .iter()
                        .map(|r| r.config_str())
                        .collect::<Vec<_>>()
                        .join(", ")
                )
            })
        })
        .transpose()?
        .unwrap_or_default();
    let owner = entry
        .owner
        .map(|o| {
            let stars = o.matches('*').count();
            if stars > 1 || (stars == 1 && !o.ends_with('*')) {
                bail!(
                    "exclude rule owner \"{o}\": '*' is only supported once, as a trailing wildcard (prefix match)"
                );
            }
            Ok(match o.strip_suffix('*') {
                Some(prefix) => OwnerPattern::Prefix(prefix.to_string()),
                None => OwnerPattern::Exact(o),
            })
        })
        .transpose()?;
    Ok(ExcludeRule {
        owner,
        member: entry.member,
        descriptor: entry.descriptor,
        kinds,
        reason: entry.reason,
    })
}

/// Load and merge exclude rules from one or more TOML files (union of all rules).
pub fn load(paths: &[PathBuf]) -> Result<Vec<ExcludeRule>> {
    let mut rules = Vec::new();
    for path in paths {
        let content = std::fs::read_to_string(path)
            .with_context(|| format!("cannot read exclude file {}", path.display()))?;
        let parsed =
            parse(&content).with_context(|| format!("invalid exclude file {}", path.display()))?;
        rules.extend(parsed);
    }
    Ok(rules)
}

pub struct ExcludeStats {
    pub suppressed: usize,
    /// Human-readable description of each rule that matched no violation.
    pub unused: Vec<String>,
}

/// Drop violations matched by any rule. A violation may match more than one rule; all matching
/// rules are marked used (not just the first), so a redundant-but-still-applicable rule is never
/// reported as stale.
pub fn filter(violations: &mut Vec<Violation>, rules: &[ExcludeRule]) -> ExcludeStats {
    if rules.is_empty() {
        return ExcludeStats {
            suppressed: 0,
            unused: Vec::new(),
        };
    }
    let mut hit = vec![false; rules.len()];
    let mut suppressed = 0usize;
    violations.retain(|v| {
        let owner = v.reference.owner.as_str();
        let member_name = v.reference.member.map(|m| m.name.as_str());
        let member_descriptor = v.reference.member.map(|m| m.descriptor.as_str());
        let mut matched = false;
        for (i, rule) in rules.iter().enumerate() {
            if let Some(pattern) = &rule.owner
                && !pattern.matches(owner)
            {
                continue;
            }
            if !rule.kinds.is_empty() && !rule.kinds.contains(&v.reason) {
                continue;
            }
            let member_matches = match &rule.member {
                None => true,
                Some(name) => {
                    member_name == Some(name.as_str())
                        // A descriptor, when present, pins one overload.
                        && match &rule.descriptor {
                            None => true,
                            Some(d) => member_descriptor == Some(d.as_str()),
                        }
                }
            };
            if member_matches {
                hit[i] = true;
                matched = true;
            }
        }
        if matched {
            suppressed += 1;
        }
        !matched
    });
    let unused = rules
        .iter()
        .zip(hit)
        .filter(|(_, hit)| !hit)
        .map(|(r, _)| r.describe())
        .collect();
    ExcludeStats { suppressed, unused }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::intern::intern;
    use crate::model::{MemberKey, Reason, RefKind, SymbolRef};

    fn class_violation(owner: &str) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern("app/Use"),
            reference: SymbolRef {
                kind: RefKind::Class,
                owner: intern(owner),
                member: None,
                expected_static: None,
                field_write: None,
                instantiated: None,
            },
            reason: Reason::ClassRemoved,
            reachable: None,
            invocation_found: None,
            suggestion: None,
            modules: Vec::new(),
        }
    }

    fn member_violation(owner: &str, name: &str, descriptor: &str) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern("app/Use"),
            reference: SymbolRef {
                kind: RefKind::Field,
                owner: intern(owner),
                member: Some(MemberKey::new(name, descriptor)),
                expected_static: None,
                field_write: None,
                instantiated: None,
            },
            reason: Reason::MethodRemoved,
            reachable: None,
            invocation_found: None,
            suggestion: None,
            modules: Vec::new(),
        }
    }

    #[test]
    fn exact_owner_and_member_suppresses_matching_violation_only() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "org/apache/commons/logging/impl/LogFactoryImpl"
            member = "classesToDiscover"
            reason = "reflectively scanned at init"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            member_violation(
                "org/apache/commons/logging/impl/LogFactoryImpl",
                "classesToDiscover",
                "[Ljava/lang/String;",
            ),
            member_violation(
                "org/apache/commons/logging/impl/LogFactoryImpl",
                "otherField",
                "I",
            ),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 1);
        assert!(stats.unused.is_empty());
        assert_eq!(violations.len(), 1);
        assert_eq!(
            violations[0].reference.member.unwrap().name.as_str(),
            "otherField"
        );
    }

    #[test]
    fn member_rule_matches_any_overload_by_name_only() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "lib/C"
            member = "m"
            reason = "known reflection use regardless of overload"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            member_violation("lib/C", "m", "()V"),
            member_violation("lib/C", "m", "(I)V"),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 2);
        assert!(violations.is_empty());
    }

    #[test]
    fn descriptor_pins_one_overload_and_keeps_siblings_reported() {
        // Only the no-arg overload is a known false positive; the (I)V overload is a
        // real break and must survive.
        let rules = parse(
            r#"
            [[exclude]]
            owner = "lib/C"
            member = "m"
            descriptor = "()V"
            reason = "no-arg overload is invoked reflectively"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            member_violation("lib/C", "m", "()V"),
            member_violation("lib/C", "m", "(I)V"),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 1);
        assert!(stats.unused.is_empty());
        assert_eq!(violations.len(), 1);
        assert_eq!(
            violations[0].reference.member.unwrap().descriptor.as_str(),
            "(I)V"
        );
    }

    #[test]
    fn descriptor_without_member_is_rejected() {
        let err = parse(
            r#"
            [[exclude]]
            owner = "lib/C"
            descriptor = "()V"
            reason = "bogus"
            "#,
        )
        .unwrap_err();
        assert!(
            err.to_string().contains("descriptor requires a member"),
            "{err}"
        );
    }

    #[test]
    fn unused_descriptor_rule_reports_the_pinned_overload() {
        // A descriptor-pinned rule that matches nothing must be reported unused, with the
        // descriptor shown so the operator sees which overload was pinned.
        let rules = parse(
            r#"
            [[exclude]]
            owner = "lib/C"
            member = "m"
            descriptor = "()V"
            reason = "no-arg overload is invoked reflectively"
            "#,
        )
        .unwrap();
        // Only the (I)V overload breaks; the pinned ()V overload never appears.
        let mut violations = vec![member_violation("lib/C", "m", "(I)V")];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 0);
        assert_eq!(violations.len(), 1);
        assert_eq!(stats.unused.len(), 1);
        let msg = &stats.unused[0];
        assert!(msg.contains("lib/C#m ()V"), "{msg}");
        assert!(
            msg.contains("no-arg overload is invoked reflectively"),
            "{msg}"
        );
    }

    #[test]
    fn owner_only_rule_suppresses_class_level_and_member_violations() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "lib/Gone"
            reason = "whole class is a known reflection-only shim"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            class_violation("lib/Gone"),
            member_violation("lib/Gone", "m", "()V"),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 2);
        assert!(violations.is_empty());
    }

    #[test]
    fn trailing_wildcard_matches_by_prefix() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "org/apache/commons/logging/*"
            reason = "entire package uses reflection-based class discovery"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            class_violation("org/apache/commons/logging/impl/LogFactoryImpl"),
            class_violation("org/apache/commons/logging/LogFactory"),
            class_violation("org/other/Unrelated"),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 2);
        assert_eq!(violations.len(), 1);
        assert_eq!(
            violations[0].reference.owner.as_str(),
            "org/other/Unrelated"
        );
    }

    #[test]
    fn non_matching_rule_is_reported_unused() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "lib/NeverReferenced"
            reason = "just in case"
            "#,
        )
        .unwrap();
        let mut violations = vec![class_violation("lib/Other")];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 0);
        assert_eq!(violations.len(), 1);
        assert_eq!(stats.unused.len(), 1);
        assert!(stats.unused[0].contains("lib/NeverReferenced"));
        assert!(stats.unused[0].contains("just in case"));
    }

    #[test]
    fn empty_reason_is_rejected() {
        let err = parse(
            r#"
            [[exclude]]
            owner = "lib/C"
            reason = "   "
            "#,
        )
        .unwrap_err();
        assert!(err.to_string().contains("missing a reason"), "{err}");
    }

    #[test]
    fn wildcard_not_at_end_is_rejected() {
        let err = parse(
            r#"
            [[exclude]]
            owner = "lib/*/Inner"
            reason = "bogus"
            "#,
        )
        .unwrap_err();
        assert!(err.to_string().contains("trailing wildcard"), "{err}");
    }

    #[test]
    fn multiple_wildcards_are_rejected() {
        let err = parse(
            r#"
            [[exclude]]
            owner = "lib/**"
            reason = "bogus"
            "#,
        )
        .unwrap_err();
        assert!(err.to_string().contains("trailing wildcard"), "{err}");
    }

    fn kind_violation(owner: &str, reason: Reason) -> Violation {
        let mut v = class_violation(owner);
        v.reason = reason;
        v
    }

    #[test]
    fn kind_only_rule_suppresses_that_kind_across_all_owners() {
        let rules = parse(
            r#"
            [[exclude]]
            kind = "method_became_abstract"
            reason = "we accept latent AbstractMethodError risk and track it separately"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            kind_violation("lib/A", Reason::MethodBecameAbstract),
            kind_violation("other/B", Reason::MethodBecameAbstract),
            kind_violation("lib/A", Reason::ClassRemoved),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 2);
        assert!(stats.unused.is_empty());
        assert_eq!(violations.len(), 1);
        assert_eq!(violations[0].reason, Reason::ClassRemoved);
    }

    #[test]
    fn owner_and_kind_combine() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "org/conscrypt/*"
            kind = "method_became_abstract"
            reason = "conscrypt adds abstract methods nothing calls yet"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            kind_violation(
                "org/conscrypt/BufferAllocator",
                Reason::MethodBecameAbstract,
            ),
            // Same owner, different kind: still reported.
            kind_violation("org/conscrypt/BufferAllocator", Reason::MethodRemoved),
            // Same kind, different owner: still reported.
            kind_violation("other/Lib", Reason::MethodBecameAbstract),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 1);
        assert_eq!(violations.len(), 2);
    }

    #[test]
    fn member_and_kind_narrow_together() {
        let rules = parse(
            r#"
            [[exclude]]
            owner = "lib/C"
            member = "m"
            kind = "method_became_abstract"
            reason = "only this member's abstractness is known-safe"
            "#,
        )
        .unwrap();
        let mut abstract_m = member_violation("lib/C", "m", "()V");
        abstract_m.reason = Reason::MethodBecameAbstract;
        let mut violations = vec![
            abstract_m,
            // Same member, different kind.
            member_violation("lib/C", "m", "()V"),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 1);
        assert_eq!(violations.len(), 1);
        assert_eq!(violations[0].reason, Reason::MethodRemoved);
    }

    #[test]
    fn unknown_kind_is_rejected_with_the_valid_list() {
        let err = parse(
            r#"
            [[exclude]]
            kind = "method went weird"
            reason = "typo"
            "#,
        )
        .unwrap_err();
        let msg = err.to_string();
        assert!(msg.contains("unknown kind"), "{msg}");
        assert!(msg.contains("method_became_abstract"), "{msg}");
        assert!(!msg.contains("method became abstract"), "{msg}");
    }

    #[test]
    fn misspelled_key_is_rejected_instead_of_widening_the_rule() {
        let err = parse(
            r#"
            [[exclude]]
            onwer = "org/conscrypt/*"
            kind = "method_became_abstract"
            reason = "conscrypt only"
            "#,
        )
        .unwrap_err();
        // The offending key is named by the TOML deserializer, so read the whole chain.
        assert!(format!("{err:#}").contains("onwer"), "{err:#}");
        let err = parse(
            r#"
            [[exclude]]
            owner = "org/conscrypt/*"
            kimd = "method became abstract"
            reason = "conscrypt only"
            "#,
        )
        .unwrap_err();
        assert!(format!("{err:#}").contains("kimd"), "{err:#}");
    }

    #[test]
    fn rule_without_owner_or_kind_is_rejected() {
        let err = parse(
            r#"
            [[exclude]]
            reason = "suppress everything"
            "#,
        )
        .unwrap_err();
        assert!(
            err.to_string().contains("needs an owner, a kind, or both"),
            "{err}"
        );
    }

    #[test]
    fn member_without_owner_is_rejected() {
        let err = parse(
            r#"
            [[exclude]]
            member = "m"
            kind = "method_removed"
            reason = "bogus"
            "#,
        )
        .unwrap_err();
        assert!(
            err.to_string().contains("member requires an owner"),
            "{err}"
        );
    }

    #[test]
    fn unused_kind_rule_names_the_kind() {
        let rules = parse(
            r#"
            [[exclude]]
            kind = "field_became_final"
            reason = "not expected to occur"
            "#,
        )
        .unwrap();
        let mut violations = vec![kind_violation("lib/A", Reason::ClassRemoved)];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 0);
        assert_eq!(stats.unused.len(), 1);
        assert!(
            stats.unused[0].contains("kind \"field_became_final\""),
            "{}",
            stats.unused[0]
        );
    }

    /// The spaced form is what a report prints under `reason`, so pasting one in works.
    #[test]
    fn kind_accepts_both_snake_case_and_the_spaced_report_form() {
        for spelling in ["method_became_abstract", "method became abstract"] {
            let rules = parse(&format!(
                r#"
                [[exclude]]
                kind = "{spelling}"
                reason = "handled outside the gate"
                "#
            ))
            .unwrap();
            let mut violations = vec![
                kind_violation("lib/A", Reason::MethodBecameAbstract),
                kind_violation("lib/A", Reason::ClassRemoved),
            ];
            let stats = filter(&mut violations, &rules);
            assert_eq!(stats.suppressed, 1, "spelling {spelling}");
            assert_eq!(violations.len(), 1);
        }
        // A half-converted spelling still resolves to the same reason.
        assert_eq!(
            Reason::parse("method_became abstract"),
            Some(Reason::MethodBecameAbstract)
        );
    }

    /// A rule file written before a category was split by direction and member kind keeps
    /// waiving what it used to, so an upgrade of uika does not fail the build on a
    /// `kind` that no longer exists.
    #[test]
    fn a_kind_that_has_since_been_split_still_matches_its_parts() {
        let rules = parse(
            r#"
            [[exclude]]
            kind = "member_changed_from_static_to_instance"
            reason = "the call sites are all reflective"

            [[exclude]]
            kind = "class_kind_changed"
            reason = "the flip is intentional and handled"
            "#,
        )
        .unwrap();
        let mut violations = vec![
            kind_violation("lib/A", Reason::MethodBecameInstance),
            kind_violation("lib/B", Reason::FieldBecameInstance),
            kind_violation("lib/C", Reason::ClassBecameInterface),
            kind_violation("lib/D", Reason::InterfaceBecameClass),
            // The other direction was a different kind before the split too.
            kind_violation("lib/E", Reason::MethodBecameStatic),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 4);
        assert_eq!(violations.len(), 1);
        assert_eq!(violations[0].reason, Reason::MethodBecameStatic);
        assert!(stats.unused.is_empty());

        // Unused, it reports the kinds it stands for now rather than the spelling it was
        // written in. Each is quoted on its own, since narrowing the rule means writing one
        // of those values, and a joined list would not load.
        let mut nothing_to_match = vec![kind_violation("lib/A", Reason::ClassRemoved)];
        let stats = filter(&mut nothing_to_match, &rules);
        assert_eq!(stats.unused.len(), 2);
        assert!(
            stats.unused.iter().any(
                |u| u.contains("kind \"class_became_interface\" or \"interface_became_class\"")
            ),
            "{:?}",
            stats.unused
        );
    }

    #[test]
    fn empty_rule_list_is_a_no_op() {
        let mut violations = vec![class_violation("lib/C")];
        let stats = filter(&mut violations, &[]);
        assert_eq!(stats.suppressed, 0);
        assert!(stats.unused.is_empty());
        assert_eq!(violations.len(), 1);
    }
}
