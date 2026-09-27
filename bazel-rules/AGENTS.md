# Notes for Agents: bazel-rules/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

## Bazel Rules Notes

Point-of-use comments and `bazel-rules/it/` hold the invariants. Only what neither can
hold lives here.

- The dump is written by `bazel run`, NEVER by a build action. It names absolute paths,
  and an action's output is cacheable and may be replayed into another output base or
  served from a remote cache, so an action that wrote one would hand a second machine
  another machine's paths. `bazel run` lays the classpath out as runfiles, and
  `toRealPath` on those symlinks is where the absolute paths come from, with no
  `bazel info execution_root` to guess at. The sweep is the one exception, and it stays
  within the rule. Its fragments ARE written by an action, but they name execution-root
  relative paths and only the merge turns them absolute.
- Coordinates come from the `maven_coordinates=` TAG, not from a provider, because
  rules_jvm_external exposes none. That is deliberate rather than a workaround: the same
  tag is what its own `java_export` and `pom_file` read, so anything carrying it is
  attributed and the integration test needs no resolver and no network.
- A `java_binary`'s `JavaInfo.transitive_runtime_jars` is EMPTY. Its classpath is in
  `JavaRuntimeClasspathInfo` instead. The symptom is a dump with no artifacts at all and
  no error, so `_runtime_jars` in `private/manifest.bzl` checks that provider first.
- `build_outputs = False` skips a GENERATED jar only when it is the main repository's or
  carries no coordinates. rules_jvm_external's jars are generated too, a `processed_` jar
  when `jvm_import` stamps manifests (the default) and a `copy_file` output when it does
  not. An `is_source` test alone therefore dropped every Maven artifact from the baseline,
  and the check read them all as ADDED and exited 0. A main-repository test alone would do
  the same to a vendored jar, which is a source file. `it/run-maven.sh` pins the first case
  and `it/run.sh` the second. Keeping the Maven jars costs one stamping action per jar plus
  a build of rules_jvm_external's stamping tool, and no Javac for the workspace's targets
  (execution log on 9.2). With stamping off it costs nothing.
- The release derivation is single-sourced through the SAME manifest rule the dump uses,
  in a `releases_only` mode. Reading only the Java toolchain would be shorter and wrong,
  because a target that pins a lower release than the toolchain would then be
  over-claimed, and over-claiming drops findings silently.
- `javacopts = ["--release", "17"]` on the ruleset's own java_library targets is not
  decoration. Bazel's default `--java_language_version` is 11, so without it the core's
  arrow switches do not compile in a user's workspace. Bazel strips the toolchain's
  `-source`/`-target` when javacopts name `--release`, so the two do not conflict.
- Both integration tests clean the output base before asserting that
  `build_outputs = False` built none of the workspace's targets. The workspace copy is
  recreated per run but its output base is keyed by that stable path and is not, so the
  assertion would otherwise read the jars the previous run left behind and pass
  unconditionally.
- JFR collection needs `--nocache_test_results`: a cached test forks no JVM and records
  nothing, with no symptom. It also needs `--sandbox_writable_path`, since the recording
  lands outside the sandbox on purpose. The `jfr-jvmopt` subcommand prints the flag AND
  creates the directory, so the `docs/bazel.md` recipe cannot drift from
  `UikaCli.jfrClassLoadJvmArg` the way Maven's hand-written argLine can.
- Coverage is ONE report: a JaCoCo agent riding every JVM `it/run.sh` starts, the unit
  suite included (`bazel test` in the IT workspace, unsandboxed, uncached; run.sh says why
  each). Do not upload the `bazel coverage` lcov beside it. Codecov merges the pair per
  line and shows every branch line the IT covers only partly as a miss. The report skips
  every jar whose name carries a dash: `libcore-hjar.jar` and `-tjar.jar` are header jars
  with the method bodies stripped, and JaCoCo fails the whole report on the first one.
- The release archive is cut with `cp -RL` (`bazel-rules/stage.sh`, via `make
  bazel-stage`). The four jvm-plugin-core sources under `bazel-rules/java` are committed
  symlinks pointing OUT of the module root, which is fine in this repository and useless
  to a consumer. `it/` is dropped from the archive because its `local_path_override`
  points back here.
- `bazel-stage` stamps the sha256 of the CLI jar into `private/checksums.bzl`, so a
  released archive pins the uika-cli download. The hash must be of the very file that goes
  to Central. In a release the uika-cli staging step builds `cli-java/build/libs` first,
  and `bazel-stage`'s own `java-cli-build` then finds it up to date and leaves it alone.
  `stage.sh` fails without the jar instead of cutting an unpinned archive. Consuming the
  rules at a git revision leaves the value empty and the download unpinned, which the
  `uika.cli` tag's `sha256` closes.
- The pin is ONE string, not a classifier map: one jar serves every host. `UIKA_CLI_PATH`
  at fetch time is symlinked under a name that keeps a `.jar` suffix, because the suffix
  is how `UikaCli` tells the jar from an executable.
- Bazel says NOTHING about an unpinned `download`. The familiar "canonical
  reproducible form" note comes from `http_archive`, which reports it by hand, so a bare
  repository rule that stays quiet leaves the download silently unverified. That is why
  `cli_repository.bzl` prints its own message, carrying the hash it just downloaded so the
  pin is paste-ready. Verified on a cold fetch with an empty `--repository_cache`; do not
  assume Bazel covers this.
- `private/checksums.bzl` describes UIKA_VERSION's jar and NOTHING else, so
  `extensions.bzl` drops the pin whenever a `uika.cli(version = ...)` tag names a
  different one. Carrying it over verified the requested version against another
  version's hash and failed the fetch outright, which made a released archive plus any
  version override unusable. The repository rule's `sha256` attr deliberately has no
  default, so that decision lives in the one place that knows both the version and the
  pin. No test reaches this: the integration test consumes the rules at a git revision,
  where the pin is empty by construction, so the trap only exists in a released archive.
- ONE public macro, `uika`, declares `<name>_dump`, `<name>_baseline_dump` and
  `<name>_check`. It replaced `uika_dump` and `uika_upgrade_check`, whose `targets` and
  `jdk_release` had to be repeated and could drift, so a stated runtime release reached the
  dump but not the check. Both binaries get the same `-Duika.jdkRelease`, and the value
  works for both because they read it differently: the dump folds anything below
  `MIN_RELEASE` into "derived", the check reads 0 as "layer off" and a negative as "derive".
  The macro's default is -1. Without `targets` no dump is declared, since an empty dump
  reads every dependency as added and passes the check. That no-targets shape is what the
  `//...` sweep pairs with.
- No duplicate-target guard is needed on the `uika` macro. Bazel rejects a repeated label in a
  `label_list` itself ("Label '//app:app' is duplicated in the 'targets' attribute"), before
  the rule implementation runs, and two distinct labels cannot produce one module name. A
  guard was written and then removed once that was checked.
- The manifest fails the build on a field carrying a tab or a newline instead of escaping
  it. A codec on both sides of the wire would exist only to hide a corrupt manifest that
  the Java side would mis-parse into the wrong module.
- The `//...` sweep and the `uika` dump targets share `private/manifest.bzl` so one code path
  decides what a module is. They differ only in which path names each jar, which `path_of`
  selects. The rule resolves runfiles (`short_path`), while the sweep has no runfiles tree
  and its merge step prefixes `bazel info execution_root` (`path`). `@uika//:merge` IS an
  ordinary `bazel run` target. What stays outside it is the `bazel info`, whose value the
  recipe passes in, so the tool needs no `bazel` on its path and the caller can carry the
  configuration flags its sweep build used.
- Bazel REPLANTS the execution root's `external/` symlink forest on every invocation,
  keeping the repositories that invocation needs and pruning the rest, so `bazel run
  @uika//:merge` prunes repositories the sweep build had. The output base holds those
  repositories itself and is never replanted, which is why `Manifest.resolveExecroot` falls
  back to it. What this reaches is a jar that is a SOURCE file in an external module, such
  as a `java_import` of a checked-in jar in a module you depend on, whose fragment path is a
  bare `external/<repo>/...`. rules_jvm_external is NOT affected, because its processed jars
  sit at `bazel-out/<cfg>/bin/external/<repo>/...` and the execroot's `bazel-out` is a real
  directory rather than part of the forest. Measured end to end on 9.2.0 with a
  `local_path_override` module exporting a checked-in jar. Neither `it/test-workspace` nor
  `it/maven-workspace` produces a bare `external/` path, so neither can reach it.
- The aspect must NOT declare `provides = [UikaClasspathInfo]`. It returns nothing for a
  target without JavaInfo, Bazel enforces an advertisement, and a sweep over `//...` aborts
  at the first non-Java target. Nothing depended on it, because the rule attribute filters
  on JavaInfo itself.
- The sweep's jars ride in the `uika_dump` output group alongside the fragment.
  `--output_groups` REPLACES the default outputs, so without them the sweep writes a
  manifest naming jars the build was never asked to produce.
- Sweep fragments live in bazel-out and nothing prunes them, so the documented recipe
  deletes `*.uika-manifest.tsv` first. A target deleted since the last sweep would otherwise
  still contribute its module, which is a wrong dump rather than a stale one.
- A dump names jars under `bazel-out`, which is BUILD OUTPUT rather than source. They
  survive a lock file change in the same tree, so the checkout-based PR gate needs no
  `--materialize`, but they do not survive `bazel clean`, a fresh output base or another
  machine, which is the baseline-as-artifact flow. There the check FAILS, exit 2 with
  "cannot open ...", because the changed pair's old jar is what the API diff is computed
  against and there is no partial answer to fall back to. Measured in `it/run-maven.sh`,
  which asserts both halves. An earlier claim here said Bazel discards the external
  repository on a lock file change and that the symptom is quieter findings. Both were
  wrong, and rules_jvm_external 7.x stores version-addressed processed jars under
  bazel-out, so nothing is overwritten.
- `it/maven-workspace/` is the only place a real `maven.install` runs, so it is what proves
  the aspect reads what rules_jvm_external actually emits rather than a tag shape written
  by hand. It needs the network, hence its own `make bazel-maven-test`. The lock files are
  committed and pinned, and guava needs `force_version` because selenium-remote-driver
  3.4.0 asks for it with an OPEN-ENDED range that otherwise resolves past both versions
  under test and leaves the version diff nothing to see.
- The integration test never downloads the CLI (`UIKA_CLI_PATH` short-circuits both the
  repository rule and the run), which keeps it hermetic and off Maven Central, so the
  download path is covered by hand instead. Last checked against the published 0.8.0:
  unpinned prints the pin, a correct pin is silent, a wrong one fails the fetch.
