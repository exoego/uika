#!/bin/sh
# The documented Gradle PR gate, end to end, with the published plugin and CLI instead of
# the stub the plugin tests use: a base dump that resolves without compiling, a PR dump
# that builds the module, and a check that must fail on the break between them.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
GRADLE=${GRADLE:-gradle}
version=${UIKA_VERSION:-0.0.0-dev}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fail() {
    echo "gradle-e2e: $1" >&2
    [ -f "$2" ] && sed 's/^/  | /' "$2" >&2
    exit 1
}

# The plugin resolves net.exoego.uika:uika-cli:<its own version>:jvm@jar through the
# build's repositories, so both land in mavenLocal under one version.
$GRADLE -q -p "$root/cli-java" publishToMavenLocal -PuikaVersion="$version"
$GRADLE -q -p "$root/gradle-plugin" publishToMavenLocal -PuikaVersion="$version"

# The CLI's fixture jars in Maven layout. No POMs: the project declares every artifact.
fixtures="$root/cli-java/tests/fixtures"
repo="$work/repo"
place() {
    mkdir -p "$repo/$1/$2/$3"
    cp "$fixtures/$2-$3.jar" "$repo/$1/$2/$3/"
}
place io/insert-koin koin-core-jvm 3.2.2
place io/insert-koin koin-core-jvm 3.3.0
place io/insert-koin koin-logger-slf4j 3.2.2
place org/jetbrains/kotlin kotlin-stdlib 2.2.20

# A copy, so the build writes nothing into the tree. Gradle runs from the repository root
# with -p, because mise resolves the pinned Gradle from the working directory.
project="$work/project"
cp -R "$here/project" "$project"
g() {
    (cd "$root" && $GRADLE --console=plain -p "$project" -PuikaVersion="$version" -PfixtureRepo="$repo" "$@")
}

# 1. The base dump resolves the old version and compiles nothing.
g uikaDumpClasspath -PkoinVersion=3.2.2 -PuikaOutput="$work/before.json" -PuikaBuildOutputs=false \
    >"$work/before.log" 2>&1 || fail "the base dump failed" "$work/before.log"
grep -q "> Task :app:compileJava" "$work/before.log" && fail "the base dump compiled :app" "$work/before.log"
grep -q '"version":"3.2.2"' "$work/before.json" || fail "the base dump does not name koin-core-jvm 3.2.2" "$work/before.json"

# 2. The PR dump resolves the new version and builds the module it will scan from.
g uikaDumpClasspath -PkoinVersion=3.3.0 -PuikaOutput="$work/after.json" \
    >"$work/after.log" 2>&1 || fail "the PR dump failed" "$work/after.log"
grep -q "> Task :app:compileJava" "$work/after.log" || fail "the PR dump did not compile :app" "$work/after.log"
grep -q '"version":"3.3.0"' "$work/after.json" || fail "the PR dump does not name koin-core-jvm 3.3.0" "$work/after.json"

# 3. The check fails the build on the break, reached from the module's own class.
if g uikaUpgradeCheck -PkoinVersion=3.3.0 -PuikaBefore="$work/before.json" -PuikaAfter="$work/after.json" \
    >"$work/check.log" 2>&1; then
    fail "upgrade-check passed across koin-core 3.2.2 -> 3.3.0" "$work/check.log"
fi
grep -q "koin-core-jvm 3.2.2 -> 3.3.0" "$work/check.log" || fail "the version diff is missing" "$work/check.log"
grep -q "became final, but .* still overrides it" "$work/check.log" || fail "the final-override break is missing" "$work/check.log"
grep -q "💥" "$work/check.log" || fail "the break is not marked reachable from :app" "$work/check.log"
grep -q "broken references" "$work/check.log" || fail "the plugin did not fail on the break" "$work/check.log"

# 4. Control: the same dump on both sides passes, so step 3 failed on the diff alone.
g uikaUpgradeCheck -PkoinVersion=3.3.0 -PuikaBefore="$work/after.json" -PuikaAfter="$work/after.json" \
    >"$work/same.log" 2>&1 || fail "upgrade-check failed with nothing changed" "$work/same.log"

echo "gradle-e2e: ok (uika $version)"
