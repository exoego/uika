"""Assertions for the rules_jvm_external integration test.

The point of this test is that nothing in its workspace writes a maven_coordinates tag.
Every coordinate below came out of a real `maven.install` resolution, so it is evidence
that the aspect reads what rules_jvm_external actually produces.
"""

import json
import os
import sys


def fail(message):
    print("assertion failed: " + message, file=sys.stderr)
    sys.exit(1)


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def coordinates(dump):
    roots = dump["roots"]
    out = {}
    for artifact in dump["artifacts"]:
        if "group" in artifact:
            key = "{}:{}".format(artifact["group"], artifact["name"])
            out[key] = (artifact["version"], roots[artifact["root"]] + artifact["path"])
    return out


def status(path):
    with open(path + ".status", encoding="utf-8") as handle:
        return int(handle.read().strip())


def broken(path):
    with open(path, encoding="utf-8") as handle:
        report = handle.read()
    for line in report.splitlines():
        if line.startswith("scanned ") and " broken" in line:
            return int(line.split("❌")[1].split()[0]), report
    fail("no summary line in " + path)
    return None


(
    before_path,
    after_path,
    materialized_path,
    materialized_report,
    external_report,
    resolution_path,
    resolution_report,
) = sys.argv[1:8]

before, after = coordinates(load(before_path)), coordinates(load(after_path))
if before.get("com.google.guava:guava", (None,))[0] != "22.0":
    fail("before guava is {}".format(before.get("com.google.guava:guava")))
if after.get("com.google.guava:guava", (None,))[0] != "23.0-rc1":
    fail("after guava is {}".format(after.get("com.google.guava:guava")))
if before.get("org.seleniumhq.selenium:selenium-remote-driver", (None,))[0] != "3.4.0":
    fail("selenium is missing from the before dump")
# Transitive artifacts get coordinates too, not just the two named in MODULE.bazel.
if "com.google.code.findbugs:jsr305" not in before:
    fail("a transitive artifact carries no coordinates: {}".format(sorted(before)))

# Every jar must sit under an external repository, which is what makes the dangling-jar
# problem real and what --materialize moves it out of.
for name, (_, path) in before.items():
    if "/external/" not in path:
        fail("{} is not in an external repository: {}".format(name, path))

materialized = coordinates(load(materialized_path))
for name, (_, path) in materialized.items():
    if "/external/" in path or not os.path.exists(path):
        fail("{} was not materialized: {}".format(name, path))

count_materialized, _ = broken(materialized_report)
if status(materialized_report) != 1 or count_materialized == 0:
    fail("the materialized baseline should have found the guava breaks")

# The claim the README and docs/bazel.md make: a baseline that still points into
# bazel-out is worthless once those jars are gone, and uika answers with FEWER breaks and a
# warning rather than an error. Asserted rather than stated, because a silent shortfall is
# exactly the failure this test exists to pin down.
old_jar = before["com.google.guava:guava"][1]
if os.path.exists(old_jar):
    fail("{} should be gone after the clean".format(old_jar))

# It fails outright rather than reporting less. The changed pair's old jar is what the API
# diff is computed against, so uika cannot fall back to a partial answer the way it can for
# a classpath entry it cannot open.
if status(external_report) != 2:
    fail("expected exit 2 without --materialize, got {}".format(status(external_report)))
with open(external_report, encoding="utf-8") as handle:
    if "cannot open" not in handle.read():
        fail("expected a 'cannot open' error without --materialize")

# The PR gate's baseline builds none of the workspace's code but must record the same Maven
# artifacts. rules_jvm_external's jars are generated files, and an is_source test dropped them.
resolution = load(resolution_path)
if any(module["classesDirs"] for module in resolution["modules"]):
    fail("the baseline dump recorded build outputs")
recorded = {name: version for name, (version, _) in coordinates(resolution).items()}
expected = {name: version for name, (version, _) in before.items()}
if recorded != expected:
    fail("the baseline dump recorded {}, expected {}".format(recorded, expected))
count_resolution, resolution_text = broken(resolution_report)
if status(resolution_report) != 1 or count_resolution != count_materialized:
    fail("the baseline dump found {} breaks with exit {}, expected {}".format(
        count_resolution, status(resolution_report), count_materialized))
if "CHANGED com.google.guava:guava 22.0 -> 23.0-rc1" not in resolution_text:
    fail("the baseline dump did not see the guava change")
print("materialized baseline found {} breaks, as did the baseline dump, and"
      " without --materialize the check exits 2".format(count_materialized))
