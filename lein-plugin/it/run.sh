#!/bin/sh
# Integration test for the Leiningen plugin: install the plugin from source,
# dump a 3.4 -> 3.20.0 fixture pair, and make the REAL uika CLI (UIKA_BIN)
# read both dumps. Mirrors the Clojure tool's round-trip test: the dump JSON
# is hand-written, so only a real-CLI run catches drift from the v2 format.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
: "${UIKA_BIN:?set UIKA_BIN to the uika binary (make lein-test does)}"

(cd "$here/.." && lein install </dev/null)

cd "$here/test-project"
rm -rf target
: > uika-exclude.toml
COMMONS_LANG3_VERSION=3.4 lein uika dump-classpath target/before.json
lein uika dump-classpath target/after.json

grep -q '"version":"3.4"' target/before.json
grep -q '"version":"3.20.0"' target/after.json
# Dev-only dependencies must not ship in the dump: lein injects nREPL through
# the :base profile into every dev task, and commons-io is :dev-scoped above.
# This locks the [:base :user :dev :provided] unmerge.
if grep -q 'nrepl' target/before.json; then
  echo "FAIL: nREPL (a :base-profile injection) leaked into the dump" >&2; exit 1
fi
if grep -q 'commons-io' target/before.json; then
  echo "FAIL: a :dev-profile dependency leaked into the dump" >&2; exit 1
fi

# The real CLI reads both dumps; the :uika map's flags ride along and a flag the
# CLI rejects would exit 2 here.
out="$(UIKA_CLI_PATH="$UIKA_BIN" lein uika upgrade-check target/before.json target/after.json)"
echo "$out"
echo "$out" | grep -q "CHANGED org.apache.commons:commons-lang3 3.4 -> 3.20.0"
echo "$out" | grep -q "scanned"

# Stub CLI recording its argv: every :uika option must reach the CLI spelled as
# the real flag. The stub sits where --before points so the args land nearby.
stub="$here/test-project/target/uika-stub"
printf '#!/bin/sh\necho "$@" > "$3.args"\nexit 0\n' > "$stub"
chmod +x "$stub"
UIKA_CLI_PATH="$stub" lein uika upgrade-check target/before.json target/after.json
args="$(cat target/before.json.args)"
case "$args" in *"--fail-on reachable"*) ;; *) echo "FAIL: fail-on not forwarded: $args" >&2; exit 1;; esac
case "$args" in *"--exclude-file uika-exclude.toml"*) ;; *) echo "FAIL: exclude-files not forwarded: $args" >&2; exit 1;; esac
case "$args" in *"--jdk-release 11"*) ;; *) echo "FAIL: jdk-release not forwarded: $args" >&2; exit 1;; esac

# A CLI that found violations (exit 1) must fail the task.
printf '#!/bin/sh\nexit 1\n' > "$stub"
if UIKA_CLI_PATH="$stub" lein uika upgrade-check target/before.json target/after.json 2>/dev/null; then
  echo "FAIL: exit 1 from the CLI did not fail lein uika" >&2; exit 1
fi

echo "lein-uika integration: OK"
