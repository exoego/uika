"""Public API: `uika_dump` writes a uika classpath dump for a set of Java targets."""

load("@rules_java//java:defs.bzl", "java_binary")
load("//private:aspect.bzl", _uika_classpath_aspect = "uika_classpath_aspect")
load("//private:dump.bzl", "uika_classpath_manifest")

uika_classpath_aspect = _uika_classpath_aspect

def uika_dump(name, targets, build_outputs = True, jdk_release = 0, **kwargs):
    """Declares a `bazel run`-able target that dumps `targets`' resolved classpaths.

    Each entry in `targets` becomes one module of the dump, named by its label, so
    `uika upgrade-check` can check each against its own resolution.

    Args:
      name: target name. `bazel run //:<name> -- --output <path>` writes the dump.
      targets: the Java targets to dump, one module each.
      build_outputs: build and record the targets' own jars. Set False for the baseline
        dump of a PR gate: it only feeds the version diff, so the sibling targets need not
        be built, and the external jars it does need are recorded either way.
      jdk_release: the API release to record every module as running on, for a build whose
        runtime is not what it compiles against. 0 keeps the derived value.
      **kwargs: passed through to the generated java_binary (visibility, tags, ...).
    """
    manifest = name + ".manifest"
    uika_classpath_manifest(
        name = manifest,
        targets = targets,
        build_outputs = build_outputs,
        visibility = ["//visibility:private"],
    )
    java_binary(
        name = name,
        data = [":" + manifest],
        jvm_flags = [
            "-Duika.manifest=$(rlocationpath :{})".format(manifest),
            "-Duika.jdkRelease={}".format(jdk_release),
        ],
        main_class = "net.exoego.uika.bazel.DumpMain",
        runtime_deps = [Label("//java:dump")],
        **kwargs
    )
