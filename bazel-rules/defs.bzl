"""Public API: `uika_dump` writes a classpath dump, `uika_upgrade_check` compares two."""

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
        `--materialize <dir>` copies every jar the dump names into `<dir>` and points the
        dump there, which is what keeps a baseline usable after a lockfile change has taken
        the originals away.
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

def uika_upgrade_check(
        name,
        targets = [],
        fail_on = None,
        exclude_files = [],
        jdk_release = -1,
        **kwargs):
    """Declares a `bazel run`-able target that checks a before/after pair of dumps.

    ```console
    $ bazel run //:uika_upgrade_check -- --before /tmp/before.json --after /tmp/after.json
    ```

    Args:
      name: target name.
      targets: the same targets the dump lists. Only their declared API release is read,
        so nothing is built. Leave empty to fall back to the Java toolchain's target
        version, which over-claims for a target that pins a lower release.
      fail_on: never, reachable or any. Omitted leaves the CLI default.
      exclude_files: TOML files of known false positives, as workspace-relative paths.
      jdk_release: the API release to resolve JDK escapes against. Negative, the default,
        derives it from `targets`; 0 switches the layer off. The dump rule's knob folds 0
        into the derived default instead, so the two attrs default differently on purpose.
      **kwargs: passed through to the generated java_binary (visibility, tags, ...).
    """
    releases = name + ".releases"
    uika_classpath_manifest(
        name = releases,
        targets = targets,
        build_outputs = False,
        releases_only = True,
        visibility = ["//visibility:private"],
    )
    java_binary(
        name = name,
        data = [
            ":" + releases,
            Label("@uika_cli//:binary"),
        ],
        jvm_flags = [
            "-Duika.cli=$(rlocationpath {})".format(Label("@uika_cli//:binary")),
            "-Duika.releases=$(rlocationpath :{})".format(releases),
            "-Duika.failOn={}".format(fail_on or ""),
            "-Duika.excludeFiles={}".format(",".join(exclude_files)),
            "-Duika.jdkRelease={}".format(jdk_release),
        ],
        main_class = "net.exoego.uika.bazel.UpgradeCheckMain",
        runtime_deps = [Label("//java:check")],
        **kwargs
    )
