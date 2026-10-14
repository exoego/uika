# [Maven plugin](../maven-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-maven-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-maven-plugin)

One of uika's [build-tool integrations](build-tools.md).
Maven running on Java 17 or newer.

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

`uika:upgrade-check` fetches the CLI as the `jvm` jar of
`net.exoego.uika:uika-cli:<version>` and runs it on the JVM that runs Maven.

## PR gate on GitHub Actions

The `linkage-check` job dumps the PR's classpath and a baseline from the PR's
base branch, and fails on broken references between the two.

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**/pom.xml'
      - .github/workflows/linkage-check.yml

# a PR update supersedes the running check
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Maven here ....

      - name: Dump PR classpath
        # `compile` so the build outputs anchor the reachability ranking
        run: mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json

      - name: Dump baseline classpath
        id: baseline
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          # no `compile` phase, so this resolves without building anything
          if mvn -q uika:dump-classpath -Duika.output=/tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: >
          mvn uika:upgrade-check
          -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json
```

If the PR workflow already compiles the project and later jobs can reuse that
work, through a remote build cache or by running on the same runner, give this
job a `needs:` on those jobs, in the same workflow file. The PR dump then
takes its compiled classes from them instead of compiling again.

`paths:` keeps the check off PRs that touch no dependency declaration. A
required status check cannot use it, because a skipped workflow creates no
check run and the PR waits on it forever. For a required check, trigger on
every PR and skip the job from a job-level `if:` that reads the changed files.
A skipped job counts as passed.

### Caching the baseline

The baseline dump is a second Maven start on the PR runner. Dumping it once
per push to the base branch instead, as an artifact named by SHA that the PR
job fetches by `base.sha`, moves that invocation off the PR's critical path at
the price of a job on every push, so the example does not do it.

Where that trade is worth it, save the local repository with `actions/cache`
under the same SHA and restore it before the check, because a fetched dump
names JARs by absolute path and the old versions are not on the PR runner.
Restore it for same-repo PRs only, since a fork PR's build code could read
private dependencies out of it. A compared-pair JAR uika cannot open exits 2
rather than degrading to a warning.

## Options

- [`failOn`](../README.md#violation-tiers-and-the-failon-threshold) and
  [`excludeFiles`](../README.md#excluding-known-false-positives)
  are configured above. The command-line forms are `-Duika.failOn=` and
  `-Duika.excludeFiles=`, the latter comma-separated, so suppressing a finding
  for one CI run needs no POM edit. Both follow Maven's ordinary precedence, so
  a POM `<configuration>` entry shadows the property. A path containing a comma
  has to go in `<excludeFiles>`, since the comma is the delimiter there.
- [`jdkRelease`](build-tools.md#jdkrelease) is derived from
  maven-compiler-plugin's `<release>`/`<target>`, else
  `maven.compiler.release`/`maven.compiler.target`. Override with
  `<jdkRelease>` or `-Duika.jdkRelease=` on both goals, or set 0 to disable
  the API layer.
  A module that declares neither is recorded in the dump with the release of
  the JVM running Maven, since that is what javac targets. The plugin does not
  read the release of a JDK toolchain or a forked `<executable>`, so a module
  compiled that way records nothing and the check uses the lowest release any
  module records.
- `<mergedClasspath>` / `-Duika.mergedClasspath=true` checks the union of every
  module's classpath once instead of [each module against its own
  resolution](../README.md#per-module-checking). Per-module checking scans once
  per module, so a large reactor may want the union; the trade is that a break
  only one module's resolution shows can hide behind another module's version of
  the same jar.
- `<jfr>` / `-Duika.jfr` also carries [text
  evidence](runtime-load-evidence.md). Anything in that directory that is not a
  recording is passed on unchanged, so `-Xlog:class+load` output and a
  classlist mix with the recordings. There is no separate parameter.
- `UIKA_CLI_PATH` runs a CLI you already have instead of resolving one, so a
  build can run air-gapped or against a locally built CLI. It takes the jar, or
  an executable that runs it, such as a script that starts the jar on another
  JDK. It wins over the CLI version, nothing is downloaded, and a value that is
  not a file, or an executable that lost its bit, fails naming the variable.

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
goal against the execution root. When the POM or a parent POM configures
surefire's `<argLine>`, Maven ignores a command-line `-DargLine`, so nothing
records and the build still passes. Put the flag in that `<argLine>` instead.
A `-DargLine` does replace an `argLine` property, such as the one jacoco's
prepare-agent sets, and drops jacoco's agent with it. A build cache that
replays test executions collects nothing: maven-build-cache-extension and the
Develocity extension both skip surefire on a cache hit, so disable caching for
the collect run, the same reason the Bazel recipe needs
`--nocache_test_results`. [`--draft-exclude-file`](runtime-load-evidence.md)
maps to `-Duika.draftExcludeFile=`.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.
