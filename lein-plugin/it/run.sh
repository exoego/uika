#!/bin/sh
# Integration test for the Leiningen plugin: install the plugin from source,
# dump a 3.4 -> 3.20.0 fixture pair, and make the REAL uika CLI (UIKA_BIN)
# read both dumps. Mirrors the Clojure tool's round-trip test: the dump JSON
# is hand-written, so only a real-CLI run catches drift from the v2 format.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
: "${UIKA_BIN:?set UIKA_BIN to the uika binary (make lein-test does)}"

(cd "$here/.." && lein install)

cd "$here/test-project"
rm -rf target
COMMONS_LANG3_VERSION=3.4 lein uika dump-classpath target/before.json
lein uika dump-classpath target/after.json

grep -q '"version":3.4' target/before.json || grep -q '"version":"3.4"' target/before.json
grep -q '"version":"3.20.0"' target/after.json

out="$(UIKA_CLI_PATH="$UIKA_BIN" lein uika upgrade-check target/before.json target/after.json)"
echo "$out"
echo "$out" | grep -q "CHANGED org.apache.commons:commons-lang3 3.4 -> 3.20.0"
echo "$out" | grep -q "scanned"
echo "lein-uika integration: OK"
