"""The manifest a uika_dump binary reads: one target's classpath per module, attributed."""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":aspect.bzl", "UikaClasspathInfo", "module_name", "uika_classpath_aspect")

# A line-oriented wire format, not JSON, purely so the Java side needs no JSON parser --
# jvm-plugin-core deliberately has none (every other front end borrows its build tool's).
# Fields are tab-separated and belong to the most recent "module" line. Paths are runfiles
# paths; the binary resolves them to real absolute paths at run time, so nothing that is
# cached ever carries an absolute path.
def _line(*fields):
    return "\t".join([f if f else "" for f in fields])

def _runtime_jars(target):
    """Everything on the target's runtime classpath.

    java_library and java_import answer with JavaInfo, but a java_binary's
    transitive_runtime_jars is EMPTY -- its classpath lives in JavaRuntimeClasspathInfo
    instead. Asking JavaInfo alone produced a dump with no artifacts at all and no error.
    """
    if java_common.JavaRuntimeClasspathInfo in target:
        return target[java_common.JavaRuntimeClasspathInfo].runtime_classpath.to_list()
    return target[JavaInfo].transitive_runtime_jars.to_list()

def _manifest_impl(ctx):
    toolchain_release = ctx.attr._java_toolchain[java_common.JavaToolchainInfo].target_version

    lines = []
    runfiles = []
    for target in ctx.attr.targets:
        info = target[UikaClasspathInfo]
        own = {jar.path: None for jar in info.own_jars}
        owners = {entry.path: entry for entry in info.owners.to_list()}

        lines.append(_line("module", module_name(target.label)))
        lines.append(_line("toolchain", toolchain_release))
        for opt in info.javacopts:
            lines.append(_line("javacopt", opt))

        if ctx.attr.build_outputs:
            for jar in info.own_jars:
                lines.append(_line("classes", jar.short_path))
                runfiles.append(jar)

        # The runtime classpath decides WHAT is on it (it already accounts for neverlink and
        # compile-only deps, which the attribute walk alone would not); the aspect only
        # decides WHO produced each jar. A jar with no owner entry still goes in
        # uncoordinated, which is strictly better than dropping it from the scan.
        for jar in _runtime_jars(target):
            if jar.path in own:
                continue
            entry = owners.get(jar.path)
            project = entry.project if entry else None
            if not ctx.attr.build_outputs and not jar.is_source:
                # build_outputs = False means "build nothing", so the jars to leave out are
                # exactly the generated ones. is_source rather than a main-repository test:
                # a vendored jar in the main repository needs no build and carries the
                # coordinates the version diff runs on, so dropping it would empty a
                # baseline dump of the very artifacts it exists for.
                continue
            lines.append(_line(
                "dep",
                entry.group if entry else None,
                entry.name if entry else None,
                entry.version if entry else None,
                project,
                jar.short_path,
            ))
            runfiles.append(jar)

    manifest = ctx.actions.declare_file(ctx.label.name + ".tsv")
    ctx.actions.write(manifest, "\n".join(lines) + "\n")
    return [DefaultInfo(
        files = depset([manifest]),
        runfiles = ctx.runfiles(files = [manifest] + runfiles),
    )]

uika_classpath_manifest = rule(
    implementation = _manifest_impl,
    doc = "Internal: the classpath manifest that a uika_dump binary turns into a dump.",
    attrs = {
        "targets": attr.label_list(
            aspects = [uika_classpath_aspect],
            providers = [JavaInfo],
            doc = "The targets to dump, one module each.",
        ),
        "build_outputs": attr.bool(default = True),
        "_java_toolchain": attr.label(
            default = Label("@rules_java//toolchains:current_java_toolchain"),
        ),
    },
)
