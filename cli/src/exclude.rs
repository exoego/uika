//! Explicit suppression of known false positives (e.g. reflection-only member access).
//!
//! Reachability (reach.rs) only ever deprioritizes a violation, never drops it, because it
//! cannot see reflection driven by external configuration. An exclude list is the opposite: an
//! operator explicitly asserts "I know about this reference and it is not a real break", so
//! dropping it here is fine as long as the assertion stays visible -- a committed TOML file with
//! a required reason, a suppressed count in the report, and a warning for entries that matched
//! nothing (so the list does not silently rot as the checked libraries change).

use crate::model::Violation;
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize)]
struct ExcludeFile {
    #[serde(default)]
    exclude: Vec<RawEntry>,
}

#[derive(Deserialize)]
struct RawEntry {
    owner: String,
    member: Option<String>,
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
    owner: OwnerPattern,
    /// Member name only (descriptor-agnostic, so it covers every overload). None excludes the
    /// owner outright, matching class-level violations too (e.g. "class removed").
    member: Option<String>,
    reason: String,
}

impl ExcludeRule {
    fn describe(&self) -> String {
        match &self.member {
            Some(m) => format!("{}#{} ({})", self.owner, m, self.reason),
            None => format!("{} ({})", self.owner, self.reason),
        }
    }
}

/// Parse one exclude file's TOML content into rules.
fn parse(content: &str) -> Result<Vec<ExcludeRule>> {
    let file: ExcludeFile = toml::from_str(content).context("invalid TOML")?;
    file.exclude.into_iter().map(compile).collect()
}

fn compile(entry: RawEntry) -> Result<ExcludeRule> {
    if entry.reason.trim().is_empty() {
        bail!(
            "exclude rule for owner \"{}\" is missing a reason (reason must explain why the reference is a known false positive)",
            entry.owner
        );
    }
    let stars = entry.owner.matches('*').count();
    if stars > 1 || (stars == 1 && !entry.owner.ends_with('*')) {
        bail!(
            "exclude rule owner \"{}\": '*' is only supported once, as a trailing wildcard (prefix match)",
            entry.owner
        );
    }
    let owner = match entry.owner.strip_suffix('*') {
        Some(prefix) => OwnerPattern::Prefix(prefix.to_string()),
        None => OwnerPattern::Exact(entry.owner),
    };
    Ok(ExcludeRule {
        owner,
        member: entry.member,
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
        let mut matched = false;
        for (i, rule) in rules.iter().enumerate() {
            if !rule.owner.matches(owner) {
                continue;
            }
            let member_matches = match &rule.member {
                None => true,
                Some(name) => member_name == Some(name.as_str()),
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
    use crate::model::{MemberKey, RefKind, SymbolRef};

    fn class_violation(owner: &str, reason: &str) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern("app/Use"),
            reference: SymbolRef {
                kind: RefKind::Class,
                owner: intern(owner),
                member: None,
                expected_static: None,
                field_write: None,
            },
            reason: reason.to_string(),
            reachable: None,
            suggestion: None,
        }
    }

    fn member_violation(owner: &str, name: &str, descriptor: &str, reason: &str) -> Violation {
        Violation {
            source: intern("consumer.jar"),
            source_class: intern("app/Use"),
            reference: SymbolRef {
                kind: RefKind::Field,
                owner: intern(owner),
                member: Some(MemberKey::new(name, descriptor)),
                expected_static: None,
                field_write: None,
            },
            reason: reason.to_string(),
            reachable: None,
            suggestion: None,
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
                "field removed",
            ),
            member_violation(
                "org/apache/commons/logging/impl/LogFactoryImpl",
                "otherField",
                "I",
                "field removed",
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
            member_violation("lib/C", "m", "()V", "method removed"),
            member_violation("lib/C", "m", "(I)V", "method removed"),
        ];
        let stats = filter(&mut violations, &rules);
        assert_eq!(stats.suppressed, 2);
        assert!(violations.is_empty());
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
            class_violation("lib/Gone", "class removed"),
            member_violation("lib/Gone", "m", "()V", "method removed"),
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
            class_violation("org/apache/commons/logging/impl/LogFactoryImpl", "x"),
            class_violation("org/apache/commons/logging/LogFactory", "x"),
            class_violation("org/other/Unrelated", "x"),
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
        let mut violations = vec![class_violation("lib/Other", "class removed")];
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

    #[test]
    fn empty_rule_list_is_a_no_op() {
        let mut violations = vec![class_violation("lib/C", "class removed")];
        let stats = filter(&mut violations, &[]);
        assert_eq!(stats.suppressed, 0);
        assert!(stats.unused.is_empty());
        assert_eq!(violations.len(), 1);
    }
}
