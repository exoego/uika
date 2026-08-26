# Caching the baseline classpath dump

The [PR gate in README.md](README.md#pr-gate-on-github-actions-the-main-use-case)
checks out the base commit and resolves it on the PR runner, costing a second
checkout and a cold build-tool start on the PR's critical path every time.

The baseline only feeds the version diff, so it does not have to be produced by
the PR job. Dump it once per push to `develop`, upload it as an artifact keyed
by SHA, and have the PR job fetch it by `base.sha`. Copy-pasteable workflow
pairs for [Gradle](#gradle), [Maven](#maven), and [sbt](#sbt) follow.

## What the PR job needs

A dump names artifacts by absolute path, so two things must hold on the PR
runner:

1. **The paths resolve.** On GitHub Actions both jobs use the same runner image
   under the same `$HOME`, so dependency-cache and workspace paths already line
   up. Dumps carried between different machines do not.
2. **The old-version JARs are on disk.** uika reads them to diff old against
   new, but the PR job resolves the *new* versions, so nothing pulls the old
   ones in on its own.

Point 2 matters because a JAR uika cannot open is a warning, not an error: the
run continues against an incomplete old-side index and reports *fewer* breaks
than exist. Gradle closes it with `uikaResolveClasspath`, which fetches whatever
is missing through the build's own repositories, mirrors, and credentials. Maven
and sbt have no equivalent task, so they restore the dependency cache the
baseline run wrote — it holds the old versions by construction. Bazel closes
both points at once with `--materialize`, which copies the JARs next to the dump
and rewrites it to point there.

Every PR workflow below keeps the checkout-based dump as a fallback, since some
SHAs have no usable baseline: those predating the workflow, expired artifacts,
and PRs not targeting `develop`. Both paths write `/tmp/before.json`, so the
check step is the same either way.

## Gradle

```yaml
# .github/workflows/uika-baseline.yml
name: uika-baseline
on:
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

jobs:
  dump:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version
      - uses: gradle/actions/setup-gradle@v4

      - run: ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/classpath.json -PuikaBuildOutputs=false

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip
```

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    # every place a dependency version can be declared
    paths:
      - '**.gradle.kts'
      - gradle/libs.versions.toml

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version
      - uses: gradle/actions/setup-gradle@v4

      - name: Fetch baseline artifact
        id: baseline-artifact
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/baseline
          mv /tmp/baseline/classpath.json /tmp/before-remote.json

      - name: Dump PR classpath
        # `classes` so the build outputs anchor the reachability ranking
        run: ./gradlew classes uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Rehydrate baseline to local paths
        id: rehydrate
        if: steps.baseline-artifact.outcome == 'success'
        continue-on-error: true
        run: >
          ./gradlew uikaResolveClasspath
          -PuikaInput=/tmp/before-remote.json -PuikaResolveOutput=/tmp/before.json

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.rehydrate.outcome != 'success'
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json -PuikaBuildOutputs=false; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.rehydrate.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: >
          ./gradlew uikaUpgradeCheck
          -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

## Maven

```yaml
# .github/workflows/uika-baseline.yml
name: uika-baseline
on:
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

jobs:
  dump:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version

      # no `compile` phase, so this resolves without building anything
      - run: mvn -q uika:dump-classpath -Duika.output=/tmp/classpath.json

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

      # the dump names JARs by absolute path, and this local repository is the
      # only place the old versions are guaranteed to exist
      - uses: actions/cache/save@v4
        with:
          path: ~/.m2/repository
          key: uika-baseline-m2-${{ github.sha }}
```

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**/pom.xml'

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version

      - name: Restore baseline dependencies
        id: baseline-deps
        uses: actions/cache/restore@v4
        with:
          path: ~/.m2/repository
          key: uika-baseline-m2-${{ github.event.pull_request.base.sha }}

      - name: Fetch baseline artifact
        id: baseline-artifact
        # without the old JARs the baseline would under-report, so skip it
        if: steps.baseline-deps.outputs.cache-hit == 'true'
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/baseline
          mv /tmp/baseline/classpath.json /tmp/before.json

      - name: Dump PR classpath
        # `compile` so the build outputs anchor the reachability ranking
        run: mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.baseline-artifact.outcome != 'success'
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if mvn -q uika:dump-classpath -Duika.output=/tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline-artifact.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: >
          mvn uika:upgrade-check
          -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json
```

## sbt

```yaml
# .github/workflows/uika-baseline.yml
name: uika-baseline
on:
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

jobs:
  dump:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version

      # sbt compiles as a side effect of evaluating the dump task
      - run: sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/classpath.json

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

      # the dump names JARs by absolute path, and these caches are the only
      # place the old versions are guaranteed to exist
      - uses: actions/cache/save@v4
        with:
          path: |
            ~/.cache/coursier
            ~/.ivy2/cache
          key: uika-baseline-deps-${{ github.sha }}
```

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**.sbt'
      - 'project/**.scala'
      - project/build.properties

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version-file: .java-version

      - name: Restore baseline dependencies
        id: baseline-deps
        uses: actions/cache/restore@v4
        with:
          path: |
            ~/.cache/coursier
            ~/.ivy2/cache
          key: uika-baseline-deps-${{ github.event.pull_request.base.sha }}

      - name: Fetch baseline artifact
        id: baseline-artifact
        # without the old JARs the baseline would under-report, so skip it
        if: steps.baseline-deps.outputs.cache-hit == 'true'
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/baseline
          mv /tmp/baseline/classpath.json /tmp/before.json

      - name: Dump PR classpath
        # compile so the build outputs anchor the reachability ranking
        run: sbt compile uikaDumpClasspath && cp target/uika/classpath.json /tmp/after.json

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.baseline-artifact.outcome != 'success'
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline-artifact.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"
```

## Bazel

Bazel is the one case where restoring a dependency cache does not work. It
discards an external repository and refetches it whenever the lockfile changes,
so the baseline's JARs are gone on the PR branch no matter what was cached.
`--materialize` sidesteps it: the baseline artifact carries the JARs themselves,
which also makes it valid on a different runner.

```yaml
# .github/workflows/uika-baseline.yml
name: uika-baseline
on:
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

jobs:
  dump:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

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
```

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - 'MODULE.bazel'
      - 'maven_install.json'

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      - name: Fetch baseline artifact
        id: baseline
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

      - name: Dump the baseline the slow way
        id: baseline-fallback
        if: steps.baseline.outcome != 'success'
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

      - run: bazel run //:uika_dump -- --output /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: >
          bazel run //:uika_upgrade_check --
          --before /tmp/uika-baseline/classpath.json --after /tmp/after.json
```

The materialized directory has to be restored at the same absolute path the
baseline run wrote it to, because the dump names its JARs absolutely. Both
workflows above use `/tmp/uika-baseline` for exactly that reason.
