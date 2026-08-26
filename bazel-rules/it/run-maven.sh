#!/bin/sh
# Integration test for the pairing the issue is named after: Bazel plus
# rules_jvm_external, with coordinates coming from a real `maven.install` resolution rather
# than from tags written by hand.
#
# It also demonstrates the one thing --materialize exists for. A baseline dump names jars
# under bazel-out, which is build output rather than source, so they do not survive a clean
# tree, a fresh output base or another machine. This runs the check both ways across a
# `bazel clean` and asserts the difference. The failure mode is an error rather than
# quieter findings, since the changed pair's old jar is what the API diff is computed
# against, so uika exits 2 with "cannot open ..." instead of reporting fewer breaks.
#
# Run through the Makefile (`make bazel-maven-test`), which supplies UIKA_BIN. Needs
# network: the two committed lock files are pinned, so nothing is resolved, but the
# artifacts themselves are downloaded from Maven Central.
set -eu

REPO=$(cd "$(dirname "$0")/../.." && pwd)
RULES=$REPO/bazel-rules
UIKA_BIN=${UIKA_BIN:-$REPO/target/debug/uika}
BAZEL=${BAZEL:-bazelisk}
WORK=${TMPDIR:-/tmp}/uika-bazel-maven-it
WS=$WORK/ws
OUT=$WORK/out

if [ ! -x "$UIKA_BIN" ]; then
  echo "no uika binary at $UIKA_BIN (cargo build first)" >&2
  exit 1
fi

rm -rf "$WS" "$OUT"
mkdir -p "$WS" "$OUT"
cp -R "$RULES/it/maven-workspace/." "$WS/"
rm -f "$WS/MODULE.bazel.template"

# Both the @uika_cli repository rule and UpgradeCheckMain read this, so the check runs
# against the freshly built debug binary instead of downloading a release.
UIKA_CLI_PATH=$UIKA_BIN
export UIKA_CLI_PATH

render() {
  sed -e "s|@UIKA_RULES@|$RULES|" -e "s|@GUAVA@|$1|" \
    "$RULES/it/maven-workspace/MODULE.bazel.template" > "$WS/MODULE.bazel"
  cp "$WS/maven_install-$1.json" "$WS/maven_install.json"
}

cd "$WS"

echo "--- before dump (guava 22.0)"
render 22.0
"$BAZEL" run //:dump -- --output "$OUT/before.json"
"$BAZEL" run //:dump -- --output "$OUT/before-materialized.json" \
  --materialize "$OUT/baseline-jars"

# Models the flow the baseline artifact exists for, where the check runs somewhere the
# baseline's jars were never built. Same effect as a fresh runner, and reproducible here.
# The materialized copy is taken above, so it survives this.
echo "--- clean, so the baseline's jars are no longer on disk"
"$BAZEL" clean

echo "--- after dump (guava 23.0-rc1)"
render 23.0-rc1
"$BAZEL" run //:dump -- --output "$OUT/after.json"

run_check() {
  set +e
  "$BAZEL" run //:check -- --before "$1" --after "$OUT/after.json" > "$2" 2>&1
  echo $? > "$2.status"
  set -e
  cat "$2"
}

echo "--- check against the materialized baseline"
run_check "$OUT/before-materialized.json" "$OUT/materialized-report.txt"

echo "--- check against the baseline still pointing into Bazel's external directory"
run_check "$OUT/before.json" "$OUT/external-report.txt"

python3 "$RULES/it/assert_maven.py" \
  "$OUT/before.json" "$OUT/after.json" "$OUT/before-materialized.json" \
  "$OUT/materialized-report.txt" "$OUT/external-report.txt"

echo "bazel rules_jvm_external integration test passed"
