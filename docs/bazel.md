# [Bazel rules](../bazel-rules/)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).
Bazel 7 or newer, with bzlmod. The module comes from the GitHub release rather
than from a registry for now:

```python
# MODULE.bazel
bazel_dep(name = "uika", version = "VERSION_PLACEHOLDER")
archive_override(
    module_name = "uika",
    urls = ["https://github.com/exoego/uika/releases/download/vVERSION_PLACEHOLDER/uika-bazel-VERSION_PLACEHOLDER.tar.gz"],
    strip_prefix = "bazel-rules",
)
```

```python
# BUILD.bazel
load("@uika//:defs.bzl", "uika_dump", "uika_upgrade_check")

UIKA_TARGETS = ["//app", "//service"]

uika_dump(
    name = "uika_dump",
    targets = UIKA_TARGETS,
)

# The baseline the PR gate compares against: it only feeds the version diff, so it
# resolves without building anything.
uika_dump(
    name = "uika_resolution_dump",
    build_outputs = False,
    targets = UIKA_TARGETS,
)

uika_upgrade_check(
    name = "uika_upgrade_check",
    exclude_files = ["uika-exclude.toml"],
    fail_on = "reachable",
    targets = UIKA_TARGETS,
)
```

```console
$ bazel run //:uika_dump -- --output /tmp/after.json
$ bazel run //:uika_resolution_dump -- --output /tmp/before.json
$ bazel run //:uika_upgrade_check -- --before /tmp/before.json --after /tmp/after.json
```

The CLI binary comes from a repository rule, so Bazel's repository cache holds
it, a second run needs no network, and the release archive pins its checksum
for every platform. `UIKA_CLI_PATH` points it at a binary you already have
instead. [`--failOn`](../README.md#violation-tiers-and---fail-on),
[`--excludeFile`](../README.md#excluding-known-false-positives---exclude-file),
`--jdkRelease`, `--classLoadLog` and
`--draftExcludeFile` override the rule's settings on the command line, and a
relative path in any of them resolves against the directory you ran `bazel` from,
not the runfiles tree. The check target repeats `targets` only to read the API
release they compile for, so it builds nothing.

Each entry in `targets` becomes one module of the dump, named by its label, so
`upgrade-check` checks each against its own resolution.

## PR gate on GitHub Actions

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for Bazel. The baseline uses `uika_resolution_dump`, which only
feeds the version diff and so resolves without building anything, and
`--materialize` hard-links the baseline JARs out of `bazel-out`, which keeps
them readable however the tree moves on (see
[What a dump names](#what-a-dump-names)):

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Bazel here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if bazel run //:uika_resolution_dump -- --output /tmp/before.json --materialize /tmp/uika-baseline; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: bazel run //:uika_dump -- --output /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: >
          bazel run //:uika_upgrade_check --
          --before /tmp/before.json --after /tmp/after.json
```

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).

## Runtime load evidence (JFR)

Collect with `bazel test`, check with `--jfr <dir>`. `--jvmopt` already
reaches every test JVM, so nothing has to be injected, and the check target
prints the flag (and creates the directory) so the recipe cannot drift:

```console
$ jvmopt=$(bazel run //:uika_upgrade_check -- jfr-jvmopt /tmp/uika-jfr)
$ bazel test //... --nocache_test_results \
      --sandbox_writable_path=/tmp/uika-jfr "$jvmopt"
$ bazel run //:uika_upgrade_check -- --before /tmp/before.json \
      --after /tmp/after.json --jfr /tmp/uika-jfr
```

`--nocache_test_results` is not optional: a cached test forks no JVM and would
record nothing, with no symptom. `--sandbox_writable_path` is what lets the
recording land outside the sandbox, where the check can read it afterwards.

## Whole-build dumps with the aspect

A rule cannot expand a target pattern, so for a whole-build dump apply the aspect
from the command line instead of listing anything:

```console
$ BIN=$(bazel info bazel-bin)
$ if [ -d "$BIN" ]; then find "$BIN" -name '*.uika-manifest.tsv' -delete; fi
$ bazel build //... --aspects=@uika//:defs.bzl%uika_classpath_aspect \
    --output_groups=uika_dump
$ bazel run @uika//:merge -- --output /tmp/after.json \
    --execroot "$(bazel info execution_root)" --fragments "$BIN"
```

Every Java target the pattern matches becomes a module, so narrow the pattern to
keep the count sane. `upgrade-check` runs once per module, and a bare `//...`
sweeps your test targets and the `uika_dump` targets themselves along with the
code you ship. `kind()` is a query function rather than a target pattern, so
narrowing by rule kind needs a round trip through `bazel query`:

```console
$ bazel build $(bazel query 'kind(java_binary, //...)') \
    --aspects=@uika//:defs.bzl%uika_classpath_aspect --output_groups=uika_dump
```

The two forms do not match the same targets. `bazel build //...` skips anything
tagged `manual` while `bazel query` does not, so a workspace that keeps its
deployables out of CI with that tag sweeps none of them through the first form.

A target carrying a `maven_coordinates` tag is skipped, because it is a
dependency rather than a module of the build under check and it already appears
in the artifact list of everything that uses it. Note that rules_jvm_external
puts that tag on the first-party library `java_export` generates, so a target you
publish yourself is skipped too.

The `find -delete` is part of the recipe rather than tidiness. Fragments live in
`bazel-out` and nothing prunes them, so a target deleted since the last sweep
would otherwise still contribute its module. Guard it on the directory existing,
since `bazel info` prints `bazel-bin` without creating it and `find` fails on a
fresh output base.

Pass the same configuration flags to `bazel info` that the sweep build used.
Fragments land in the configuration's own `bazel-out/<config>/bin`, so a `-c opt`
sweep read back through a bare `bazel info bazel-bin` either finds nothing or
merges an older configuration's fragments.

`--materialize` works the same way here. The merge is a separate command from the
sweep build because it needs the execution root, which the recipe reads with
`bazel info` and passes in. `@uika//:merge` is itself an ordinary `bazel run`
target.

## Coordinates and `jdk_release`

Coordinates come from the `maven_coordinates=group:artifact:version` tag that
rules_jvm_external puts on every `jvm_import` it generates — the same tag its own
`java_export` and `pom_file` read. Nothing here is specific to rules_jvm_external,
so a hand-written `java_import` carrying that tag is attributed just as well, and a
target of your own build is recorded by label the way the other tools record a
project dependency. [`--jdk-release`](../README.md#build-tool-plugins) is
derived per target from its `javacopts`, falling back to the Java toolchain's
target version, and `jdk_release = N` on the rule overrides every module.

## What a dump names

Two Bazel-specific things to know. The dump is written by a `bazel run`, never by
a build action, because it names absolute paths and an action's output is
cacheable. The sweep does write its per-target fragments from an action, but a
fragment names paths relative to the execution root and only the merge turns them
absolute. Which paths those are depends on the route. A `bazel run` lays the
classpath out as runfiles and resolves those symlinks, while the merge resolves
against the execution root, and both end at the same real file.

And a dump names JARs under `bazel-out`, which is build output rather than
source. They survive a lockfile change in the same tree, so the checkout-based PR
gate works unchanged. They do not survive a `bazel clean`, a fresh output
base, or another machine. That last case is the baseline-as-artifact flow, and
there the check does not degrade quietly. It fails with `cannot open ...` and
exit 2, because the changed pair's old JAR is what the API diff is computed
against, and only a scan target is skipped with a warning. `--materialize <dir>`
is the answer. It hard-links every JAR the dump names into one directory and
points the dump there, which takes the baseline out of `bazel-out` and makes it
portable to another machine. See [BASELINE-CACHING.md](../BASELINE-CACHING.md).
