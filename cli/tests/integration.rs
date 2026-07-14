//! Integration tests using real JARs in tests/fixtures/ (vendored from Maven Central;
//! see tests/fixtures/README.md). Two real incidents are used as ground truth.
//!
//! ground truth: BlockingAdapter in ktor-io 2.3.13 binds to
//! EventLoopKt.processNextEventInCurrentThread ()J from kotlinx-coroutines 1.7.1,
//! and that method disappeared in 1.11.0 (causing NoSuchMethodError).

use std::path::PathBuf;
use uika::check::check;
use uika::diff::diff;
use uika::index::ApiIndex;
use uika::input::load;
use uika::model::{BreakingChange, RefKind};

fn fixture(jar_name: &str) -> PathBuf {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(jar_name);
    assert!(path.exists(), "fixture not found: {}", path.display());
    path
}

#[test]
fn detects_ktor_io_break_against_coroutines_1_11() {
    let old_jar = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
    let new_jar = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
    let ktor_io = fixture("ktor-io-jvm-2.3.13.jar");

    let (old_index, warnings) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    assert!(warnings.is_empty(), "old jar parse warnings: {warnings:?}");
    let (new_index, warnings) = ApiIndex::from_classes(&load(&new_jar).unwrap());
    assert!(warnings.is_empty(), "new jar parse warnings: {warnings:?}");

    // diff: the original method removal is detected.
    let changes = diff(&old_index, &new_index);
    assert!(
        changes.iter().any(|c| matches!(
            c,
            BreakingChange::MethodRemoved { class, name, descriptor, .. }
                if class.as_str() == "kotlinx/coroutines/EventLoopKt"
                    && name.as_str() == "processNextEventInCurrentThread"
                    && descriptor.as_str() == "()J"
        )),
        "EventLoopKt.processNextEventInCurrentThread ()J removal is missing from diff"
    );

    // check: the reference from BlockingAdapter is detected as the only violation.
    let targets = load(&ktor_io).unwrap();
    let report = check(&targets, &old_index, &new_index);
    assert_eq!(
        report.violations.len(),
        1,
        "violations: {:?}",
        report.violations
    );
    let v = &report.violations[0];
    assert_eq!(
        v.source_class.as_str(),
        "io/ktor/utils/io/jvm/javaio/BlockingAdapter"
    );
    assert_eq!(v.reference.kind, RefKind::Method);
    assert_eq!(v.reference.owner.as_str(), "kotlinx/coroutines/EventLoopKt");
    assert_eq!(v.reason, "method removed");
}

/// ground truth 2: OTel 1.42 -> 1.60 moved DaemonThreadFactory from
/// io.opentelemetry.sdk.internal to io.opentelemetry.sdk.common.internal.
/// OkHttpUtil in the okhttp sender built against 1.42.1 references the old package,
/// causing NoClassDefFoundError (a real case where Sentry 8.43.2 lifted sdk-common).
#[test]
fn detects_otel_daemon_thread_factory_package_move() {
    let old_jar = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_jar = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let changes = diff(&old_index, &new_index);
    assert!(
        changes.iter().any(|c| matches!(
            c,
            BreakingChange::ClassRemoved { class }
                if class.as_str() == "io/opentelemetry/sdk/internal/DaemonThreadFactory"
        )),
        "DaemonThreadFactory removal is missing from diff"
    );

    let report = check(&load(&sender).unwrap(), &old_index, &new_index);
    assert_eq!(
        report.violations.len(),
        1,
        "violations: {:?}",
        report.violations
    );
    let v = &report.violations[0];
    // Matches the top of the real NoClassDefFoundError stack trace.
    assert_eq!(
        v.source_class.as_str(),
        "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"
    );
    assert_eq!(v.reference.kind, RefKind::Class);
    assert_eq!(
        v.reference.owner.as_str(),
        "io/opentelemetry/sdk/internal/DaemonThreadFactory"
    );
    assert_eq!(v.reason, "class removed");
}

/// SeleniumHQ/selenium#4381:
/// Selenium 3.4.0's UrlChecker calls Guava SimpleTimeLimiter's public
/// constructor. Guava 23.0-rc1 made that constructor private, producing
/// IllegalAccessError at runtime.
#[test]
fn detects_selenium_guava_constructor_access_narrowing() {
    let old_jar = fixture("guava-22.0.jar");
    let new_jar = fixture("guava-23.0-rc1.jar");
    let selenium = fixture("selenium-remote-driver-3.4.0.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let changes = diff(&old_index, &new_index);
    assert!(
        changes.iter().any(|c| matches!(
            c,
            BreakingChange::MethodAccessNarrowed { class, name, descriptor, .. }
                if class.as_str() == "com/google/common/util/concurrent/SimpleTimeLimiter"
                    && name.as_str() == "<init>"
                    && descriptor.as_str() == "(Ljava/util/concurrent/ExecutorService;)V"
        )),
        "SimpleTimeLimiter constructor access narrowing is missing from diff"
    );

    let report = check(&load(&selenium).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str() == "org/openqa/selenium/net/UrlChecker"
                && v.reference.owner.as_str()
                    == "com/google/common/util/concurrent/SimpleTimeLimiter"
                && v.reference.member.is_some_and(|m| {
                    m.name.as_str() == "<init>"
                        && m.descriptor.as_str() == "(Ljava/util/concurrent/ExecutorService;)V"
                })
                && v.reason == "method access narrowed"
        }),
        "violations: {:?}",
        report.violations
    );
}

/// InsertKoinIO/koin#1489:
/// koin-core 3.3.0 made Logger.log(Level, String) final while
/// koin-logger-slf4j 3.2.2 still overrides it, producing
/// IncompatibleClassChangeError.
#[test]
fn detects_koin_logger_final_method_override() {
    let old_jar = fixture("koin-core-jvm-3.2.2.jar");
    let new_jar = fixture("koin-core-jvm-3.3.0.jar");
    let logger = fixture("koin-logger-slf4j-3.2.2.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let changes = diff(&old_index, &new_index);
    assert!(
        changes.iter().any(|c| matches!(
            c,
            BreakingChange::MethodBecameFinal { class, name, descriptor }
                if class.as_str() == "org/koin/core/logger/Logger"
                    && name.as_str() == "log"
                    && descriptor.as_str()
                        == "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V"
        )),
        "Logger.log final addition is missing from diff"
    );

    let report = check(&load(&logger).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str() == "org/koin/logger/SLF4JLogger"
                && v.reference.owner.as_str() == "org/koin/core/logger/Logger"
                && v.reference.member.is_some_and(|m| {
                    m.name.as_str() == "log"
                        && m.descriptor.as_str()
                            == "(Lorg/koin/core/logger/Level;Ljava/lang/String;)V"
                })
                && v.reason == "method became final"
        }),
        "violations: {:?}",
        report.violations
    );
}

/// rburgst/okhttp-digest#57:
/// okhttp-digest 1.x calls RequestLine.requestPath as a static OkHttp 3 method.
/// OkHttp 4.0.x changed RequestLine into a Kotlin object, making requestPath an
/// instance method and producing IncompatibleClassChangeError.
#[test]
fn detects_okhttp_digest_static_to_instance_change() {
    let old_jar = fixture("okhttp-3.14.1.jar");
    let new_jar = fixture("okhttp-4.0.1.jar");
    let digest = fixture("okhttp-digest-1.21.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let changes = diff(&old_index, &new_index);
    assert!(
        changes.iter().any(|c| matches!(
            c,
            BreakingChange::MethodStaticChanged {
                class,
                name,
                descriptor,
                old_static: true,
                new_static: false,
            } if class.as_str() == "okhttp3/internal/http/RequestLine"
                && name.as_str() == "requestPath"
                && descriptor.as_str() == "(Lokhttp3/HttpUrl;)Ljava/lang/String;"
        )),
        "RequestLine.requestPath static-to-instance change is missing from diff"
    );

    let report = check(&load(&digest).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str() == "com/burgstaller/okhttp/digest/DigestAuthenticator"
                && v.reference.owner.as_str() == "okhttp3/internal/http/RequestLine"
                && v.reference.member.is_some_and(|m| {
                    m.name.as_str() == "requestPath"
                        && m.descriptor.as_str() == "(Lokhttp3/HttpUrl;)Ljava/lang/String;"
                })
                && v.reason == "member changed from static to instance"
        }),
        "violations: {:?}",
        report.violations
    );
}

/// Gradle integration: reproduce the OTel incident (only sdk-common lifted) from
/// before/after resolved classpath dumps.
#[test]
fn upgrade_check_reproduces_otel_incident_from_dumps() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_sc = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    let dir = std::env::temp_dir().join(format!("uika-upgrade-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let dump = |version: &str, file: &std::path::Path| {
        format!(
            r#"{{"modules":[{{"module":":app","classesDirs":[],"artifacts":[
                {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"{version}","file":"{}"}},
                {{"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"{}"}}
            ]}}]}}"#,
            file.display(),
            sender.display(),
        )
    };
    let before_path = dir.join("before.json");
    let after_path = dir.join("after.json");
    std::fs::write(&before_path, dump("1.42.1", &old_sc)).unwrap();
    std::fs::write(&after_path, dump("1.60.1", &new_sc)).unwrap();

    let before = uika::gradle::load_dump(&before_path).unwrap();
    let after = uika::gradle::load_dump(&after_path).unwrap();
    let changes = uika::gradle::diff_dumps(&before, &after);
    assert_eq!(changes.changes.len(), 1);
    assert_eq!(
        changes.changes[0].coordinate,
        "io.opentelemetry:opentelemetry-sdk-common"
    );
    assert_eq!(changes.old_jars, vec![old_sc]);
    assert_eq!(changes.new_jars, vec![new_sc]);

    let report = uika::run_check(
        &changes.old_jars,
        &changes.new_jars,
        &after.scan_targets,
        &after.app_roots,
    )
    .unwrap();
    assert_eq!(
        report.violations.len(),
        1,
        "violations: {:?}",
        report.violations
    );
    let v = &report.violations[0];
    assert_eq!(
        v.source_class.as_str(),
        "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"
    );
    assert_eq!(
        v.reference.owner.as_str(),
        "io/opentelemetry/sdk/internal/DaemonThreadFactory"
    );
    let _ = std::fs::remove_dir_all(&dir);
}

/// The ktor-io / coroutines break (see detects_ktor_io_break_against_coroutines_1_11):
/// the same violation is reachable when the referencing JAR is an application root, and not
/// proven reachable when the only root is an unrelated JAR that never references it. ktor-io
/// has no service providers, so BlockingAdapter is only reachable through an explicit root.
#[test]
fn reachability_tiers_violation_by_app_roots() {
    let old = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
    let new = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
    let ktor_io = fixture("ktor-io-jvm-2.3.13.jar");
    // Unrelated to ktor/coroutines: a real, scanned root that never reaches BlockingAdapter.
    let unrelated = fixture("koin-logger-slf4j-3.2.2.jar");

    let reachable = uika::run_check(
        std::slice::from_ref(&old),
        std::slice::from_ref(&new),
        std::slice::from_ref(&ktor_io),
        std::slice::from_ref(&ktor_io),
    )
    .unwrap();
    assert_eq!(reachable.violations.len(), 1);
    assert_eq!(
        reachable.violations[0].reachable,
        Some(true),
        "referencing JAR as an app root should make the violation reachable"
    );

    let targets = [ktor_io.clone(), unrelated.clone()];
    let unreachable = uika::run_check(
        std::slice::from_ref(&old),
        std::slice::from_ref(&new),
        &targets,
        std::slice::from_ref(&unrelated),
    )
    .unwrap();
    assert_eq!(unreachable.violations.len(), 1);
    assert_eq!(
        unreachable.violations[0].reachable,
        Some(false),
        "a root that never references BlockingAdapter should leave it not proven reachable"
    );
}

#[test]
fn unrelated_jar_reports_no_violations() {
    let old_jar = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
    let new_jar = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
    // A JAR that does not depend on coroutines produces no violations.
    let unrelated = fixture("opentelemetry-sdk-common-1.60.1.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());
    let report = check(&load(&unrelated).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.is_empty(),
        "violations: {:?}",
        report.violations
    );
}
