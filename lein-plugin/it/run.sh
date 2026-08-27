#!/bin/sh
# Integration test for the Leiningen plugin: install the plugin from source,
# dump a 3.4 -> 3.20.0 fixture pair, and make the REAL uika CLI (UIKA_BIN)
# read both dumps. Mirrors the Clojure tool's round-trip test: the dump JSON
# is hand-written, so only a real-CLI run catches drift from the v2 format.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
: "${UIKA_BIN:?set UIKA_BIN to the uika binary (make lein-test does)}"
# lein-plugin/project.clj reads UIKA_VERSION, the fixture pins "0.0.0-dev", and make
# exports command-line overrides. Without this, `make check UIKA_VERSION=x` installs
# x and the fixture silently resolves whatever stale 0.0.0-dev sits in ~/.m2.
unset UIKA_VERSION

(cd "$here/.." && lein install </dev/null)

cd "$here/test-project"
rm -rf target
: > uika-exclude.toml
# :class-load-logs points here. A missing path is only a warning now, but an evidence
# set naming no class is refused when drafting, so it has to carry a load.
printf '[class,load] example.Consumer\n[class,load] org.apache.commons.lang3.StringUtils\n' > loads.log
# :jfr points here. A REAL recording, because only the JDK's own writer produces the
# chunk format the converter reads; `java -version` records enough jdk.ClassLoad
# events to convert.
rm -rf jfr-evidence && mkdir jfr-evidence
java "-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=jfr-evidence/probe.jfr" -version 2>/dev/null
COMMONS_LANG3_VERSION=3.4 lein uika dump-classpath target/before.json
lein uika dump-classpath target/after.json

grep -q '"version":"3.4"' target/before.json
grep -q '"version":"3.20.0"' target/after.json
# The fixture is Java with no :aot, so `lein compile` alone runs no javac. Its
# :target-path also carries a "%s", which the profile unmerge would re-format into a
# directory nothing compiled into. Both faults end the same way -- classesDirs
# without the classes -- so assert the dump names the dir javac actually wrote.
if [ ! -f target/classes/example/Consumer.class ]; then
  echo "FAIL: javac did not run (:prep-tasks skipped?)" >&2; exit 1
fi
# tr: data.json escapes every / as \/, so unescape before matching paths.
if ! tr -d '\\' < target/before.json | grep -q '"path":"[^"]*/target/classes"'; then
  echo "FAIL: compiled classes missing from classesDirs" >&2
  cat target/before.json >&2; exit 1
fi
# An explicit :jdk-release is the only way to name a runtime the probe cannot see, so
# it beats the probe in the dump. The fixture sets 11 while lein runs on a much newer
# JVM, which is exactly the disagreement.
if ! tr -d ' ' < target/before.json | grep -q '"jdkRelease":11'; then
  echo "FAIL: :jdk-release 11 did not reach the dump" >&2
  tr -d ' ' < target/before.json | sed -n 's/.*\("jdkRelease":[0-9]*\).*/\1/p' >&2; exit 1
fi
echo ":jdk-release override: dump records 11"

# Without that override, jdkRelease and the derived --jdk-release must describe the JVM
# the PROJECT runs on, not the one lein runs on: :eval-in-leiningen pins the plugin to
# lein's. Probe with a second JDK when one is around; UIKA_IT_ALT_JAVA names it.
if [ -n "${UIKA_IT_ALT_JAVA:-}" ] && [ -x "${UIKA_IT_ALT_JAVA:-}" ]; then
  alt_feature="$("$UIKA_IT_ALT_JAVA" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/.*java\.specification\.version = \([0-9][0-9]*\).*/\1/p')"
  lein update-in :uika dissoc :jdk-release \
    -- update-in : assoc :java-cmd "\"$UIKA_IT_ALT_JAVA\"" \
    -- uika dump-classpath target/altjvm.json >/dev/null
  got="$(tr -d ' ' < target/altjvm.json | sed -n 's/.*"jdkRelease":\([0-9]*\).*/\1/p')"
  if [ "$got" != "$alt_feature" ]; then
    echo "FAIL: jdkRelease $got is lein's JVM, not the project's $alt_feature" >&2; exit 1
  fi
  echo "project-JVM probe: jdkRelease $got matches :java-cmd"
fi

# A JDK 8 :java-cmd says java.specification.version = 1.8, the one spelling a bare
# Long/parseLong cannot read. The shim fakes only the probe output and hands anything
# else to the real java, so the dump must record 8. A nil probe would fall back to
# lein's own JVM and record the WRITING JVM's release, the issue #128 shape.
shim="$here/test-project/target/fake-jdk8-java"
real_java="$(command -v java)"
cat > "$shim" <<SHIM
#!/bin/sh
case "\$*" in
  *"-XshowSettings:properties"*)
    echo "    java.home = /opt/fake-jdk8" >&2
    echo "    java.specification.version = 1.8" >&2
    exit 0 ;;
esac
exec "$real_java" "\$@"
SHIM
chmod +x "$shim"
lein update-in :uika dissoc :jdk-release \
  -- update-in : assoc :java-cmd "\"$shim\"" \
  -- uika dump-classpath target/jdk8.json >/dev/null
got="$(tr -d ' ' < target/jdk8.json | sed -n 's/.*"jdkRelease":\([0-9]*\).*/\1/p')"
if [ "$got" != "8" ]; then
  echo "FAIL: a JDK 8 :java-cmd recorded jdkRelease $got, not 8" >&2; exit 1
fi
echo "JDK 8 probe: jdkRelease 8"

# :javac-options is the spelling that pins the API, so it must beat the probed JVM in
# the dump, the way every other tool's declared release does. Passed on the fly so the
# fixture keeps probing by default; 17 matches neither the fixture's :jdk-release 11
# nor the mise-pinned lein JVM's feature.
lein update-in :uika dissoc :jdk-release \
  -- update-in : assoc :javac-options '["--release" "17"]' \
  -- uika dump-classpath target/declared.json >/dev/null
got="$(tr -d ' ' < target/declared.json | sed -n 's/.*"jdkRelease":\([0-9]*\).*/\1/p')"
if [ "$got" != "17" ]; then
  echo "FAIL: :javac-options --release 17 recorded jdkRelease $got, not 17" >&2; exit 1
fi
echo ":javac-options derivation: jdkRelease 17"

# :provided is compile-scope: the fixture's Java source imports javax.servlet, so
# javac only succeeds because prep runs on the FULL project. Prepping the unmerged
# one (which drops :provided) fails with "package javax.servlet does not exist".
# The dump must still leave it out, since an uberjar does.
if grep -q 'servlet' target/before.json; then
  echo "FAIL: a :provided dependency shipped in the dump" >&2; exit 1
fi

# Dev-only dependencies must not ship in the dump: lein injects nREPL through
# the :base profile into every dev task, and commons-io is :dev-scoped above.
# This locks the :default unmerge.
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
# :jfr conversion runs plugin-side before the CLI. The event-count line proves the
# compiled JfrEvidence loaded under lein's own JVM and read the recording.
echo "$out" | grep -q "uika: converted jfr-evidence/probe.jfr"
# The application class must be a reachability root, or `:fail-on "reachable"` gates
# on nothing and every assertion above it passes with an empty classesDirs.
if echo "$out" | grep -q "no application root matched"; then
  echo "FAIL: the dump gave the CLI no application classes to root from" >&2; exit 1
fi

# Stub CLI recording its argv: every :uika option must reach the CLI spelled as
# the real flag. The stub sits where --before points so the args land nearby.
stub="$here/test-project/target/uika-stub"
printf '#!/bin/sh\necho "$@" > "$3.args"\nexit 0\n' > "$stub"
chmod +x "$stub"
UIKA_CLI_PATH="$stub" lein uika upgrade-check target/before.json target/after.json
args="$(cat target/before.json.args)"
case "$args" in *"--fail-on reachable"*) ;; *) echo "FAIL: fail-on not forwarded: $args" >&2; exit 1;; esac
case "$args" in *"--exclude-file uika-exclude.toml"*) ;; *) echo "FAIL: exclude-files not forwarded: $args" >&2; exit 1;; esac
case "$args" in *"--class-load-log loads.log"*) ;; *) echo "FAIL: class-load-logs not forwarded: $args" >&2; exit 1;; esac
case "$args" in *"--class-load-log jfr-evidence"*) ;; *) echo "FAIL: the :jfr directory was not forwarded: $args" >&2; exit 1;; esac
case "$args" in *"jfr-class-load/jfr-1-probe.log"*) ;; *) echo "FAIL: the jfr conversion is missing from argv: $args" >&2; exit 1;; esac
case "$args" in *"--draft-exclude-file uika-draft.toml"*) ;; *) echo "FAIL: draft-exclude-file not forwarded: $args" >&2; exit 1;; esac
# Presence, not the exact 11: effective-jdk-release clamps to the JVM's ct.sym
# ceiling, so a pre-12 lein JVM would legitimately send a lower number and a JRE
# none at all. .mise.toml pins temurin-21, which passes 11 through unclamped.
case "$args" in
  *"--jdk-release 11"*) ;;
  *"--jdk-release "*) echo "WARN: jdk-release clamped by this JVM's ct.sym: $args" >&2 ;;
  *) echo "FAIL: jdk-release not forwarded (run via make lein-test): $args" >&2; exit 1;;
esac

# A misspelt :uika key must abort, not vanish: destructuring drops what it does not
# name, so a silently-ignored key is a silently-disabled flag.
if err="$(UIKA_CLI_PATH="$stub" lein update-in :uika assoc :exclude-file '["x.toml"]' \
            -- uika upgrade-check target/before.json target/after.json 2>&1)"; then
  echo "FAIL: an unknown :uika key was accepted" >&2; exit 1
fi
case "$err" in *"unknown :uika option"*) ;; *) echo "FAIL: wrong abort: $err" >&2; exit 1;; esac

# A CLI that found violations (exit 1) must fail the task -- and fail for THAT
# reason, so a broken UIKA_CLI_PATH or a missing dump cannot pass this case by
# failing for its own.
printf '#!/bin/sh\nexit 1\n' > "$stub"
if err="$(UIKA_CLI_PATH="$stub" lein uika upgrade-check target/before.json target/after.json 2>&1)"; then
  echo "FAIL: exit 1 from the CLI did not fail lein uika" >&2; exit 1
fi
case "$err" in *"found broken references"*) ;; *) echo "FAIL: wrong failure: $err" >&2; exit 1;; esac

echo "lein-uika integration: OK"
