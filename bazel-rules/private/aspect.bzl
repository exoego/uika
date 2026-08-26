"""Collects a Java target's runtime classpath together with each jar's Maven coordinates.

Coordinates are not carried by any provider. rules_jvm_external puts them on the
`jvm_import` targets it generates as `tags = ["maven_coordinates=group:artifact:version"]`,
which is what its own `pom_file`/`java_export` read as well. So this aspect reads a TAG
CONVENTION rather than a rules_jvm_external API: any target carrying the tag is attributed,
whoever declared it, and the integration test needs no artifact resolution at all.
"""

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

_TAG_PREFIX = "maven_coordinates="

def module_name(label):
    """`//app:app`, not the `@@//app:app` that str() gives since Bazel 7.

    upgrade-check pairs modules between two dumps by this string, and matches an artifact's
    `project` attribution against it, so both have to be the stable spelling a user types.
    """
    if label.workspace_name:
        return str(label)
    return "//{}:{}".format(label.package, label.name)

# Attributes a runtime classpath can flow along. `exports` matters because a java_library
# that only re-exports puts nothing of its own on the classpath.
_ASPECT_ATTRS = ["deps", "runtime_deps", "exports"]

def _coordinates(tags):
    """group/name/version from the first maven_coordinates tag, or (None, None, None).

    rules_jvm_external writes `group:artifact:version`, `group:artifact:packaging:version`
    or `group:artifact:packaging:classifier:version`. The dump records only the first
    three, like every other uika front end -- a classifier is recoverable from the file
    name and is not part of the coordinate the version diff keys on.
    """
    for tag in tags:
        if not tag.startswith(_TAG_PREFIX):
            continue
        parts = tag[len(_TAG_PREFIX):].split(":")
        if len(parts) == 3:
            return (parts[0], parts[1], parts[2])
        if len(parts) == 4:
            return (parts[0], parts[1], parts[3])
        if len(parts) == 5:
            return (parts[0], parts[1], parts[4])
    return (None, None, None)

def _own_jars(target):
    """The jars this target itself contributes, never its dependencies'.

    Direct rather than transitive so every jar maps to exactly one owner. java_binary does
    not always populate runtime_output_jars, hence the DefaultInfo fallback; the deploy jar
    is skipped because it repackages the whole closure and would be scanned twice.
    """
    jars = list(target[JavaInfo].runtime_output_jars)
    if jars:
        return jars
    return [
        f
        for f in target[DefaultInfo].files.to_list()
        if f.extension == "jar" and not f.basename.endswith("_deploy.jar")
    ]

def _aspect_impl(target, ctx):
    if JavaInfo not in target:
        return []

    transitive = []
    for attr_name in _ASPECT_ATTRS:
        for dep in getattr(ctx.rule.attr, attr_name, []):
            if UikaClasspathInfo in dep:
                transitive.append(dep[UikaClasspathInfo].owners)

    group, name, version = _coordinates(ctx.rule.attr.tags)

    # No coordinates and in the main repository means a target of the build under check.
    # Attributing it by label is what lets uika substitute that module's own outputs when
    # the jar has not been built, the same attribution Gradle and Maven do with "project".
    project = None
    if group == None and target.label.workspace_name == "":
        project = module_name(target.label)

    own_jars = _own_jars(target)
    direct = [
        struct(
            path = jar.path,
            short_path = jar.short_path,
            group = group,
            name = name,
            version = version,
            project = project,
        )
        for jar in own_jars
    ]

    return [UikaClasspathInfo(
        owners = depset(direct = direct, transitive = transitive),
        own_jars = own_jars,
        javacopts = list(getattr(ctx.rule.attr, "javacopts", [])),
    )]

uika_classpath_aspect = aspect(
    implementation = _aspect_impl,
    attr_aspects = _ASPECT_ATTRS,
    provides = [UikaClasspathInfo],
    doc = "Attributes every jar on a Java target's classpath to the target that produced it.",
)
