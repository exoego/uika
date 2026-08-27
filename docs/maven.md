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

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for Maven. The baseline dump omits the `compile` phase, because
the base branch is only there for its resolved versions:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Maven here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if mvn -q uika:dump-classpath -Duika.output=/tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: mvn uika:upgrade-check -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json
```

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).

## Options

- [`failOn`](../README.md#violation-tiers-and---fail-on) and
  [`excludeFiles`](../README.md#excluding-known-false-positives---exclude-file)
  are configured above. The command-line form of the gate is `-Duika.failOn=`.
- [`jdkRelease`](../README.md#build-tool-plugins) is derived from
  maven-compiler-plugin's `<release>`/`<target>`, else
  `maven.compiler.release`/`maven.compiler.target`. Override with
  `<jdkRelease>` or `-Duika.jdkRelease=`, or set 0 to disable the API layer.

## Runtime load evidence (JFR)

Collect with the test JVM flag (`mvn test
-DargLine="-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>"`),
check with `-Duika.jfr=<dir>`. Create `<dir>` first: given a missing parent
JFR aborts JVM startup, but given an existing parent it silently records to a
single file at that path, every fork clobbering the last. Make it absolute in
a multi-module build:
surefire forks resolve a relative path against each module, the aggregator
goal against the execution root. A command-line `-DargLine` replaces any
POM-configured argLine (jacoco's agent included) — append to the POM's
argLine instead when one exists.
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
maps to `-Duika.draftExcludeFile=`.
