"""Public API: `uika_dump` writes a classpath dump, `uika_upgrade_check` compares two."""

load("@rules_java//java:defs.bzl", "java_binary")
load("//private:aspect.bzl", _uika_classpath_aspect = "uika_classpath_aspect")
load("//private:dump.bzl", "uika_classpath_manifest")

uika_classpath_aspect = _uika_classpath_aspect

def _release_value(name, macro, jdk_release):
    """The jdk_release to put on the command line, rejecting what is not a number.

    These are macro parameters rather than typed rule attributes, so Bazel checks nothing
    and `jdk_release = "seventeen"` used to reach the JVM as
    `-Duika.jdkRelease=seventeen`, where `Integer.getInteger` answered its default and the
    release was silently derived instead of overridden. Catching it here fails the load with
    the target named, which is the earliest and clearest point. It does NOT cover a
    hand-written `jvm_flags`, a `--jvmopt` or a `.bazelrc` line setting the property
    directly, nor `@uika//:merge`, which no macro fronts; those still read through
    `Integer.getInteger` and still fall back silently.

    A digit string is accepted and normalized to an int, because `jdk_release = "17"` has
    always worked and Gradle and the Clojure tool both take a numeric string for the same
    knob. Normalizing also settles the base: `format` on an int always writes plain
    decimal, so `"017"` cannot reach the JVM to be read as octal.
    """
    if type(jdk_release) == "int":
        return jdk_release
    if type(jdk_release) == "string":
        digits = jdk_release[1:] if jdk_release.startswith("-") else jdk_release
        if digits.isdigit():
            return int(jdk_release)
    fail("%s(name = %r): jdk_release wants a whole number, got %r" %
         (macro, name, jdk_release))

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
    jdk_release = _release_value(name, "uika_dump", jdk_release)
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
        merged_classpath = False,
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
        A comma in a path is rejected: the list rides one comma-joined property.
      jdk_release: the API release to resolve JDK escapes against. Negative, the default,
        derives it from `targets`; 0 switches the layer off. The dump rule's option folds 0
        into the derived default instead, so the two attrs default differently on purpose.
      merged_classpath: check the union of every module's classpath once instead of each
        module against its own resolution. Per-module checking scans once per module, so a
        large workspace may want the union; the trade is that a break only one module's
        resolution shows can hide behind another module's version of the same jar.
        `--mergedClasspath` and `--noMergedClasspath` override it in either direction, since
        a run-time flag winning over the attribute needs both for a boolean.
      **kwargs: passed through to the generated java_binary (visibility, tags, ...).
    """
    jdk_release = _release_value(name, "uika_upgrade_check", jdk_release)
    # The list rides one comma-joined -D property, so a comma inside a path would be
    # silently split into two bogus paths. Fail loudly instead of encoding, the same
    # decision the manifest makes for tab and newline.
    for exclude_file in exclude_files:
        if "," in exclude_file:
            fail("uika_upgrade_check(name = %r): exclude_files entry %r carries a comma," %
                 (name, exclude_file) +
                 " the -Duika.excludeFiles delimiter; rename the path or pass it at run" +
                 " time via --excludeFile")

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
            "-Duika.mergedClasspath={}".format("true" if merged_classpath else "false"),
        ],
        main_class = "net.exoego.uika.bazel.UpgradeCheckMain",
        runtime_deps = [Label("//java:check")],
        **kwargs
    )
