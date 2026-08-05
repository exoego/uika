//! Integration tests using real JARs in tests/fixtures/ (vendored from Maven Central;
//! see tests/fixtures/README.md). Two real incidents are used as ground truth.
//!
//! ground truth: BlockingAdapter in ktor-io 2.3.13 binds to
//! EventLoopKt.processNextEventInCurrentThread ()J from kotlinx-coroutines 1.7.1,
//! and that method disappeared in 1.11.0 (causing NoSuchMethodError).

mod common;

use common::fixture;
use uika::check::check;
use uika::diff::diff;
use uika::index::ApiIndex;
use uika::input::load;
use uika::model::{BreakingChange, Reason, RefKind};

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
    assert_eq!(v.reason, Reason::MethodRemoved);
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
    assert_eq!(v.reason, Reason::ClassRemoved);
}

/// https://github.com/SeleniumHQ/selenium/issues/4381:
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
                && v.reason == Reason::MethodAccessNarrowed
        }),
        "violations: {:?}",
        report.violations
    );
}

/// https://github.com/InsertKoinIO/koin/issues/1489:
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
                && v.reason == Reason::MethodBecameFinal
        }),
        "violations: {:?}",
        report.violations
    );
}

/// https://github.com/rburgst/okhttp-digest/issues/57:
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
                && v.reason == Reason::StaticToInstance
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
        &[],
        None,
        None,
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

/// upgrade-check attributes the DaemonThreadFactory break to the two artifacts involved:
/// the referencing sender JAR and the sdk-common coordinate whose bump removed the class.
#[test]
fn upgrade_check_suggestion_attributes_the_break() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_sc = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    let dir = std::env::temp_dir().join(format!("uika-suggest-test-{}", std::process::id()));
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
    let mut report = uika::run_check(
        &changes.old_jars,
        &changes.new_jars,
        &after.scan_targets,
        &after.app_roots,
        &[],
        None,
        None,
    )
    .unwrap();
    uika::suggest::annotate(&mut report.violations, &before, &after, &changes.changes);

    let s = report.violations[0]
        .suggestion
        .as_ref()
        .expect("violation should carry a suggestion");
    assert_eq!(
        s.referenced_by.as_deref(),
        Some("io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1")
    );
    assert_eq!(s.removed_by, "io.opentelemetry:opentelemetry-sdk-common");
    assert_eq!(s.before, "1.42.1");
    assert_eq!(s.after, "1.60.1");
    // Same group -> advice leads with alignment.
    assert!(
        s.advice.starts_with("align all io.opentelemetry artifacts"),
        "advice: {}",
        s.advice
    );
    let _ = std::fs::remove_dir_all(&dir);
}

/// A coordinate rename (publishing the same library under a dev coordinate): the old
/// coordinate is REMOVED with no new-side pair, and the identical JAR re-enters as a
/// plain scan target. Its nest-internal private references (Java 11+ nestmates, e.g.
/// anonymous enum bodies calling the private enum constructor) resolve as private
/// against both sides — pre-existing, not access narrowing. Before the old-relative
/// gate this reported 13 false "access narrowed" violations from caffeine alone.
#[test]
fn coordinate_rename_of_identical_jar_reports_nothing() {
    let caffeine = fixture("caffeine-3.2.3.jar");

    let dir = std::env::temp_dir().join(format!("uika-rename-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let copy = dir.join("caffeine-dev-abc123.jar");
    std::fs::copy(&caffeine, &copy).unwrap();
    let dump = |name: &str, version: &str, file: &std::path::Path| {
        format!(
            r#"{{"modules":[{{"module":":app","classesDirs":[],"artifacts":[
                {{"group":"com.github.ben-manes.caffeine","name":"{name}","version":"{version}","file":"{}"}}
            ]}}]}}"#,
            file.display(),
        )
    };
    let before_path = dir.join("before.json");
    let after_path = dir.join("after.json");
    std::fs::write(&before_path, dump("caffeine", "3.2.3", &caffeine)).unwrap();
    std::fs::write(&after_path, dump("caffeine-dev", "abc123", &copy)).unwrap();

    let before = uika::gradle::load_dump(&before_path).unwrap();
    let after = uika::gradle::load_dump(&after_path).unwrap();
    let changes = uika::gradle::diff_dumps(&before, &after);
    assert_eq!(changes.old_jars, vec![caffeine]);
    assert!(changes.new_jars.is_empty());

    let report = uika::run_check(
        &changes.old_jars,
        &changes.new_jars,
        &after.scan_targets,
        &after.app_roots,
        &[],
        None,
        None,
    )
    .unwrap();
    assert!(
        report.violations.is_empty(),
        "violations: {:?}",
        report.violations
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
        &[],
        None,
        None,
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
        &[],
        None,
        None,
    )
    .unwrap();
    assert_eq!(unreachable.violations.len(), 1);
    assert_eq!(
        unreachable.violations[0].reachable,
        Some(false),
        "a root that never references BlockingAdapter should leave it not proven reachable"
    );
}

/// https://github.com/pact-foundation/pact-jvm/issues/1338:
/// junit5spring 4.2.3 subclasses PactVerificationExtension, which junit5 4.2.3
/// opened up but 4.2.2 still declares final (Kotlin classes start final). When
/// the runtime classpath lags at junit5 4.2.2 the subclass cannot load
/// (IncompatibleClassChangeError). old = the compile-time binding (4.2.3),
/// new = the lagging runtime resolution (4.2.2).
#[test]
fn detects_pact_class_became_final_under_version_lag() {
    let old_jar = fixture("junit5-4.2.3.jar");
    let new_jar = fixture("junit5-4.2.2.jar");
    let spring = fixture("junit5spring-4.2.3.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let changes = diff(&old_index, &new_index);
    assert!(
        changes.iter().any(|c| matches!(
            c,
            BreakingChange::ClassBecameFinal { class }
                if class.as_str() == "au/com/dius/pact/provider/junit5/PactVerificationExtension"
        )),
        "PactVerificationExtension final change is missing from diff"
    );

    let report = check(&load(&spring).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str()
                == "au/com/dius/pact/provider/spring/junit5/PactVerificationSpringExtension"
                && v.reference.owner.as_str()
                    == "au/com/dius/pact/provider/junit5/PactVerificationExtension"
                && v.reason == Reason::ClassBecameFinal
        }),
        "violations: {:?}",
        report.violations
    );
}

/// Jetty module version skew: jetty-util 10 made ArrayTrie/ArrayTernaryTrie
/// package-private (and removed the Trie interface) while jetty-http 9.4 still
/// references them, producing IllegalAccessError/NoClassDefFoundError when the
/// modules mix on one classpath.
#[test]
fn detects_jetty_util_class_access_narrowing() {
    let old_jar = fixture("jetty-util-9.3.26.v20190403.jar");
    let new_jar = fixture("jetty-util-10.0.26.jar");
    let http = fixture("jetty-http-9.4.49.v20220914.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let report = check(&load(&http).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str() == "org/eclipse/jetty/http/MimeTypes"
                && v.reference.owner.as_str() == "org/eclipse/jetty/util/ArrayTrie"
                && v.reason == Reason::ClassAccessNarrowed
        }),
        "violations: {:?}",
        report.violations
    );
    assert!(
        report.violations.iter().any(|v| {
            v.reference.owner.as_str() == "org/eclipse/jetty/util/Trie"
                && v.reason == Reason::ClassRemoved
        }),
        "violations: {:?}",
        report.violations
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

/// Two versions of the same library on one classpath: duplicate class names are
/// first-wins, and the JVM never loads the shadowed copies. sisu 0.3.4's
/// SpaceScanner$1 extends its shaded asm ClassVisitor, while sisu 1.0.0's extends
/// the real org.objectweb.asm.ClassVisitor and calls its protected super(int)
/// (public in asm 8, protected in asm 9). With 0.3.4 winning, judging the shadowed
/// 1.0.0 copy's super() call against the winner's non-subclass hierarchy reported
/// a false "method access narrowed"; neither classpath order breaks at runtime.
#[test]
fn refs_from_shadowed_duplicate_jar_copies_are_not_reported() {
    let old_jar = fixture("asm-8.0.1.jar");
    let new_jar = fixture("asm-9.10.1.jar");
    let sisu_034 = fixture("org.eclipse.sisu.inject-0.3.4.jar");
    let sisu_100 = fixture("org.eclipse.sisu.inject-1.0.0.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    for order in [[&sisu_034, &sisu_100], [&sisu_100, &sisu_034]] {
        let mut targets = load(order[0]).unwrap();
        targets.extend(load(order[1]).unwrap());
        let report = check(&targets, &old_index, &new_index);
        assert!(
            report.violations.is_empty(),
            "order {:?}: violations: {:?}",
            order.map(|p| p.file_name().unwrap()),
            report.violations
        );
    }
}

/// Version lag in the upgrade direction (https://github.com/pact-foundation/pact-jvm/issues/1338): upgrading
/// junit5spring 4.2.2 -> 4.2.3 while junit5 stays at 4.2.2 introduces
/// PactVerificationSpringExtension, a new subclass of PactVerificationExtension,
/// which the lagging junit5 still declares final (opened only in 4.2.3). The final
/// class lives in an artifact the upgrade did not change, so the old/new pair diff
/// cannot see it; the upgraded artifact's own new classes must be checked against
/// the resolved classpath.
#[test]
fn detects_upgraded_artifact_subclassing_final_class_of_lagging_sibling() {
    let old_spring = fixture("junit5spring-4.2.2.jar");
    let new_spring = fixture("junit5spring-4.2.3.jar");
    let lagging_junit5 = fixture("junit5-4.2.2.jar");

    let report = uika::run_check(
        &[old_spring],
        std::slice::from_ref(&new_spring),
        &[new_spring.clone(), lagging_junit5],
        &[],
        &[],
        None,
        None,
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
        "au/com/dius/pact/provider/spring/junit5/PactVerificationSpringExtension"
    );
    assert_eq!(
        v.reference.owner.as_str(),
        "au/com/dius/pact/provider/junit5/PactVerificationExtension"
    );
    assert_eq!(v.reason, Reason::ExtendsFinalClass);
}

/// The old-relative gate for the version-lag check: when the changed artifact's old
/// version already had the same super edge, the breakage predates the upgrade and
/// must not be reported (same stance as every other pre-existing inconsistency).
#[test]
fn preexisting_final_super_edge_is_not_reported_on_upgrade() {
    let spring = fixture("junit5spring-4.2.3.jar");
    let lagging_junit5 = fixture("junit5-4.2.2.jar");

    let dir = std::env::temp_dir().join(format!("uika-lag-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let old_copy = dir.join("junit5spring-old.jar");
    std::fs::copy(&spring, &old_copy).unwrap();

    let report = uika::run_check(
        &[old_copy],
        std::slice::from_ref(&spring),
        &[spring.clone(), lagging_junit5],
        &[],
        &[],
        None,
        None,
    )
    .unwrap();
    assert!(
        report.violations.is_empty(),
        "violations: {:?}",
        report.violations
    );
    let _ = std::fs::remove_dir_all(&dir);
}

/// Locate ct.sym for the JDK-layer test: the same environment lookup the CLI uses,
/// then the mise-pinned JDK as a fallback (CI and this repo's dev setup).
fn find_ct_sym_for_test() -> Option<std::path::PathBuf> {
    if let Some(p) = uika::jdk::find_ct_sym() {
        return Some(p);
    }
    let out = std::process::Command::new("mise")
        .args([
            "exec",
            "--",
            "java",
            "-XshowSettings:properties",
            "-version",
        ])
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stderr);
    let home = text
        .lines()
        .find_map(|l| l.trim().strip_prefix("java.home = "))?;
    uika::jdk::ct_sym_in(home.trim())
}

/// The opt-in JDK API layer (--jdk-release): guava's collections extend java.util
/// types, so the selenium scenario leaves hierarchy-escape references unverified.
/// With the layer, every escape concludes and the broken verdicts stay identical:
/// no detection is lost and no new violation appears from ct.sym data.
#[test]
fn jdk_layer_resolves_hierarchy_escapes_without_changing_verdicts() {
    let Some(ct_sym) = find_ct_sym_for_test() else {
        eprintln!("skipping: no JDK with ct.sym found (JAVA_HOME/UIKA_JDK/mise)");
        return;
    };
    let old_jar = fixture("guava-22.0.jar");
    let new_jar = fixture("guava-23.0-rc1.jar");
    let selenium = fixture("selenium-remote-driver-3.4.0.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());
    let scan = || {
        uika::check::scan_target_paths(std::slice::from_ref(&selenium), &old_index, false).unwrap()
    };

    let baseline = uika::check::check_scanned(
        scan(),
        &old_index,
        &new_index,
        &Default::default(),
        None,
        None,
        None,
    );
    assert!(baseline.unknown_refs > 0, "expected hierarchy escapes");

    // The found JDK may be older than 18 (its own release is not in its
    // ct.sym), so walk down a ladder instead of panicking; the guava escapes
    // are java.util/java.lang types present since release 8, so the
    // assertions hold on every rung (verified for 8, 11, and 17).
    let Some(mut indexer) = [17, 11, 8]
        .iter()
        .find_map(|r| uika::jdk::JdkIndexer::open(&ct_sym, *r).ok())
    else {
        eprintln!("skipping: no usable release in {}", ct_sym.display());
        return;
    };
    let with_jdk = uika::check::check_scanned(
        scan(),
        &old_index,
        &new_index,
        &Default::default(),
        Some(&mut indexer),
        None,
        None,
    );
    assert_eq!(with_jdk.unknown_refs, 0, "all escapes should conclude");
    fn key(v: &uika::model::Violation) -> (&str, &str, &str, &str, &str) {
        (
            v.source_class.as_str(),
            v.reference.owner.as_str(),
            v.reference.member.map_or("", |m| m.name.as_str()),
            v.reference.member.map_or("", |m| m.descriptor.as_str()),
            v.reason.as_str(),
        )
    }
    let mut a: Vec<_> = baseline.violations.iter().map(key).collect();
    let mut b: Vec<_> = with_jdk.violations.iter().map(key).collect();
    a.sort();
    b.sort();
    assert_eq!(a, b, "verdicts must not change, only Unknowns conclude");
}

/// When the sibling is upgraded in lockstep (junit5 4.2.3 opened the class), the
/// same junit5spring upgrade reports nothing.
#[test]
fn lockstep_sibling_upgrade_reports_nothing() {
    let old_spring = fixture("junit5spring-4.2.2.jar");
    let new_spring = fixture("junit5spring-4.2.3.jar");
    let old_junit5 = fixture("junit5-4.2.2.jar");
    let new_junit5 = fixture("junit5-4.2.3.jar");

    let report = uika::run_check(
        &[old_spring, old_junit5],
        &[new_spring.clone(), new_junit5.clone()],
        &[new_spring, new_junit5],
        &[],
        &[],
        None,
        None,
    )
    .unwrap();
    assert!(
        report.violations.is_empty(),
        "violations: {:?}",
        report.violations
    );
}

/// ktor module version skew (the same class family as the coroutines/ktor fixture):
/// io.ktor.utils.io.ByteChannel was an interface in ktor-io 2.3.13 and became a
/// final class in 3.1.0. ktor-network 2.3.13 calls it through an InterfaceMethodref
/// (invokeinterface), so mixing ktor-io 3.1.0 under ktor-network 2.3.13 makes method
/// resolution throw IncompatibleClassChangeError. This is the class<->interface flip
/// branch of the B1 checks, not visible to the constant-pool removal checks.
#[test]
fn detects_ktor_interface_became_class_under_module_skew() {
    let old_jar = fixture("ktor-io-jvm-2.3.13.jar");
    let new_jar = fixture("ktor-io-jvm-3.1.0.jar");
    let network = fixture("ktor-network-jvm-2.3.13.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    let report = check(&load(&network).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str()
                == "io/ktor/network/sockets/CIOReaderKt$attachForReadingDirectImpl$1"
                && v.reference.owner.as_str() == "io/ktor/utils/io/ByteChannel"
                && v.reason == Reason::ClassKindChanged
        }),
        "expected a class-kind-changed break on ByteChannel: {:?}",
        report
            .violations
            .iter()
            .filter(|v| v.reason == Reason::ClassKindChanged)
            .map(|v| (v.source_class.as_str(), v.reference.owner.as_str()))
            .collect::<Vec<_>>()
    );
}

/// InstantiationError from a `new` on a class that became abstract. jackson-module-kotlin
/// 2.20.1 made ValueClassBoxConverter abstract; the module's own ReflectionCache (and two
/// other classes) instantiate it directly with `new`. Bytecode compiled against 2.18.2,
/// where the class was a concrete final class, throws InstantiationError once 2.20.1 is on
/// the classpath. Only the `new` breaks; a plain type reference to the class stays valid.
#[test]
fn detects_new_on_class_that_became_abstract() {
    let old_jar = fixture("jackson-module-kotlin-2.18.2.jar");
    let new_jar = fixture("jackson-module-kotlin-2.20.1.jar");

    let (old_index, _) = ApiIndex::from_classes(&load(&old_jar).unwrap());
    let (new_index, _) = ApiIndex::from_classes(&load(&new_jar).unwrap());

    // The old jar's own classes are the consumer: they carry the `new` sites.
    let report = check(&load(&old_jar).unwrap(), &old_index, &new_index);
    assert!(
        report.violations.iter().any(|v| {
            v.source_class.as_str() == "com/fasterxml/jackson/module/kotlin/ReflectionCache"
                && v.reference.owner.as_str()
                    == "com/fasterxml/jackson/module/kotlin/ValueClassBoxConverter"
                && v.reference.instantiated == Some(true)
                && v.reason == Reason::ClassBecameAbstract
        }),
        "expected an InstantiationError break on ValueClassBoxConverter: {:?}",
        report
            .violations
            .iter()
            .filter(|v| v.reason == Reason::ClassBecameAbstract)
            .map(|v| (v.source_class.as_str(), v.reference.owner.as_str()))
            .collect::<Vec<_>>()
    );
}

/// Runs the uika binary for end-to-end upgrade-check coverage (flags, JSON shape, exit code).
fn run_uika(args: &[&str]) -> (i32, String, String) {
    let out = std::process::Command::new(env!("CARGO_BIN_EXE_uika"))
        .args(args)
        .output()
        .expect("run uika binary");
    (
        out.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&out.stdout).into_owned(),
        String::from_utf8_lossy(&out.stderr).into_owned(),
    )
}
/// Writes the before/after dump JSON into a fresh scratch dir, runs `uika upgrade-check`
/// with any extra args, cleans up, and returns (exit code, stdout, stderr). The six
/// per-module e2e tests differ only in their dump JSON and assertions.
fn run_upgrade_check_with_dumps(
    tag: &str,
    before: &str,
    after: &str,
    extra: &[&str],
) -> (i32, String, String) {
    let dir = std::env::temp_dir().join(format!("uika-{tag}-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let before_path = dir.join("before.json");
    let after_path = dir.join("after.json");
    std::fs::write(&before_path, before).unwrap();
    std::fs::write(&after_path, after).unwrap();
    let mut args = vec![
        "upgrade-check".to_string(),
        "--before".to_string(),
        before_path.display().to_string(),
        "--after".to_string(),
        after_path.display().to_string(),
    ];
    args.extend(extra.iter().map(|s| s.to_string()));
    let arg_refs: Vec<&str> = args.iter().map(String::as_str).collect();
    let result = run_uika(&arg_refs);
    let _ = std::fs::remove_dir_all(&dir);
    result
}

/// Per-module upgrade-check judges each module against its own resolution. Two properties on
/// one fixture layout (the netty shape from a real multi-module monorepo incident):
///
/// - A module pinned to the old version is skipped, so its jar's classes are never judged
///   against the sibling module's newer version (the cross-version false-positive class).
/// - The merged universe still resolves the old version somewhere (the pinned module), so the
///   flat diff has no old jars and reports NOTHING for the real break in the upgrading module
///   (a false negative per-module mode fixes).
#[test]
fn per_module_upgrade_check_gates_on_each_modules_own_resolution() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_sc = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");
    let dump = |app_sc: &std::path::Path, app_sc_version: &str| {
        format!(
            r#"{{"modules":[
                {{"module":":app","classesDirs":[],"artifacts":[
                    {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"{app_sc_version}","file":"{}"}},
                    {{"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"{}"}}
                ]}},
                {{"module":":pinned","classesDirs":[],"artifacts":[
                    {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"{}"}},
                    {{"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"{}"}}
                ]}}
            ]}}"#,
            app_sc.display(),
            sender.display(),
            old_sc.display(),
            sender.display(),
        )
    };
    let before = dump(&old_sc, "1.42.1");
    let after = dump(&new_sc, "1.60.1");

    // Per-module (default): :app's upgrade is caught and attributed; :pinned is skipped.
    let (code, stdout, stderr) =
        run_upgrade_check_with_dumps("permod-e2e", &before, &after, &["--json"]);
    assert_eq!(code, 1, "stdout:\n{stdout}\nstderr:\n{stderr}");
    let json: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    let violations = json["violations"].as_array().unwrap();
    assert!(
        violations.iter().any(|v| {
            v["source_class"] == "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"
                && v["modules"] == serde_json::json!([":app"])
        }),
        "expected the :app-attributed DaemonThreadFactory break:\n{stdout}"
    );
    assert!(
        violations.iter().all(|v| !v["modules"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!(":pinned"))),
        "the pinned module must never be judged against the sibling's upgrade:\n{stdout}"
    );
    let runs = json["module_runs"]["outcomes"].as_array().unwrap();
    assert_eq!(runs.len(), 1, "{stdout}");
    assert_eq!(runs[0]["modules"], serde_json::json!([":app"]));
    assert_eq!(json["module_runs"]["unchanged_modules"], 1, "{stdout}");

    // Text mode carries the same attribution for humans.
    let (code, stdout, _) = run_upgrade_check_with_dumps("permod-e2e", &before, &after, &[]);
    assert_eq!(code, 1);
    assert!(
        stdout.contains("per-module check: 1 of 2 modules"),
        "{stdout}"
    );
    assert!(stdout.contains("affected modules: :app"), "{stdout}");

    // --merged keeps the flat behavior: 1.42.1 is still resolved by :pinned, so the flat
    // diff has no removed version and the real break in :app goes unreported.
    let (code, stdout, _) =
        run_upgrade_check_with_dumps("permod-e2e", &before, &after, &["--merged", "--json"]);
    assert_eq!(code, 0, "{stdout}");
    let json: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    assert!(json["violations"].is_null(), "{stdout}");
}

/// A project-dependency artifact that was never built (jar path missing) falls back to the
/// producing module's classesDirs from the same dump, so the reference is still checked
/// instead of being silently skipped.
#[test]
fn per_module_project_dependency_falls_back_to_classes_dirs() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_sc = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    // :app depends on project :sender-lib whose jar is unbuilt; :sender-lib's classesDirs
    // stand in for its output (a jar path works: scan targets may be jars or dirs).
    let dump = |sc: &std::path::Path, version: &str| {
        format!(
            r#"{{"modules":[
                {{"module":":app","classesDirs":[],"artifacts":[
                    {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"{version}","file":"{}"}},
                    {{"file":"/nonexistent/uika-test/sender-lib.jar","project":":sender-lib"}}
                ]}},
                {{"module":":sender-lib","classesDirs":["{}"],"artifacts":[]}}
            ]}}"#,
            sc.display(),
            sender.display(),
        )
    };

    let (code, stdout, stderr) = run_upgrade_check_with_dumps(
        "permod-fallback",
        &dump(&old_sc, "1.42.1"),
        &dump(&new_sc, "1.60.1"),
        &["--json"],
    );
    assert_eq!(code, 1, "stdout:\n{stdout}\nstderr:\n{stderr}");
    let json: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    assert!(
        json["violations"].as_array().unwrap().iter().any(|v| {
            v["source_class"] == "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"
                && v["modules"] == serde_json::json!([":app"])
        }),
        "the fallback classesDirs must be scanned in :app's run:\n{stdout}"
    );
    assert!(
        stderr.contains("is not built; scanning module :sender-lib's classesDirs"),
        "stderr:\n{stderr}"
    );
}

/// The stay-vs-upgrade shape: module :pinned stays on a THIRD version of the coordinate
/// (not the upgrading module's old version), module :upgrader moves old -> new across a
/// breaking change. The pinned module's classpath must never be judged against the
/// upgrading module's new version, and the break must be attributed to :upgrader alone.
#[test]
fn per_module_check_detects_upgrade_beside_module_pinned_to_third_version() {
    let coroutines_old = fixture("kotlinx-coroutines-core-jvm-1.7.1.jar");
    let coroutines_new = fixture("kotlinx-coroutines-core-jvm-1.11.0.jar");
    let ktor_io = fixture("ktor-io-jvm-2.3.13.jar");

    // :pinned resolves a distinct third version (the version string is what the diff sees;
    // the 1.7.1 jar stands in for its bytes). :upgrader moves 1.7.1 -> 1.11.0, which
    // removed EventLoopKt.processNextEventInCurrentThread that ktor-io references.
    let dump = |upgrader_version: &str, upgrader_jar: &std::path::Path| {
        format!(
            r#"{{"modules":[
                {{"module":":pinned","classesDirs":[],"artifacts":[
                    {{"group":"org.jetbrains.kotlinx","name":"kotlinx-coroutines-core-jvm","version":"1.5.0","file":"{}"}},
                    {{"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","file":"{}"}}
                ]}},
                {{"module":":upgrader","classesDirs":[],"artifacts":[
                    {{"group":"org.jetbrains.kotlinx","name":"kotlinx-coroutines-core-jvm","version":"{upgrader_version}","file":"{}"}},
                    {{"group":"io.ktor","name":"ktor-io-jvm","version":"2.3.13","file":"{}"}}
                ]}}
            ]}}"#,
            coroutines_old.display(),
            ktor_io.display(),
            upgrader_jar.display(),
            ktor_io.display(),
        )
    };

    let (code, stdout, stderr) = run_upgrade_check_with_dumps(
        "thirdver-e2e",
        &dump("1.7.1", &coroutines_old),
        &dump("1.11.0", &coroutines_new),
        &["--json"],
    );
    assert_eq!(code, 1, "stdout:\n{stdout}\nstderr:\n{stderr}");
    let json: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    let violations = json["violations"].as_array().unwrap();
    assert!(
        violations.iter().any(|v| {
            v["source_class"] == "io/ktor/utils/io/jvm/javaio/BlockingAdapter"
                && v["modules"] == serde_json::json!([":upgrader"])
        }),
        "expected the BlockingAdapter break attributed to :upgrader only:\n{stdout}"
    );
    assert!(
        violations.iter().all(|v| !v["modules"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!(":pinned"))),
        "the third-version pinned module must never be judged against 1.11.0:\n{stdout}"
    );
    assert_eq!(json["module_runs"]["unchanged_modules"], 1, "{stdout}");
    let runs = json["module_runs"]["outcomes"].as_array().unwrap();
    assert_eq!(runs.len(), 1, "{stdout}");
    assert_eq!(runs[0]["modules"], serde_json::json!([":upgrader"]));
}

/// A version swap between modules leaves the universe-wide change list empty, but the
/// per-module diffs still find the break; the text report must show it (an empty global
/// header must not swallow the violations) and the per-run suggestion must carry the
/// module's own before -> after versions.
#[test]
fn per_module_check_reports_break_when_global_version_set_is_unchanged() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_sc = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    let dump = |a_version: &str,
                a_jar: &std::path::Path,
                b_version: &str,
                b_jar: &std::path::Path| {
        format!(
            r#"{{"modules":[
                {{"module":":a","classesDirs":[],"artifacts":[
                    {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"{a_version}","file":"{}"}},
                    {{"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"{}"}}
                ]}},
                {{"module":":b","classesDirs":[],"artifacts":[
                    {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"{b_version}","file":"{}"}}
                ]}}
            ]}}"#,
            a_jar.display(),
            sender.display(),
            b_jar.display(),
        )
    };

    // :a upgrades 1.42.1 -> 1.60.1 (break), :b downgrades 1.60.1 -> 1.42.1: the union
    // version set {1.42.1, 1.60.1} is identical on both sides.
    let (code, stdout, stderr) = run_upgrade_check_with_dumps(
        "swap-e2e",
        &dump("1.42.1", &old_sc, "1.60.1", &new_sc),
        &dump("1.60.1", &new_sc, "1.42.1", &old_sc),
        &[],
    );
    assert_eq!(code, 1, "stdout:\n{stdout}\nstderr:\n{stderr}");
    assert!(stdout.contains("dependency changes: none"), "{stdout}");
    // Both sides of the swap changed their own resolution (a downgrade is a change too).
    assert!(
        stdout.contains("per-module check: 2 of 2 modules"),
        "{stdout}"
    );
    assert!(
        stdout.contains("io.opentelemetry.sdk.internal.DaemonThreadFactory"),
        "the swap-hidden break must still be reported:\n{stdout}"
    );
    // The per-run suggestion quotes :a's own move, which the empty global list cannot.
    assert!(
        stdout.contains("why: io.opentelemetry:opentelemetry-sdk-common changed 1.42.1 -> 1.60.1"),
        "{stdout}"
    );
}

/// A module renamed while upgrading (no same-name before module) is checked against the
/// union's before versions instead of being silently skipped.
#[test]
fn per_module_check_covers_renamed_module_via_union_fallback() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let new_sc = fixture("opentelemetry-sdk-common-1.60.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    let dump = |name: &str, version: &str, jar: &std::path::Path| {
        format!(
            r#"{{"modules":[
                {{"module":"{name}","classesDirs":[],"artifacts":[
                    {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"{version}","file":"{}"}},
                    {{"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"{}"}}
                ]}}
            ]}}"#,
            jar.display(),
            sender.display(),
        )
    };

    let (code, stdout, stderr) = run_upgrade_check_with_dumps(
        "rename-e2e",
        &dump(":server", "1.42.1", &old_sc),
        &dump(":backend", "1.60.1", &new_sc),
        &["--json"],
    );
    assert_eq!(code, 1, "stdout:\n{stdout}\nstderr:\n{stderr}");
    assert!(
        stderr.contains("module :backend is not in the before dump"),
        "stderr:\n{stderr}"
    );
    let json: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    assert!(
        json["violations"].as_array().unwrap().iter().any(|v| {
            v["source_class"] == "io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil"
                && v["modules"] == serde_json::json!([":backend"])
        }),
        "the renamed module's upgrade must still be checked:\n{stdout}"
    );
}

/// An after-side module whose artifact list vanished (partial build, failed resolution) is
/// skipped as incomplete instead of being diffed as "every dependency removed".
#[test]
fn per_module_check_skips_module_with_vanished_artifacts() {
    let old_sc = fixture("opentelemetry-sdk-common-1.42.1.jar");
    let sender = fixture("opentelemetry-exporter-sender-okhttp-1.42.1.jar");

    let before = format!(
        r#"{{"modules":[
            {{"module":":a","classesDirs":[],"artifacts":[
                {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"{}"}},
                {{"group":"io.opentelemetry","name":"opentelemetry-exporter-sender-okhttp","version":"1.42.1","file":"{}"}}
            ]}}
        ]}}"#,
        old_sc.display(),
        sender.display(),
    );
    // :a still exists but resolved nothing; a second module keeps per-module mode on.
    let after = format!(
        r#"{{"modules":[
            {{"module":":a","classesDirs":[],"artifacts":[]}},
            {{"module":":b","classesDirs":[],"artifacts":[
                {{"group":"io.opentelemetry","name":"opentelemetry-sdk-common","version":"1.42.1","file":"{}"}}
            ]}}
        ]}}"#,
        old_sc.display(),
    );

    let (code, stdout, stderr) =
        run_upgrade_check_with_dumps("incomplete-e2e", &before, &after, &["--json"]);
    assert_eq!(code, 0, "stdout:\n{stdout}\nstderr:\n{stderr}");
    assert!(
        stderr.contains("module :a lists no resolved artifacts in the after dump"),
        "stderr:\n{stderr}"
    );
    let json: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    assert_eq!(json["module_runs"]["incomplete_modules"], 1, "{stdout}");
    assert!(json["violations"].is_null(), "{stdout}");
}
