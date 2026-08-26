#!/bin/sh
# Integration test for the Bazel ruleset: dump a workspace's classpath before and after a
# dependency version bump, then run the real uika binary over the pair.
#
# The workspace is copied to a temp directory rather than driven in place, because the
# version bump under test is a BUILD-file edit and the repository has to stay clean. The
# copy path is stable, so Bazel reuses one output base across runs.
#
# Run through the Makefile (`make bazel-test`), which supplies UIKA_BIN.
set -eu

REPO=$(cd "$(dirname "$0")/../.." && pwd)
RULES=$REPO/bazel-rules
UIKA_BIN=${UIKA_BIN:-$REPO/target/debug/uika}
BAZEL=${BAZEL:-bazelisk}
WORK=${TMPDIR:-/tmp}/uika-bazel-it
WS=$WORK/ws
OUT=$WORK/out

if [ ! -x "$UIKA_BIN" ]; then
  echo "no uika binary at $UIKA_BIN (cargo build first)" >&2
  exit 1
fi

rm -rf "$WS" "$OUT"
mkdir -p "$WS" "$OUT"
cp -R "$RULES/it/test-workspace/." "$WS/"
sed "s|@UIKA_RULES@|$RULES|" "$WS/MODULE.bazel.template" > "$WS/MODULE.bazel"
rm "$WS/MODULE.bazel.template"

# The guava pair and its consumer are the guava-selenium row of cli/tests/scenarios.tsv, so
# the breaks this test expects are the ones the golden files already document.
for jar in guava-22.0.jar guava-23.0-rc1.jar selenium-remote-driver-3.4.0.jar; do
  cp "$REPO/cli/tests/fixtures/$jar" "$WS/vendor/$jar"
done

cd "$WS"

echo "--- baseline dump (resolution only)"
# The workspace directory is recreated per run but its output base is not, so bazel-out
# still holds the jars the LAST run built. Without this clean the assertion below reads
# stale state and passes no matter what build_outputs does.
"$BAZEL" clean
"$BAZEL" run //:resolution_dump -- --output "$OUT/resolution.json"
# Nothing under bazel-bin for the two source targets: a baseline dump has to be able to run
# on a branch it never builds, which is the whole reason the PR gate can afford it.
for built in bazel-bin/app bazel-bin/lib; do
  if [ -e "$built" ]; then
    echo "build_outputs = False built $built" >&2
    exit 1
  fi
done

echo "--- before dump"
"$BAZEL" run //:dump -- --output "$OUT/before.json"

echo "--- after dump (guava 22.0 -> 23.0-rc1)"
"$BAZEL" run --define guava=23 //:dump -- --output "$OUT/after.json"

echo "--- upgrade-check"
set +e
"$UIKA_BIN" upgrade-check --before "$OUT/before.json" --after "$OUT/after.json" > "$OUT/report.txt" 2>&1
status=$?
set -e
cat "$OUT/report.txt"
if [ "$status" -ne 1 ]; then
  echo "expected exit 1 (violations found), got $status" >&2
  exit 1
fi

set +e
"$UIKA_BIN" upgrade-check --before "$OUT/before.json" --after "$OUT/after.json" \
  --fail-on never > /dev/null 2>&1
clean_status=$?
set -e
if [ "$clean_status" -ne 0 ]; then
  echo "--fail-on never should exit 0, got $clean_status" >&2
  exit 1
fi

python3 "$RULES/it/assert_dump.py" "$OUT/before.json" "$OUT/after.json" \
  "$OUT/resolution.json" "$OUT/report.txt"

echo "bazel integration test passed"
