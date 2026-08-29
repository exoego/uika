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
instead. The check target repeats `targets` only to read the API release they
compile for, so it builds nothing.

The `uika.cli` module-extension tag overrides where the binary comes from.
That is the pin for every case the release archive's checksum map cannot
serve — the rules at a git revision, a different CLI version, a mirror — and
the unpinned-download warning prints the hash ready to paste:

```python
# MODULE.bazel
uika = use_extension("@uika//:extensions.bzl", "uika")
uika.cli(
    version = "VERSION_PLACEHOLDER",
    sha256 = {"linux-x86_64": "<hash from the unpinned-download warning>"},
    # repository = "https://my.mirror/maven2",  # Maven repository base URL
)
use_repo(uika, "uika_cli")
```

Each entry in `targets` becomes one module of the dump, named by its label, so
`upgrade-check` checks each against its own resolution.

## Options

Every option is a rule attribute, a run-time flag, or both. A run-time flag
wins over the attribute of the same name, except `--excludeFile`, which appends
to `exclude_files`. A relative path in any of them resolves against the
workspace root (`BUILD_WORKSPACE_DIRECTORY`), wherever you ran `bazel` from,
and never against the runfiles tree.

- [`fail_on`](../README.md#violation-tiers-and-the-failon-threshold) is `never`,
  `reachable` or `any`, and `--failOn` overrides it.
- [`exclude_files`](../README.md#excluding-known-false-positives)
  is a label list, and the repeatable `--excludeFile` adds to it.
- [`jdk_release`](#coordinates-and-jdk_release) is derived per target, and
  `--jdkRelease` overrides it on both rules and on `@uika//:merge`.
- `--classLoadLog` (repeatable) and `--jfr` supply
  [runtime load evidence](#runtime-load-evidence-jfr). `--draftExcludeFile` is
  where rules drafted from it are written. None of the three has an attribute,
  so they are passed at run time only.
- `--materialize <dir>` on a dump puts every JAR it names into one directory and
  points the dump there, which is what makes a baseline portable to another
  machine. It hard-links where the filesystem allows it, so the common case
  costs no space, and copies where it does not, such as a destination on
  another filesystem. See [What a dump names](#what-a-dump-names).

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

      # build_outputs = False on this target, so it resolves without building anything
      - run: |
          bazel run //:uika_resolution_dump -- \
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
          if bazel run //:uika_resolution_dump -- \
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
          bazel run //:uika_upgrade_check --
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
rather than degrading to a warning, which
[What a dump names](#what-a-dump-names) explains and
`bazel-rules/it/run-maven.sh` asserts.

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
project dependency. [`jdkRelease`](../README.md#build-tool-plugins) is
derived per target from its `javacopts`, falling back to the Java toolchain's
target version, and `jdk_release = N` on the rule overrides every module.

`jdk_release` sits on both rules and the two default differently on purpose. On
`uika_dump` it defaults to 0, which folds into the derived value, since 0 there
would otherwise mean "record nothing" and take JDK move detection down with the
API layer. On `uika_upgrade_check` it defaults to -1 for "derive" so that 0
stays available as the switch that turns the API layer off. Both rules'
binaries also take `--jdkRelease` at run time, `@uika//:merge` included.

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
portable to another machine. See [Caching the baseline](#caching-the-baseline).
