# [Maven plugin](../maven-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-maven-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-maven-plugin)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).

```xml
<build>
  <plugins>
    <plugin>
      <groupId>net.exoego.uika</groupId>
      <artifactId>uika-maven-plugin</artifactId>
      <version>VERSION_PLACEHOLDER</version>
      <!-- Optional: gate only on reachable violations, and suppress known false positives. -->
      <configuration>
        <failOn>reachable</failOn>
        <excludeFiles>
          <excludeFile>${project.basedir}/uika-exclude.toml</excludeFile>
        </excludeFiles>
      </configuration>
    </plugin>
  </plugins>
</build>
```

```console
$ mvn uika:dump-classpath -Duika.output=/tmp/classpath.json
$ mvn uika:upgrade-check \
      -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json   # -Duika.cliVersion to override
```

A dump also refers to build outputs, and this plugin cannot build them itself.
Run a `compile` phase in the same invocation when they should be scanned.

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
      - '**/pom.xml'
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
  # push and workflow_dispatch triggers, and the marked steps in
  # linkage-check.
  dump-baseline:
    if: github.event_name != 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Maven here ....

      # no `compile` phase, so this resolves without building anything
      - run: mvn -q uika:dump-classpath -Duika.output=/tmp/classpath.json

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

      # the dump names JARs by absolute path, and this local repository is the
      # only place the old versions are guaranteed to exist
      - uses: actions/cache/save@v6
        with:
          path: ~/.m2/repository
          key: uika-baseline-m2-${{ github.sha }}

  linkage-check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Maven here ....

      # Cached-baseline fast path. These steps skip while no artifact
      # exists. Delete them together with the dump-baseline job if you do
      # not cache.
      - name: Restore baseline dependencies
        id: baseline-deps
        # a fork PR's build code could read private dependencies out of this
        # cache, so only same-repo PRs take the fast path
        if: github.event.pull_request.head.repo.full_name == github.repository
        uses: actions/cache/restore@v6
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
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
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

### Caching the baseline

The fallback resolves the base branch on the PR runner, which puts a
second checkout and a cold Maven start on the PR's critical path every time.
The baseline only feeds the version diff, so the `dump-baseline` job
produces it once per push instead. The fallback stays for SHAs with no
usable baseline. Those are SHAs predating the job, expired artifacts, and
PRs not targeting `develop`. Deleting the marked blocks is also safe,
because an `if:` reads a missing step's outcome as empty, never as
`success`.

A fetched dump names JARs by absolute path. On GitHub Actions both jobs
run on the same runner image under the same `$HOME`, so those paths line
up as long as the files exist. The old-version JARs are the gap, because
the PR job resolves the new versions and nothing pulls the old ones in on
its own, and a compared-pair JAR uika cannot open exits 2 rather than
degrading to a warning. The cache save and restore close that gap.

## Options

- [`failOn`](../README.md#violation-tiers-and---fail-on) and
  [`excludeFiles`](../README.md#excluding-known-false-positives---exclude-file)
  are configured above. The command-line forms are `-Duika.failOn=` and
  `-Duika.excludeFiles=`, the latter comma-separated and appended to the POM
  list, so suppressing a finding for one CI run needs no POM edit. A path
  containing a comma has to go in `<excludeFiles>`, since the comma is the
  delimiter.
- [`jdkRelease`](../README.md#build-tool-plugins) is derived from
  maven-compiler-plugin's `<release>`/`<target>`, else
  `maven.compiler.release`/`maven.compiler.target`. Override with
  `<jdkRelease>` or `-Duika.jdkRelease=`, or set 0 to disable the API layer.

## Runtime load evidence (JFR)

Collect with the test JVM flag (`mvn test
-DargLine="-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>"`;
the flag syntax needs JDK 17+ test JVMs, and surefire can fork a different JVM
than the build's), check with `-Duika.jfr=<dir>`. Create `<dir>` first: given
a missing parent JFR aborts JVM startup, but given an existing parent it
silently records to a single file at that path, every fork clobbering the
last. Quote the `filename` value when `<dir>` carries a comma — the comma is
the option delimiter, and an unquoted one silently truncates `filename=` with
exit 0, leaving the directory empty. Make it absolute in a multi-module build:
surefire forks resolve a relative path against each module, the aggregator
goal against the execution root. A command-line `-DargLine` replaces any
POM-configured argLine (jacoco's agent included) — append to the POM's
argLine instead when one exists. A build cache that replays test executions
collects nothing: maven-build-cache-extension and the Develocity extension
both skip surefire on a cache hit, so disable caching for the collect run,
the same reason the Bazel recipe needs `--nocache_test_results`.
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
maps to `-Duika.draftExcludeFile=`.
