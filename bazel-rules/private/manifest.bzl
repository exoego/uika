"""The manifest records both the uika_dump rule and the sweep aspect emit.

Shared so one code path decides what a module looks like. The two differ only in which
path each jar is named by, which is what `path_of` selects. A `bazel run` target resolves
runfiles, so it uses `short_path`; the sweep has no runfiles tree and its merge step
prefixes the execution root instead, so it uses the execroot-relative `path`.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")

UikaClasspathInfo = provider(
    doc = "Per-jar coordinate attribution over a target's transitive closure.",
    fields = {
        "owners": "depset of struct(path, short_path, group, name, version, project), one " +
                  "entry per jar, naming the target that produced it",
        "own_jars": "list of File: the visited target's own runtime output jars",
        "javacopts": "list of str: the visited target's javacopts, for the release derivation",
    },
)

def module_name(label):
    """`//app:app`, not the `@@//app:app` that str() gives since Bazel 7.

    upgrade-check pairs modules between two dumps by this string, and matches an artifact's
    `project` attribution against it, so both have to be the stable spelling a user types.
    """
    if label.workspace_name:
        return str(label)
    return "//{}:{}".format(label.package, label.name)

def short_path(jar):
    """Runfiles-relative, for a manifest a `bazel run` target resolves."""
    return jar.short_path

def exec_path(jar):
    """Execution-root-relative, for a fragment the sweep's merge step resolves."""
    return jar.path

def line(*fields):
    """One manifest record.

    A line-oriented wire format, not JSON, purely so the Java side needs no JSON parser --
    jvm-plugin-core deliberately has none, because every other front end borrows its build
    tool's. Fields are tab-separated and belong to the most recent "module" line.

    A field carrying a delimiter fails the build rather than being escaped. A tab or a
    newline cannot occur in a Bazel label, and a javacopt or a path holding one is
    pathological, so a codec to maintain on both sides of the wire would exist only to hide
    a corrupt manifest that the Java side would then mis-parse into the wrong module.
    """
    values = [f if f else "" for f in fields]
    for value in values:
        if "\t" in value or "\n" in value:
            fail("a uika manifest field cannot contain a tab or a newline: {}".format(value))
    return "\t".join(values)

def runtime_jars(target):
    """Everything on the target's runtime classpath.

    java_library and java_import answer with JavaInfo, but a java_binary's
    transitive_runtime_jars is EMPTY -- its classpath lives in JavaRuntimeClasspathInfo
    instead. Asking JavaInfo alone produced a dump with no artifacts at all and no error.
    """
    if java_common.JavaRuntimeClasspathInfo in target:
        return target[java_common.JavaRuntimeClasspathInfo].runtime_classpath.to_list()
    return target[JavaInfo].transitive_runtime_jars.to_list()

def module_records(
        target,
        info,
        toolchain_release,
        path_of,
        build_outputs = True,
        releases_only = False):
    """The manifest records for one module, and the jars they name.

    Returns a struct with `lines` and `jars`. The caller decides what to do with the jars,
    which is runfiles for the rule and an output group for the sweep, but either way
    requesting the manifest has to build them or it names files that were never produced.
    """
    lines = [
        line("module", module_name(target.label)),
        line("toolchain", toolchain_release),
    ]
    for opt in info.javacopts:
        lines.append(line("javacopt", opt))

    if releases_only:
        # The check target needs the release derivation and nothing else, so it skips the
        # classpath entirely. Sharing this function keeps ONE derivation, which is what
        # stops the flag and the recorded release from disagreeing.
        return struct(lines = lines, jars = [])

    jars = []
    own = {jar.path: None for jar in info.own_jars}
    owners = {entry.path: entry for entry in info.owners.to_list()}
    if build_outputs:
        for jar in info.own_jars:
            lines.append(line("classes", path_of(jar)))
            jars.append(jar)

    # The runtime classpath decides WHAT is on it (it already accounts for neverlink and
    # compile-only deps, which the attribute walk alone would not); the aspect only decides
    # WHO produced each jar. A jar with no owner entry still goes in uncoordinated, which is
    # strictly better than dropping it from the scan.
    for jar in runtime_jars(target):
        if jar.path in own:
            continue
        entry = owners.get(jar.path)
        project = entry.project if entry else None
        if not build_outputs and not jar.is_source:
            # build_outputs = False means "build nothing", so the jars to leave out are
            # exactly the generated ones. is_source rather than a main-repository test: a
            # vendored jar in the main repository needs no build and carries the coordinates
            # the version diff runs on, so dropping it would empty a baseline dump of the
            # very artifacts it exists for.
            continue
        lines.append(line(
            "dep",
            entry.group if entry else None,
            entry.name if entry else None,
            entry.version if entry else None,
            project,
            path_of(jar),
        ))
        jars.append(jar)
    return struct(lines = lines, jars = jars)
