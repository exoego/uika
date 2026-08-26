"""The manifest a uika_dump binary reads: one target's classpath per module, attributed."""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":aspect.bzl", "uika_classpath_aspect")
load(
    ":manifest.bzl",
    "UikaClasspathInfo",
    "line",
    "module_records",
    "short_path",
)

def _manifest_impl(ctx):
    toolchain_release = ctx.attr._java_toolchain[java_common.JavaToolchainInfo].target_version

    lines = []
    runfiles = []
    if ctx.attr.releases_only and not ctx.attr.targets:
        # Nothing to read a release from, so the toolchain's is the whole answer. Written as
        # a module of its own because that is all the parser on the other side understands.
        lines.append(line("module", "@java_toolchain"))
        lines.append(line("toolchain", toolchain_release))
    for target in ctx.attr.targets:
        records = module_records(
            target = target,
            info = target[UikaClasspathInfo],
            toolchain_release = toolchain_release,
            path_of = short_path,
            build_outputs = ctx.attr.build_outputs,
            releases_only = ctx.attr.releases_only,
        )
        lines.extend(records.lines)
        runfiles.extend(records.jars)

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
        "releases_only": attr.bool(default = False),
        "_java_toolchain": attr.label(
            default = Label("@rules_java//toolchains:current_java_toolchain"),
        ),
    },
)
