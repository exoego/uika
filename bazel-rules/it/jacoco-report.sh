#!/bin/sh
# Turns the integration test's JaCoCo exec file into a report.
#
# Separate from the Makefile because finding the class files is the whole job and it does
# not fit on a recipe line. The ruleset is an EXTERNAL module inside the test workspace, so
# its outputs live under a canonical repository name bzlmod chooses ("uika+" today) and
# under one runfiles tree per target, which means the same libcheck.jar appears many times.
# JaCoCo wants each class once, so this takes the first of each name.
#
# Usage: jacoco-report.sh <jacoco-cli.jar> <exec> <it-workspace> <sources> <out.xml>
set -eu

CLI=$1
EXEC=$2
WS=$3
SOURCES=$4
OUT=$5
BAZEL=${BAZEL:-bazelisk}
JAVA=${JAVA:-java}

BIN=$(cd "$WS" && "$BAZEL" info bazel-bin)

# One --classfiles per distinct jar NAME. Sorting first makes the pick deterministic, so a
# report does not depend on the order find happened to walk the runfiles trees in.
#
# Bazel derives several jars per library and gives each a SUFFIXED name: libcore-hjar.jar
# and libcore-tjar.jar are header jars, signatures with the method bodies stripped, built
# for downstream compilation. JaCoCo cannot analyze one and fails the entire report on the
# first it meets ("Error while analyzing ... ClasspathDump$Artifact.class"). The real
# output is the unsuffixed libcore.jar, so anything with a dash in the name is skipped --
# a rule that also holds for whatever suffix a later Bazel adds.
set --
for jar in $(find "$BIN" -path '*/java/lib*.jar' ! -name '*-*.jar' \
                  | sort | awk -F/ '!seen[$NF]++'); do
  set -- "$@" --classfiles "$jar"
done
if [ "$#" -eq 0 ]; then
  echo "no ruleset jars under $BIN; did the integration test run?" >&2
  exit 1
fi

"$JAVA" -jar "$CLI" report "$EXEC" "$@" --sourcefiles "$SOURCES" --xml "$OUT"
