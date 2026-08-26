"""Assertions over the dump a `//...` sweep produced.

The sweep exists because a rule cannot expand a target pattern, so this checks that going
through the aspect and the merge step lands on the same answer the rule gives, for the
targets the two have in common.
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


sweep, rule_based = modules(load(sys.argv[1])), modules(load(sys.argv[2]))

for name in ("//app:app", "//lib:lib"):
    if name not in sweep:
        fail("{} is missing from the sweep: {}".format(name, sorted(sweep)))

# A target carrying maven_coordinates is a dependency, not a module of the build under
# check, and it already appears in every module that uses it.
for name in sweep:
    if name.startswith("//vendor:"):
        fail("{} is a third-party import and should not be a module".format(name))

# The sweep resolves execution-root paths while the rule resolves runfiles. Different
# mechanisms, so the agreement below is the thing worth checking.
for name in ("//app:app", "//lib:lib"):
    if sweep[name]["jdkRelease"] != rule_based[name]["jdkRelease"]:
        fail("{} release differs: sweep {} vs rule {}".format(
            name, sweep[name]["jdkRelease"], rule_based[name]["jdkRelease"]))

    def key(module):
        return sorted(
            "{}:{}:{}".format(d.get("group"), d.get("name"), d.get("version"))
            for d in module["deps"]
        )

    if key(sweep[name]) != key(rule_based[name]):
        fail("{} artifacts differ: sweep {} vs rule {}".format(
            name, key(sweep[name]), key(rule_based[name])))

    for path in [d["file"] for d in sweep[name]["deps"]] + sweep[name]["classes"]:
        if not os.path.isabs(path) or not os.path.exists(path):
            fail("{} names {}, which is not an existing absolute path".format(name, path))

print("sweep agrees with the rule on {} modules".format(len(sweep)))
