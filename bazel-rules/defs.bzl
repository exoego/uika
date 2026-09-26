"""Public API: the `uika` macro, and the aspect behind the `//...` sweep."""

load("@rules_java//java:defs.bzl", "java_binary")
load("//private:aspect.bzl", _uika_classpath_aspect = "uika_classpath_aspect")
load("//private:dump.bzl", "uika_classpath_manifest")

uika_classpath_aspect = _uika_classpath_aspect

def _release_value(name, jdk_release):
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
    if jdk_release == None:
        return -1
    if type(jdk_release) == "int":
        return jdk_release
    if type(jdk_release) == "string":
        digits = jdk_release[1:] if jdk_release.startswith("-") else jdk_release
        if digits.isdigit():
            return int(jdk_release)
    fail("uika(name = %r): jdk_release wants a whole number, got %r" % (name, jdk_release))


def uika(
        name,
        targets = [],
        jdk_release = None,
        fail_on = None,
        exclude_files = [],
        merged_classpath = False,
        **kwargs):
    """Declares the `bazel run`-able targets that dump classpaths and check a pair of dumps.

    ```console
    $ bazel run //:uika_dump -- --output /tmp/after.json
    $ bazel run //:uika_baseline_dump -- --output /tmp/before.json
    $ bazel run //:uika_check -- --before /tmp/before.json --after /tmp/after.json
    ```

    Targets, for `name = "uika"`:

    - `:uika_dump` writes the dump of `targets`. Each entry becomes one module, named by
      its label, so the check can check each against its own resolution.
    - `:uika_baseline_dump` writes the same dump without building `targets`. It is the
      baseline of a PR gate, which only feeds the version diff, so it builds none of your
      code and still records your Maven dependencies, rules_jvm_external's included.
    - `:uika_check` runs `uika upgrade-check` over `--before` and `--after`.

    Both dumps take `--output <path>`, and `--materialize <dir>`, which copies every jar
    the dump names into `<dir>` and points the dump there. That keeps a baseline usable
    after a lockfile change has taken the originals away.

    Args:
      name: prefix of the declared targets.
      targets: the Java targets to dump, one module each. Leave empty for the `//...`
        sweep, which dumps through the aspect and `@uika//:merge`. Then only the check is
        declared, and it reads the release from the Java toolchain.
      jdk_release: the release your build runs on. The dumps record it as every module's
        release, and the check resolves JDK references against it. Omitted derives it from
        what `targets` compile for. 0 switches the check's JDK API layer off and leaves the
        dumps' release derived, so a JDK move is still detected.
      fail_on: never, reachable or any. Omitted leaves the CLI default.
      exclude_files: TOML files of known false positives, as workspace-relative paths.
        A comma in a path is rejected: the list rides one comma-joined property.
      merged_classpath: check the union of every module's classpath once instead of each
        module against its own resolution. Per-module checking scans once per module, so a
        large workspace may want the union; the trade is that a break only one module's
        resolution shows can hide behind another module's version of the same jar.
        `--mergedClasspath` and `--noMergedClasspath` override it in either direction, since
        a run-time flag winning over the attribute needs both for a boolean.
      **kwargs: passed through to every generated java_binary (visibility, tags, ...).
    """
    release = _release_value(name, jdk_release)

    # One value for both sides: the dump reads 0 and below as "derive", the check reads 0 as
    # the off switch and below 0 as "derive".
    release_flag = "-Duika.jdkRelease={}".format(release)

    # The list rides one comma-joined -D property, so a comma inside a path would be
    # silently split into two bogus paths. Fail loudly instead of encoding, the same
    # decision the manifest makes for tab and newline.
    for exclude_file in exclude_files:
        if "," in exclude_file:
            fail("uika(name = %r): exclude_files entry %r carries a comma," %
                 (name, exclude_file) +
                 " the -Duika.excludeFiles delimiter; rename the path or pass it at run" +
                 " time via --excludeFile")

    # An empty dump would read every dependency as added and pass the check, so without
    # targets no dump is declared at all.
    if targets:
        for suffix, build_outputs in [("_dump", True), ("_baseline_dump", False)]:
            manifest = name + suffix + ".manifest"
            uika_classpath_manifest(
                name = manifest,
                targets = targets,
                build_outputs = build_outputs,
                visibility = ["//visibility:private"],
            )
            java_binary(
                name = name + suffix,
                data = [":" + manifest],
                jvm_flags = [
                    "-Duika.manifest=$(rlocationpath :{})".format(manifest),
                    release_flag,
                ],
                main_class = "net.exoego.uika.bazel.DumpMain",
                runtime_deps = [Label("//java:dump")],
                **kwargs
            )

    releases = name + "_check.releases"
    uika_classpath_manifest(
        name = releases,
        targets = targets,
        build_outputs = False,
        releases_only = True,
        visibility = ["//visibility:private"],
    )
    java_binary(
        name = name + "_check",
        data = [
            ":" + releases,
            Label("@uika_cli//:cli"),
        ],
        jvm_flags = [
            "-Duika.cli=$(rlocationpath {})".format(Label("@uika_cli//:cli")),
            "-Duika.releases=$(rlocationpath :{})".format(releases),
            "-Duika.failOn={}".format(fail_on or ""),
            "-Duika.excludeFiles={}".format(",".join(exclude_files)),
            release_flag,
            "-Duika.mergedClasspath={}".format("true" if merged_classpath else "false"),
        ],
        main_class = "net.exoego.uika.bazel.UpgradeCheckMain",
        runtime_deps = [Label("//java:check")],
        **kwargs
    )
