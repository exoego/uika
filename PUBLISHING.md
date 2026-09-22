# Publishing

Everything under the `net.exoego.uika` group is published to Maven Central in
one shot when a GitHub release is published: the CLI (`uika-cli`, whose
classifier `jvm` is the runnable jar), the Gradle plugin, the sbt plugin, the
Maven plugin, the Mill plugin, the Clojure CLI tool, and the Leiningen plugin.

The jar is a classified artifact and the POM keeps `pom` packaging, on purpose.
Central [requires](https://central.sonatype.org/publish/requirements/) a sources
and a javadoc jar for every packaging other than `pom`, and each artifact is
four files. A main jar would cost twelve files per release, and this one costs
four. Nothing in the jar is public API, so nobody needs it as a plain
dependency. If it ever gains one, that is the time to pay for jar packaging.

The Bazel rules do not go to Maven Central. They ship as
`uika-bazel-<version>.tar.gz` attached to the same GitHub release, because a
`bazel_dep` archive override points at a release URL rather than at a Maven
coordinate. `jreleaser.yml` attaches it through the `files` globs, so it costs
nothing against the Central Portal metrics below.

## Release procedure

Create a GitHub release with tag `vX.Y.Z`. That is all.

`.github/workflows/publish-release.yml` stages all Maven artifacts locally, then
JReleaser signs everything in-memory and uploads a single deployment to the
Central Portal (all-or-nothing validation). It attaches the CLI jar and the Bazel
ruleset tarball to the GitHub release.

Versions are derived from the tag alone. No source file is rewritten. Every
build keeps a `0.0.0-dev` placeholder and takes the tag version as a parameter,
`-PuikaVersion` for the CLI jar, whose manifest carries it into
`uika --version`, and the equivalent for each plugin. Every module publishes to a local
`staging-deploy` directory, and `jreleaser.yml` lists those directories as
staging repositories.

## Central Portal publishing limits

Maven Central meters three monthly metrics per organization, evaluated on a
three-month rolling average. Enforcement starts 2026-10-01, moved back from an
originally announced 2026-08-11. Current thresholds
and where `net.exoego` stands are in the
[Usage Center](https://central.sonatype.com/), which is the only authoritative
source. The free tier is roughly the 90th percentile of all publishers, about
1,167 files, 78 MB, and 7 releases per month.

One `vX.Y.Z` tag is one deployment carrying eight components (`uika-cli`,
`uika-gradle-plugin`, the `net.exoego.uika.gradle.plugin` marker,
`sbt-uika_2.12_1.0`, `uika-maven-plugin`, `mill-uika_mill1_3`, `clojure-uika`,
`lein-uika`).
That is 112 files per tag (`clojure-uika` added 16: four artifacts, each with
md5, sha1, and asc; the `uika-cli` jar is 4, and the four native ZIPs that were
16 more left with the Rust CLI). So for uika alone Release Count is
the binding metric, not file count or size. Riding the shared deployment is
also why publishing the Clojure CLI tool costs no extra release against that
metric. July 2026 shipped eight tags and tripped the release-count limit.

All three metrics are metered per organization, so the quota is shared with
every other project under `net.exoego`.

Three rules follow.

Batch changes into fewer tags. Do not publish for documentation or metadata
updates, and do not cut a tag per merged PR. A burst for a security fix is
explicitly tolerated by Sonatype and is not a reason to delay one.

Keep the deployment small. The plugins publish an empty javadoc jar, because
Central requires that jar to exist but not to have content, and readers have the
sources jar; each build file comments how. The CLI jar ships as a classifier
under `pom` packaging for the same reason, above.

Do not add files to the deployment without checking the cost. Every artifact
carries a `.md5`, a `.sha1`, and an `.asc`, so one new artifact is four files
per release. `jreleaser.yml` sets `checksums: false` on the deployer because
`applyMavenCentralRules` otherwise adds `.sha256` and `.sha512` to every
artifact, which Central accepts but does not require. Gradle, sbt, Maven and lein
stage md5 and sha1 themselves (Mill's M2 publisher writes none, so
`mill-plugin/build.mill` has a `stageChecksums` command the release step chains after
`publishM2Local`), and the two Gradle builds carry a
`gradle.properties` with `systemProp.org.gradle.internal.publish.checksums.insecure=true`
so they stop at those two. Dropping the optional pair cut the bundle from 114
files to 76, measured before the Mill plugin added its four artifacts. Verify
both metrics after any publishing change:

```console
$ unzip -Z1 out/jreleaser/deploy/mavenCentral/uika/*-bundle.zip | wc -l
$ unzip -l  out/jreleaser/deploy/mavenCentral/uika/*-bundle.zip | tail -1
```

## Required repository secrets 

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD` (a [Central Portal token](https://central.sonatype.com/account) for the verified `net.exoego` namespace)
- `JRELEASER_GPG_SECRET_KEY`
- `JRELEASER_GPG_PUBLIC_KEY` 
- `JRELEASER_GPG_PASSPHRASE` (ASCII-armored key pair)

The public key must be published to `keyserver.ubuntu.com` so Central can verify signatures.

## Local verification

```console
$ make cli-publish-local UIKA_VERSION=0.1.0      # publish the CLI jar (classifier jvm) to ~/.m2
$ make stage-all UIKA_VERSION=0.1.0              # stage all Maven artifacts locally, plus the Bazel tarball under dist/bazel/
$ mise exec -- jreleaser deploy --dry-run        # needs JRELEASER_* env vars. Validates POMs and signs without uploading
```
