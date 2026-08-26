"""Assertions over the dumps bazel-rules/it/run.sh produced.

Everything checked here is something the CLI reads and would otherwise fail on silently:
an unnamed or renamed module drops upgrade-check to merged mode, a missing jar path is a
warning and fewer findings, and a wrong jdkRelease manufactures or hides a JDK-pair run.
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


def resolved(dump):
    """The dump, with every root-table index expanded back into a full path."""
    roots = dump["roots"]
    artifacts = [
        dict(a, file=roots[a["root"]] + a["path"]) for a in dump["artifacts"]
    ]
    modules = [
        dict(
            m,
            classes=[roots[d["root"]] + d["path"] for d in m["classesDirs"]],
            deps=[artifacts[i] for i in m["artifactRefs"]],
        )
        for m in dump["modules"]
    ]
    return artifacts, {m["module"]: m for m in modules}


def coordinate(artifact):
    if "group" not in artifact:
        return None
    return "{}:{}:{}".format(artifact["group"], artifact["name"], artifact["version"])


def main():
    before_path, after_path, resolution_path, report_path = sys.argv[1:5]
    before, after, resolution = (
        load(before_path),
        load(after_path),
        load(resolution_path),
    )

    for name, dump in (("before", before), ("after", after), ("resolution", resolution)):
        if dump.get("version") != 2:
            fail("{} dump is not v2".format(name))
        artifacts, modules = resolved(dump)
        if sorted(modules) != ["//app:app", "//lib:lib"]:
            fail("{} dump modules are {}".format(name, sorted(modules)))
        # An @@-prefixed or renamed module would pair with nothing in the other dump.
        if modules["//app:app"]["jdkRelease"] != 11:
            fail("{}: //app:app should take 11 from the java toolchain".format(name))
        if modules["//lib:lib"]["jdkRelease"] != 17:
            fail("{}: //lib:lib should take 17 from its own javacopts".format(name))
        if dump.get("jdkRelease") != 11:
            fail("{}: the dump-level release should be the lowest module's".format(name))
        for artifact in artifacts:
            if not os.path.exists(artifact["file"]):
                fail("{}: {} does not exist".format(name, artifact["file"]))

    _, before_modules = resolved(before)
    _, after_modules = resolved(after)
    _, resolution_modules = resolved(resolution)

    app_before = before_modules["//app:app"]
    if not app_before["classes"]:
        fail("//app:app has no build output recorded")
    for module in resolution_modules.values():
        if module["classes"]:
            fail("a resolution-only dump must record no build outputs")

    projects = [d.get("project") for d in app_before["deps"]]
    if "//lib:lib" not in projects:
        fail("//app:app should carry //lib:lib as a project-attributed artifact")

    versions = {
        "before": sorted(filter(None, (coordinate(d) for d in app_before["deps"]))),
        "after": sorted(
            filter(None, (coordinate(d) for d in after_modules["//app:app"]["deps"]))
        ),
    }
    expected = {
        "before": [
            "com.google.guava:guava:22.0",
            "org.seleniumhq.selenium:selenium-remote-driver:3.4.0",
        ],
        "after": [
            "com.google.guava:guava:23.0-rc1",
            "org.seleniumhq.selenium:selenium-remote-driver:3.4.0",
        ],
    }
    if versions != expected:
        fail("coordinates are {}, expected {}".format(versions, expected))

    # The baseline dump skips generated jars but must keep the external ones: they are the
    # only side of the version diff it exists to provide.
    resolution_coordinates = sorted(
        filter(None, (coordinate(d) for d in resolution_modules["//app:app"]["deps"]))
    )
    if resolution_coordinates != expected["before"]:
        fail("resolution dump coordinates are {}".format(resolution_coordinates))

    with open(report_path, encoding="utf-8") as handle:
        report = handle.read()
    for expected_line in (
        "CHANGED com.google.guava:guava 22.0 -> 23.0-rc1",
        "1 of 2 modules changed their resolved versions",
        "TimeLimiter.callWithTimeout",
    ):
        if expected_line not in report:
            fail("the report does not mention {!r}".format(expected_line))


main()
