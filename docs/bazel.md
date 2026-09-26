# [Bazel rules](../bazel-rules/)

One of uika's [build-tool integrations](build-tools.md).
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
load("@uika//:defs.bzl", "uika")

uika(
    name = "uika",
    exclude_files = ["uika-exclude.toml"],
    fail_on = "reachable",
    targets = ["//app", "//service"],
)
```

The `uika` macro declares three targets that share these settings. `:uika_dump`
dumps the classpath of `targets`. `:uika_baseline_dump` writes the same dump
without building `targets`, which is the baseline the PR gate compares against.
It only feeds the version diff, so it builds none of your code.
`:uika_check` compares two dumps.

```console
$ bazel run //:uika_dump -- --output /tmp/after.json
$ bazel run //:uika_baseline_dump -- --output /tmp/before.json
$ bazel run //:uika_check -- --before /tmp/before.json --after /tmp/after.json
```

The CLI is a jar that comes from a repository rule, so Bazel's repository cache
holds it, a second run needs no network, and the release archive pins its
checksum. The check and dump targets run on the Java runtime Bazel gives them
(`--java_runtime_version`, the local JDK by default), which has to be Java 17 or
newer. `UIKA_CLI_PATH` runs a CLI you already have instead, the jar or an
executable that runs it, and a value that is not a file, or an executable that
lost its bit, fails naming the variable. The check target reads `targets` only
for the API release they compile for, so it builds nothing.

The `uika.cli` module-extension tag overrides where the jar comes from.
That is the pin for every case the release archive's checksum cannot
serve — the rules at a git revision, a different CLI version, a mirror — and
the unpinned-download warning prints the hash ready to paste:

```python
# MODULE.bazel
uika = use_extension("@uika//:extensions.bzl", "uika")
uika.cli(
    version = "VERSION_PLACEHOLDER",
    sha256 = "<hash from the unpinned-download warning>",
    # repository = "https://my.mirror/maven2",  # Maven repository base URL
)
use_repo(uika, "uika_cli")
```

Each entry in `targets` becomes one module of the dump, named by its label, so
`upgrade-check` checks each against its own resolution.

## PR gate on GitHub Actions

The `linkage-check` job dumps a baseline from the PR's base branch and the
PR's own classpath, and fails on broken references between the two. The
`dump-baseline` job and the marked steps are the optional caching half,
explained in [Caching the baseline](#caching-the-baseline).

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - 'MODULE.bazel'
      - 'MODULE.bazel.lock'
      - '**/*_install.json'
      - .github/workflows/linkage-check.yml
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

# a PR update supersedes the running check, and baseline dumps get per-SHA
# groups so a develop push never cancels one
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.sha }}
  cancel-in-progress: true

jobs:
  # Optional: dumps the baseline once per push so the PR job can fetch it
  # instead of resolving the base branch. To opt out, delete this job, the
  # push and workflow_dispatch triggers, and the marked step in
  # linkage-check.
  dump-baseline:
    if: github.event_name != 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Bazel here ....

      # this target builds none of your code
      - run: |
          bazel run //:uika_baseline_dump -- \
            --output /tmp/uika-baseline/classpath.json \
            --materialize /tmp/uika-baseline/jars

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/uika-baseline
          retention-days: 30   # a PR's base.sha is always a recent tip

  linkage-check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Bazel here ....

      # Cached-baseline fast path. This step skips while no artifact
      # exists. Delete it together with the dump-baseline job if you do not
      # cache.
      - name: Fetch baseline artifact
        id: baseline
        # the artifact carries the baseline JARs themselves, so only
        # same-repo PRs take the fast path
        if: github.event.pull_request.head.repo.full_name == github.repository
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          # To look up the artifact from another run (the develop push)
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/uika-baseline

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.baseline.outcome != 'success'
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if bazel run //:uika_baseline_dump -- \
               --output /tmp/uika-baseline/classpath.json \
               --materialize /tmp/uika-baseline/jars; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: bazel run //:uika_dump -- --output /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: >
          bazel run //:uika_check --
          --before /tmp/uika-baseline/classpath.json --after /tmp/after.json
```

### Caching the baseline

The fallback resolves the base branch on the PR runner, which puts a
second checkout and a cold Bazel start on the PR's critical path every time.
The baseline only feeds the version diff, so the `dump-baseline` job
produces it once per push instead. The fallback stays for SHAs with no
usable baseline. Those are SHAs predating the job, expired artifacts, and
PRs not targeting `develop`. Deleting the marked blocks is also safe,
because an `if:` reads a missing step's outcome as empty, never as
`success`.

Bazel has no shared dependency cache to restore, so `--materialize` puts
the JARs into the artifact itself, and the materialized directory has to
land at the same absolute path the baseline run wrote it to. Both jobs use
`/tmp/uika-baseline` for that reason. A missing old-side JAR exits 2
rather than degrading to a warning, as
[What a dump names](#what-a-dump-names) explains.

## Options

Every option is a parameter of the `uika` macro, a run-time flag, or both. A
run-time flag wins over the parameter of the same name, except `--excludeFile`,
which appends to `exclude_files`. A relative path in any of them resolves against the
workspace root (`BUILD_WORKSPACE_DIRECTORY`), wherever you ran `bazel` from,
and never against the runfiles tree.

- `targets` lists the Java targets to check, one module each. Leave it empty
  when you dump with the [aspect](#whole-build-dumps-with-the-aspect). The macro
  then declares only `:<name>_check`, which reads the release from the Java
  toolchain.
- [`fail_on`](../README.md#violation-tiers-and-the-failon-threshold) is `never`,
  `reachable` or `any`, and `--failOn` overrides it.
- [`exclude_files`](../README.md#excluding-known-false-positives)
  takes file paths from the workspace root, not labels, even in a BUILD file
  below the root. The repeatable `--excludeFile` adds to it.
- [`jdk_release`](#coordinates-and-jdk_release) is derived per target, and
  `--jdkRelease` overrides it on all three targets and on `@uika//:merge`.
- [`merged_classpath`](../README.md#per-module-checking) checks the union of
  every target's classpath once instead of each against its own resolution.
  Per-module checking scans once per target, so a large workspace may want the
  union; the trade is that a break only one target's resolution shows can hide
  behind another target's version of the same jar. `--mergedClasspath` and
  `--noMergedClasspath` override it in either direction, since a boolean needs
  both for a run-time flag to win over the attribute.
- `--classLoadLog` (repeatable) and `--jfr` supply
  [runtime load evidence](#runtime-load-evidence-jfr). `--draftExcludeFile` is
  where rules drafted from it are written. None of the three has an attribute,
  so they are passed at run time only.
- `--materialize <dir>` on a dump puts every JAR it names into one directory and
  points the dump there, which is what makes a baseline portable to another
  machine. It hard-links where the filesystem allows it, so the common case
  costs no space, and copies where it does not, such as a destination on
  another filesystem. See [What a dump names](#what-a-dump-names).

## Runtime load evidence (JFR)

Collect with `bazel test`, check with `--jfr <dir>`. The check target prints
the `--jvmopt` flag and creates the directory:

```console
$ jvmopt=$(bazel run //:uika_check -- jfr-jvmopt /tmp/uika-jfr)
$ bazel test //... --nocache_test_results \
      --sandbox_writable_path=/tmp/uika-jfr "$jvmopt"
$ bazel run //:uika_check -- --before /tmp/before.json \
      --after /tmp/after.json --jfr /tmp/uika-jfr
```

`--nocache_test_results` is not optional: a cached test forks no JVM and would
record nothing, with no symptom. `--sandbox_writable_path` is what lets the
recording land outside the sandbox, where the check can read it afterwards.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.

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
sweeps your test targets and the `uika` macro's targets along with the
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

`--materialize` works on `@uika//:merge` the same way.

There is no baseline dump for a sweep, so a baseline taken this way builds
everything the pattern matches. List the targets in the `uika` macro and use
`:<name>_baseline_dump` when that build cost matters. A sweep still needs the
check target, so declare the macro without `targets`:

```python
uika(name = "uika", fail_on = "reachable")
```

## Coordinates and `jdk_release`

Coordinates come from the `maven_coordinates=group:artifact:version` tag that
rules_jvm_external puts on every `jvm_import` it generates — the same tag its own
`java_export` and `pom_file` read. Nothing here is specific to rules_jvm_external,
so a hand-written `java_import` carrying that tag is attributed just as well, and a
target of your own build is recorded by label the way the other tools record a
project dependency. [`jdkRelease`](build-tools.md#jdkrelease) is
derived per target from its `javacopts`, falling back to the Java toolchain's
target version, and `jdk_release = N` on the macro overrides every module.

`jdk_release = N` states the release your build runs on, for a build whose
runtime is not what it compiles against. The one value reaches all three
targets. Both dumps record it as every module's release, and the check
resolves JDK references against it. `jdk_release = 0` switches the check's JDK
API layer off. The dumps then keep the derived release, so `upgrade-check`
still sees a JDK move between two dumps. Every target also takes `--jdkRelease`
at run time, `@uika//:merge` included.

## What a dump names

A dump names JARs by absolute path under `bazel-out`, which is build output
rather than source. They survive a lockfile change in the same tree, so the
checkout-based PR gate works unchanged. They do not survive a `bazel clean`, a
fresh output base, or another machine. That last case is the
baseline-as-artifact flow, and there the check does not degrade quietly. It
fails with `cannot open ...` and exit 2, because the changed pair's old JAR is
what the API diff is computed against, and only a scan target is skipped with a
warning. `--materialize <dir>` is the answer. It hard-links every JAR the dump
names into one directory and points the dump there, which takes the baseline out
of `bazel-out` and makes it portable to another machine. See [Caching the
baseline](#caching-the-baseline).
