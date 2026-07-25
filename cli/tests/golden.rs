//! Golden regression tests: the full check JSON for fixed fixture scenarios is
//! compared byte-for-byte against tests/golden/<scenario>.json, so any detection
//! shift (count, order, reason, field) fails here first. After verifying a diff
//! is an intended semantic change, re-bless with
//! `UIKA_BLESS=1 cargo test --test golden`.
//!
//! Inputs are loaded via paths relative to the crate root (cargo runs tests
//! there) so the `source` strings inside the JSON stay machine-independent.

use std::path::{Path, PathBuf};
use uika::check::check;
use uika::index::ApiIndex;
use uika::input::load;
use uika::report::check_json;

fn fixture(jar_name: &str) -> PathBuf {
    let path = PathBuf::from("tests/fixtures").join(jar_name);
    assert!(
        path.exists(),
        "fixture not found: {} (tests must run from the cli/ crate root)",
        path.display()
    );
    path
}

/// Parse warnings are intentionally not part of the golden surface; guava
/// fixtures produce a few and the integration tests already pin the important
/// warning-free cases.
fn scenario_json(old: &str, new: &str, target: &str) -> String {
    let (old_index, _) = ApiIndex::from_classes(&load(&fixture(old)).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&fixture(new)).unwrap());
    let report = check(&load(&fixture(target)).unwrap(), &old_index, &new_index);
    check_json(&report).unwrap()
}

fn assert_golden(name: &str, actual: &str) {
    let path = Path::new("tests/golden").join(format!("{name}.json"));
    let actual = format!("{actual}\n");
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
    assert_golden(
        "coroutines-ktor-io",
        &scenario_json(
            "kotlinx-coroutines-core-jvm-1.7.1.jar",
            "kotlinx-coroutines-core-jvm-1.11.0.jar",
            "ktor-io-jvm-2.3.13.jar",
        ),
    );
}

/// OTel moved DaemonThreadFactory out of sdk.internal between 1.42 and 1.60
/// (NoClassDefFoundError from the okhttp sender).
#[test]
fn golden_otel_sdk_common_sender_okhttp() {
    assert_golden(
        "otel-sdk-common-sender-okhttp",
        &scenario_json(
            "opentelemetry-sdk-common-1.42.1.jar",
            "opentelemetry-sdk-common-1.60.1.jar",
            "opentelemetry-exporter-sender-okhttp-1.42.1.jar",
        ),
    );
}

/// Guava 23.0-rc1 made the SimpleTimeLimiter constructor private while Selenium
/// 3.4.0 still calls it (IllegalAccessError).
#[test]
fn golden_guava_selenium() {
    assert_golden(
        "guava-selenium",
        &scenario_json(
            "guava-22.0.jar",
            "guava-23.0-rc1.jar",
            "selenium-remote-driver-3.4.0.jar",
        ),
    );
}

/// koin-core 3.3.0 made Logger.log final while koin-logger-slf4j 3.2.2 overrides
/// it (IncompatibleClassChangeError).
#[test]
fn golden_koin_core_logger_slf4j() {
    assert_golden(
        "koin-core-logger-slf4j",
        &scenario_json(
            "koin-core-jvm-3.2.2.jar",
            "koin-core-jvm-3.3.0.jar",
            "koin-logger-slf4j-3.2.2.jar",
        ),
    );
}

/// OkHttp 4.0 turned RequestLine into a Kotlin object, making requestPath an
/// instance method under okhttp-digest 1.21 (IncompatibleClassChangeError).
#[test]
fn golden_okhttp_digest() {
    assert_golden(
        "okhttp-digest",
        &scenario_json(
            "okhttp-3.14.1.jar",
            "okhttp-4.0.1.jar",
            "okhttp-digest-1.21.jar",
        ),
    );
}
