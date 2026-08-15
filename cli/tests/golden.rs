//! Golden regression tests: the full check JSON for fixed fixture scenarios is
//! compared byte-for-byte against tests/golden/<scenario>.json, so any detection
//! shift (count, order, reason, field) fails here first. After verifying a diff
//! is an intended semantic change, re-bless with
//! `UIKA_BLESS=1 cargo test --test golden`.
//!
//! Scenario jar triples live in tests/scenarios.tsv, shared with
//! tools/jvm-probe/run-fixtures.sh so the golden and probe scenario lists cannot
//! drift. Inputs are loaded via crate-relative paths (see common::fixture) so
//! the `source` strings inside the JSON stay machine-independent.

mod common;

use common::fixture;
use std::path::Path;
use uika::check::check;
use uika::index::ApiIndex;
use uika::input::load;
use uika::report::check_json;

/// (old, new, consumer) jar names for a scenario in tests/scenarios.tsv.
fn scenario(name: &str) -> (String, String, String) {
    for line in include_str!("scenarios.tsv").lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split('\t').collect();
        assert!(fields.len() == 5, "malformed scenarios.tsv line: {line}");
        if fields[0] == name {
            return (
                fields[1].to_string(),
                fields[2].to_string(),
                fields[3].to_string(),
            );
        }
    }
    panic!("scenario not found in tests/scenarios.tsv: {name}");
}

/// Parse warnings are intentionally not part of the golden surface; guava
/// fixtures produce a few and the integration tests already pin the important
/// warning-free cases.
fn scenario_json(name: &str) -> String {
    let (old, new, target) = scenario(name);
    let (old_index, _) = ApiIndex::from_classes(&load(&fixture(&old)).unwrap());
    let new_classes = load(&fixture(&new)).unwrap();
    let (new_index, _) = ApiIndex::from_classes(&new_classes);
    // The new library's own bytecode is swept for invocation evidence, matching what the
    // CLI does with --new, so the goldens pin the same latent classification users see.
    let report = check(
        &load(&fixture(&target)).unwrap(),
        &old_index,
        &new_index,
        &new_classes,
    );
    check_json(&report).unwrap()
}

fn assert_golden(name: &str) {
    let actual = format!("{}\n", scenario_json(name));
    let path = Path::new("tests/golden").join(format!("{name}.json"));
    if std::env::var_os("UIKA_BLESS").is_some() {
        std::fs::write(&path, &actual).unwrap();
        return;
    }
    let expected = std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!(
            "golden file missing: {} ({e}); create it with UIKA_BLESS=1 cargo test --test golden",
            path.display()
        )
    });
    assert_eq!(
        expected, actual,
        "golden mismatch for {name}: detection output changed. Verify the diff is an \
         intended semantic change, then re-bless with UIKA_BLESS=1 cargo test --test golden"
    );
}

/// ktor-io binds EventLoopKt.processNextEventInCurrentThread ()J, removed in
/// coroutines 1.11.0 (NoSuchMethodError).
#[test]
fn golden_coroutines_ktor_io() {
    assert_golden("coroutines-ktor-io");
}

/// OTel moved DaemonThreadFactory out of sdk.internal between 1.42 and 1.60
/// (NoClassDefFoundError from the okhttp sender).
#[test]
fn golden_otel_sdk_common_sender_okhttp() {
    assert_golden("otel-sdk-common-sender-okhttp");
}

/// Guava 23.0-rc1 made the SimpleTimeLimiter constructor private while Selenium
/// 3.4.0 still calls it (IllegalAccessError).
#[test]
fn golden_guava_selenium() {
    assert_golden("guava-selenium");
}

/// koin-core 3.3.0 made Logger.log final while koin-logger-slf4j 3.2.2 overrides
/// it (IncompatibleClassChangeError).
#[test]
fn golden_koin_core_logger_slf4j() {
    assert_golden("koin-core-logger-slf4j");
}

/// OkHttp 4.0 turned RequestLine into a Kotlin object, making requestPath an
/// instance method under okhttp-digest 1.21 (IncompatibleClassChangeError).
#[test]
fn golden_okhttp_digest() {
    assert_golden("okhttp-digest");
}

/// pact junit5spring 4.2.3 subclasses a class junit5 4.2.3 opened but 4.2.2
/// still declares final; runtime lag at 4.2.2 breaks the subclass
/// (https://github.com/pact-foundation/pact-jvm/issues/1338, IncompatibleClassChangeError). old = compile-time binding.
#[test]
fn golden_pact_junit5_version_lag() {
    assert_golden("pact-junit5-version-lag");
}

/// jetty-util 10 made the Trie classes package-private and removed the Trie
/// interface while jetty-http 9.4 still references them (module version skew,
/// IllegalAccessError/NoClassDefFoundError).
#[test]
fn golden_jetty_util_http_skew() {
    assert_golden("jetty-util-http-skew");
}

/// Shape-2 AbstractMethodError, minimizing a real incident: jOOQ 3.17 added
/// `ExecuteListener.end`, which Spring Boot's `JooqExceptionTranslator` (compiled
/// against 3.16) never implements, so the first translated exception throws
/// AbstractMethodError (https://github.com/jOOQ/jOOQ/issues/14430). Here
/// EventListener 2.0 adds an abstract end() that BrokenTranslator never implements;
/// GoodTranslator declares end() and is the not-reported control. Minimized because
/// the real pair ships multi-megabyte jars; source and build command in
/// tests/fixtures/README.md.
#[test]
fn golden_synthetic_abstract_added() {
    assert_golden("synthetic-abstract-added");
}

/// Sealing break, minimizing a realistic-but-unpublished move: Shape 2.0 is
/// `sealed permits Circle`, so Square (compiled against the unsealed 1.0) no longer
/// loads. Marker implements the untouched Tagged and is the not-reported control.
/// No published pair seals a type consumers extend (mining 2026-08), but sealing
/// existing public types is real practice — the JDK sealed java.lang.constant, and
/// recompiling a Groovy enum under JDK 17 stamps PermittedSubclasses on it
/// (https://issues.apache.org/jira/browse/GROOVY-10194) — so this triple minimizes
/// that move landing on an existing implementor.
#[test]
fn golden_synthetic_sealed() {
    assert_golden("synthetic-sealed");
}

/// Default-method conflict, minimizing a break no library pair can exhibit alone:
/// lib/B 2.0 adds a default n() that lib/A already declares, so Conflicted
/// (implementing both, compiled against 1.0) can no longer select one. Overriding
/// declares its own n() and is the not-reported control. Adding a default is the
/// sanctioned evolution move and the collision exists only in a consumer implementing
/// both interfaces, so published-pair evidence is structurally impossible; the JVM
/// confirms both error forms in
/// `detects_a_default_method_conflict_from_a_newly_added_default`.
#[test]
fn golden_synthetic_default_conflict() {
    assert_golden("synthetic-default-conflict");
}
