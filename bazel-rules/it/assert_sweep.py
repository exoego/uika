"""Assertions over the dump a `//...` sweep produced.

The sweep exists because a rule cannot expand a target pattern, so this checks that going
through the aspect and the merge step lands on the same answer the rule gives, for the
targets the two have in common.
"""

import json
import os
import sys

COMPARED = ("//app:app", "//lib:lib")


def fail(message):
    print("assertion failed: " + message, file=sys.stderr)
    sys.exit(1)


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def modules(dump):
    roots = dump["roots"]
    artifacts = [
        dict(a, file=roots[a["root"]] + a["path"]) for a in dump["artifacts"]
    ]
    out = {}
    for module in dump["modules"]:
        out[module["module"]] = dict(
            module,
            classes=[roots[d["root"]] + d["path"] for d in module["classesDirs"]],
            deps=[artifacts[i] for i in module["artifactRefs"]],
        )
    return out


def coordinates(module):
    return sorted(
        "{}:{}:{}".format(d.get("group"), d.get("name"), d.get("version"))
        for d in module["deps"]
    )


def files(module):
    return sorted([d["file"] for d in module["deps"]] + module["classes"])


if len(sys.argv) != 3:
    fail("usage: assert_sweep.py <sweep.json> <rule-based.json>")

sweep, rule_based = modules(load(sys.argv[1])), modules(load(sys.argv[2]))

for name in COMPARED:
    if name not in sweep:
        fail("{} is missing from the sweep: {}".format(name, sorted(sweep)))
    if name not in rule_based:
        fail("{} is missing from the rule-based dump: {}".format(name, sorted(rule_based)))

# A target carrying maven_coordinates is a dependency, not a module of the build under
# check, and it already appears in every module that uses it.
for name in sweep:
    if name.startswith("//vendor:"):
        fail("{} is a third-party import and should not be a module".format(name))

for name in COMPARED:
    if sweep[name].get("jdkRelease") != rule_based[name].get("jdkRelease"):
        fail("{} release differs: sweep {} vs rule {}".format(
            name, sweep[name].get("jdkRelease"), rule_based[name].get("jdkRelease")))

    if coordinates(sweep[name]) != coordinates(rule_based[name]):
        fail("{} artifacts differ: sweep {} vs rule {}".format(
            name, coordinates(sweep[name]), coordinates(rule_based[name])))

    # The two resolve by different mechanisms, the rule through runfiles and the sweep
    # through the execution root, and both have to land on the same real file. Comparing
    # only coordinates cannot see a sweep that resolved into Bazel's per-build symlink
    # forest, which is a dump whose paths die with the next invocation.
    if files(sweep[name]) != files(rule_based[name]):
        only_sweep = sorted(set(files(sweep[name])) - set(files(rule_based[name])))
        only_rule = sorted(set(files(rule_based[name])) - set(files(sweep[name])))
        fail("{} resolves to different files.\n  only in sweep: {}\n  only in rule:  {}".format(
            name, only_sweep, only_rule))

    for path in files(sweep[name]):
        if not os.path.isabs(path) or not os.path.exists(path):
            fail("{} names {}, which is not an existing absolute path".format(name, path))

print("sweep agrees with the rule on {} modules, out of {} it swept".format(
    len(COMPARED), len(sweep)))
