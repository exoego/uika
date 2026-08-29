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

# Both the @uika_cli repository rule and UpgradeCheckMain read this, so the check runs
# against the freshly built debug binary instead of downloading a release.
UIKA_CLI_PATH=$UIKA_BIN
export UIKA_CLI_PATH

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

echo "--- before dump, materialized"
"$BAZEL" run //:dump -- --output "$OUT/before-materialized.json" \
  --materialize "$OUT/baseline-jars"

echo "--- after dump (guava 22.0 -> 23.0-rc1)"
"$BAZEL" run --define guava=23 //:dump -- --output "$OUT/after.json"

echo "--- upgrade-check"
set +e
"$BAZEL" run //:check -- --before "$OUT/before.json" --after "$OUT/after.json" \
  > "$OUT/report.txt" 2>&1
status=$?
set -e
cat "$OUT/report.txt"
if [ "$status" -ne 1 ]; then
  echo "expected exit 1 (violations found), got $status" >&2
  exit 1
fi

set +e
"$BAZEL" run //:check -- --before "$OUT/before.json" --after "$OUT/after.json" \
  --failOn never > /dev/null 2>&1
clean_status=$?
set -e
if [ "$clean_status" -ne 0 ]; then
  echo "--failOn never should exit 0, got $clean_status" >&2
  exit 1
fi

set +e
"$BAZEL" run //:check_without_targets -- --before "$OUT/before.json" --after "$OUT/after.json" \
  > /dev/null 2>&1
no_targets_status=$?
set -e
if [ "$no_targets_status" -ne 1 ]; then
  echo "the no-targets check should exit 1, got $no_targets_status" >&2
  exit 1
fi

# The same finding out of the materialized baseline. Its jars live outside Bazel's reach,
# which is what a PR job needs once the lockfile change has taken the originals away.
set +e
"$BAZEL" run //:check -- --before "$OUT/before-materialized.json" --after "$OUT/after.json" \
  > "$OUT/materialized-report.txt" 2>&1
materialized_status=$?
set -e
if [ "$materialized_status" -ne 1 ]; then
  echo "expected exit 1 from the materialized baseline, got $materialized_status" >&2
  cat "$OUT/materialized-report.txt" >&2
  exit 1
fi

echo "--- jdk-release semantics against an argv-recording stub"
# The real binary cannot show which flags it was handed, so this section swaps in a stub
# that records its argv and UIKA_JDK. The stub ignores the dumps, so the earlier pair is
# reused as-is.
STUB=$WORK/stub-cli
cat > "$STUB" <<'EOF'
#!/bin/sh
printf '%s\n' "$@" > "$UIKA_STUB_ARGS"
printf '%s' "${UIKA_JDK:-}" > "$UIKA_STUB_ARGS.jdk"
exit 0
EOF
chmod +x "$STUB"

# Default: the lowest declared release (11 from //app) reaches the CLI, paired with UIKA_JDK.
UIKA_STUB_ARGS=$OUT/stub-args-default.txt UIKA_CLI_PATH=$STUB "$BAZEL" run //:check -- \
  --before "$OUT/before.json" --after "$OUT/after.json"
if ! tr '\n' ' ' < "$OUT/stub-args-default.txt" | grep -q -- "--jdk-release 11 "; then
  echo "the default check should pass --jdk-release 11, got:" >&2
  cat "$OUT/stub-args-default.txt" >&2
  exit 1
fi
if [ ! -s "$OUT/stub-args-default.txt.jdk" ]; then
  echo "UIKA_JDK should be exported when --jdk-release is passed" >&2
  exit 1
fi

# --jdkRelease 0 on the command line switches the layer off: no flag, no UIKA_JDK.
UIKA_STUB_ARGS=$OUT/stub-args-zero.txt UIKA_CLI_PATH=$STUB "$BAZEL" run //:check -- \
  --before "$OUT/before.json" --after "$OUT/after.json" --jdkRelease 0
if grep -qx -- "--jdk-release" "$OUT/stub-args-zero.txt"; then
  echo "--jdkRelease 0 should omit --jdk-release, got:" >&2
  cat "$OUT/stub-args-zero.txt" >&2
  exit 1
fi
if [ -s "$OUT/stub-args-zero.txt.jdk" ]; then
  echo "UIKA_JDK should not be exported when the layer is off" >&2
  exit 1
fi

# jdk_release = 0 on the rule means the same off switch.
UIKA_STUB_ARGS=$OUT/stub-args-attr-zero.txt UIKA_CLI_PATH=$STUB "$BAZEL" run //:check_layer_off -- \
  --before "$OUT/before.json" --after "$OUT/after.json"
if grep -qx -- "--jdk-release" "$OUT/stub-args-attr-zero.txt"; then
  echo "jdk_release = 0 on the rule should omit --jdk-release, got:" >&2
  cat "$OUT/stub-args-attr-zero.txt" >&2
  exit 1
fi

echo "--- runtime load evidence"
JFR=$OUT/jfr
# The flag is printed by the check target rather than written out here, so this recipe
# cannot drift from the format the converter expects. Printing it also creates the
# directory, which JFR needs in place before any test JVM starts.
jvmopt=$("$BAZEL" run //:check -- jfr-jvmopt "$JFR")
# --nocache_test_results because a cached test forks no JVM and would record nothing,
# with no symptom at all. --sandbox_writable_path because the recording lands outside the
# sandbox on purpose, so the check can read it afterwards.
"$BAZEL" test //app:load_test --nocache_test_results --sandbox_writable_path="$JFR" "$jvmopt"

set +e
"$BAZEL" run //:check -- --before "$OUT/before.json" --after "$OUT/after.json" \
  --jfr "$JFR" --failOn reachable > "$OUT/jfr-report.txt" 2>&1
jfr_status=$?
set -e
cat "$OUT/jfr-report.txt"
if [ "$jfr_status" -ne 1 ]; then
  echo "expected exit 1 from --failOn reachable with load evidence, got $jfr_status" >&2
  exit 1
fi

python3 "$RULES/it/assert_jfr.py" "$OUT/jfr-report.txt"

# A recording handed to --classLoadLog must be converted exactly like a --jfr value:
# the CLI is JVM-free and skips .jfr names silently, so forwarding it raw loses the
# evidence with no symptom at all.
rec="$(find "$JFR" -name '*.jfr' | head -1)"
set +e
"$BAZEL" run //:check -- --before "$OUT/before.json" --after "$OUT/after.json" \
  --classLoadLog "$rec" --failOn reachable > "$OUT/cll-jfr-report.txt" 2>&1
cll_status=$?
set -e
if ! grep -q "uika: converted" "$OUT/cll-jfr-report.txt"; then
  echo "a recording passed via --classLoadLog was not converted:" >&2
  cat "$OUT/cll-jfr-report.txt" >&2
  exit 1
fi
if [ "$cll_status" -ne 1 ]; then
  echo "expected exit 1 from --failOn reachable with --classLoadLog evidence, got $cll_status" >&2
  cat "$OUT/cll-jfr-report.txt" >&2
  exit 1
fi
python3 "$RULES/it/assert_jfr.py" "$OUT/cll-jfr-report.txt"

echo "--- sweep over //... with the aspect"
BIN=$("$BAZEL" info bazel-bin)
# Fragments live in bazel-out and nothing prunes them, so a target deleted since the last
# sweep would still contribute its module. Clearing first is part of the recipe. The guard is
# the recipe's too, because bazel info PRINTS bazel-bin without creating it, so find exits 1
# on a fresh output base and kills a set -e job on the recipe's first line.
if [ -d "$BIN" ]; then
  find "$BIN" -name "*.uika-manifest.tsv" -delete
fi
"$BAZEL" build //... --aspects=@uika//:defs.bzl%uika_classpath_aspect \
  --output_groups=uika_dump
"$BAZEL" run @uika//:merge -- --output "$OUT/sweep.json" \
  --execroot "$("$BAZEL" info execution_root)" --fragments "$BIN"

python3 "$RULES/it/assert_sweep.py" "$OUT/sweep.json" "$OUT/before.json"

python3 "$RULES/it/assert_dump.py" "$OUT/before.json" "$OUT/after.json" \
  "$OUT/resolution.json" "$OUT/report.txt" \
  "$OUT/before-materialized.json" "$OUT/baseline-jars" "$OUT/materialized-report.txt"

echo "--- exclude_files comma guard"
# The attr rides one comma-joined -D property, so a comma inside a path would be
# silently split into two bogus paths. The macro fails loudly instead, the same
# decision manifest.bzl makes for tab and newline. A separate package, added after
# the //... sweep above so the sweep never loads it.
mkdir -p badcheck
cat > badcheck/BUILD.bazel <<'EOF'
load("@uika//:defs.bzl", "uika_upgrade_check")

uika_upgrade_check(
    name = "bad",
    exclude_files = ["ex,cludes.toml"],
)
EOF
set +e
"$BAZEL" build //badcheck:bad > "$OUT/comma-guard.txt" 2>&1
guard_status=$?
set -e
rm -rf badcheck
if [ "$guard_status" -eq 0 ]; then
  echo "a comma-carrying exclude_files entry should fail the load" >&2
  cat "$OUT/comma-guard.txt" >&2
  exit 1
fi
if ! grep -q "carries a comma" "$OUT/comma-guard.txt"; then
  echo "expected the uika comma message:" >&2
  cat "$OUT/comma-guard.txt" >&2
  exit 1
fi

echo "bazel integration test passed"
