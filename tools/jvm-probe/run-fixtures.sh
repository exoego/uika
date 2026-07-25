#!/bin/sh
# Answer-check the golden fixture scenarios against a real JVM: run uika check
# with --verdicts-json, then let tools/jvm-probe/Probe.java resolve every
# reference with MethodHandles.Lookup. A verdict uika calls broken that the JVM
# links fine is a false positive and fails the run (--fail-on-fp); ok/unknown
# verdicts that fail on the new side but link on the old side are listed as
# false-negative candidates for triage.
#
# Scenarios come from cli/tests/scenarios.tsv, shared with cli/tests/golden.rs.
# Graph-walk violations (koin's "method became final", pact's "class became
# final") never enter the verdict stream, so those scenarios' actual breaking
# change is NOT answer-checked here; the probe covers their reference verdicts
# only. Run from the repository root (make probe does).
set -eu

UIKA=${UIKA:-target/debug/uika}
JAVA=${JAVA:-java}
FIX=cli/tests/fixtures
OUT=${OUT:-target/probe}
SCENARIOS=cli/tests/scenarios.tsv
TAB=$(printf '\t')
mkdir -p "$OUT"

while IFS="$TAB" read -r name old new consumer extra; do
    case $name in ''|'#'*) continue ;; esac
    shared=""
    [ "$extra" != "-" ] && shared=":$FIX/$extra"
    echo "== $name"
    "$UIKA" check --old "$FIX/$old" --new "$FIX/$new" --classpath "$FIX/$consumer" \
        --verdicts-json "$OUT/$name.jsonl" --fail-on never >/dev/null
    # $JAVA unquoted on purpose: it may carry a launcher prefix ("mise exec -- java").
    $JAVA tools/jvm-probe/Probe.java --verdicts "$OUT/$name.jsonl" \
        --classpath "$FIX/$new:$FIX/$consumer$shared" \
        --old-classpath "$FIX/$old:$FIX/$consumer$shared" \
        --fail-on-fp
done < "$SCENARIOS"

echo "probe finished: no false-positive candidates on the fixture scenarios"
