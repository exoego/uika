#!/bin/sh
# Answer-check the golden fixture scenarios against a real JVM: run uika check
# with --verdicts-json, then let tools/jvm-probe/Probe.java resolve every
# reference with MethodHandles.Lookup. A verdict uika calls broken that the JVM
# links fine is a false positive and fails the run (--fail-on-fp); ok/unknown
# verdicts that fail on the new side but link on the old side are listed as
# false-negative candidates for triage.
#
# Scenario list mirrors cli/tests/golden.rs; keep them in sync. kotlin-stdlib is
# probe support for the Kotlin-built fixtures (see cli/tests/fixtures/README.md).
set -eu

UIKA=${UIKA:-target/release/uika}
JAVA=${JAVA:-java}
FIX=cli/tests/fixtures
OUT=${OUT:-target/probe}
KOTLIN=$FIX/kotlin-stdlib-2.2.20.jar
mkdir -p "$OUT"

# run <name> <old-jar> <new-jar> <consumer-jar> [shared-classpath-suffix]
run() {
    name=$1; old=$2; new=$3; consumer=$4; shared=${5:-}
    echo "== $name"
    "$UIKA" check --old "$FIX/$old" --new "$FIX/$new" --classpath "$FIX/$consumer" \
        --verdicts-json "$OUT/$name.jsonl" --fail-on never >/dev/null
    # $JAVA unquoted on purpose: it may carry a launcher prefix ("mise exec -- java").
    $JAVA tools/jvm-probe/Probe.java --verdicts "$OUT/$name.jsonl" \
        --classpath "$FIX/$new:$FIX/$consumer$shared" \
        --old-classpath "$FIX/$old:$FIX/$consumer$shared" \
        --fail-on-fp
}

run coroutines-ktor-io \
    kotlinx-coroutines-core-jvm-1.7.1.jar kotlinx-coroutines-core-jvm-1.11.0.jar \
    ktor-io-jvm-2.3.13.jar ":$KOTLIN"
run otel-sdk-common-sender-okhttp \
    opentelemetry-sdk-common-1.42.1.jar opentelemetry-sdk-common-1.60.1.jar \
    opentelemetry-exporter-sender-okhttp-1.42.1.jar
run guava-selenium \
    guava-22.0.jar guava-23.0-rc1.jar \
    selenium-remote-driver-3.4.0.jar
run koin-core-logger-slf4j \
    koin-core-jvm-3.2.2.jar koin-core-jvm-3.3.0.jar \
    koin-logger-slf4j-3.2.2.jar ":$KOTLIN"
run okhttp-digest \
    okhttp-3.14.1.jar okhttp-4.0.1.jar \
    okhttp-digest-1.21.jar ":$KOTLIN"
# pact's "class became final" violation is graph-walk, so it never appears in
# the verdict stream; this scenario still answer-checks the reference verdicts.
run pact-junit5-version-lag \
    junit5-4.2.3.jar junit5-4.2.2.jar \
    junit5spring-4.2.3.jar ":$KOTLIN"
run jetty-util-http-skew \
    jetty-util-9.3.26.v20190403.jar jetty-util-10.0.26.jar \
    jetty-http-9.4.49.v20220914.jar

echo "probe finished: no false-positive candidates on the fixture scenarios"
