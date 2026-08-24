# Publishing

Everything under the `net.exoego.uika` group is published to Maven Central in
one shot when a GitHub release is published: the native CLI ZIPs (`uika-cli`
with classifiers `linux-x86_64`, `macos-aarch64`, `macos-x86_64`,
`windows-x86_64`), the Gradle plugin, the sbt plugin, and the Maven plugin.

## Release procedure

Create a GitHub release with tag `vX.Y.Z`. That is all.

`.github/workflows/publish-release.yml` builds each platform on its native
runner, stages all Maven artifacts locally, then JReleaser signs everything
in-memory and uploads a single deployment to the Central Portal
(all-or-nothing validation) and attaches the ZIPs to the GitHub release.

Versions are derived from the tag alone. No source file is rewritten.
`cli/Cargo.toml` stays at the `0.0.0-dev` placeholder: release builds embed
the tag version into `uika --version` at compile time through the
`UIKA_VERSION` environment variable (`option_env!` in `cli/src/cli.rs`), and
JVM plugin versions are injected too. Every module publishes to a local
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

One `vX.Y.Z` tag is one deployment carrying six components (`uika-cli`,
`uika-gradle-plugin`, the `net.exoego.uika.gradle.plugin` marker,
`sbt-uika_2.12_1.0`, `uika-maven-plugin`, `mill-uika_mill1_3`). That is 92 files
and about 3 MB per tag, so for uika alone Release Count is the binding metric, not file count or
size. July 2026 shipped eight tags and tripped the release-count limit.

All three metrics are metered per organization, so the quota is shared with
every other project under `net.exoego`. uika is the heavy one because it ships
four native binaries.

Three rules follow.

Batch changes into fewer tags. Do not publish for documentation or metadata
updates, and do not cut a tag per merged PR. A burst for a security fix is
explicitly tolerated by Sonatype and is not a reason to delay one.

Keep the deployment small. Two things hold it at 3 MB instead of 6.2 MB, and
reverting either without a replacement gives the bytes back. `[profile.release]`
in the workspace `Cargo.toml` is tuned for size over speed, deliberately, and
its comment carries the measurements. The four plugins publish an empty
javadoc jar, because Central requires that jar to exist but not to have
content, and readers have the sources jar; each build file comments how.

Do not add files to the deployment without checking the cost. Every artifact
carries a `.md5`, a `.sha1`, and an `.asc`, so one new artifact is four files
per release. `jreleaser.yml` sets `checksums: false` on the deployer because
`applyMavenCentralRules` otherwise adds `.sha256` and `.sha512` to every
artifact, which Central accepts but does not require. Each build tool already
stages md5 and sha1 itself, and the two Gradle builds carry a
`gradle.properties` with `systemProp.org.gradle.internal.publish.checksums.insecure=true`
so they stop at those two. Dropping the optional pair cut the bundle from 114
files to 76. Verify both metrics after any publishing change:

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
$ make native-publish-local UIKA_VERSION=0.1.0   # publish CLI ZIPs to ~/.m2 (expects ZIPs under dist/native/<classifier>/)
$ make stage-all UIKA_VERSION=0.1.0              # stage all Maven artifacts locally
$ mise exec -- jreleaser deploy --dry-run        # needs JRELEASER_* env vars. Validates POMs and signs without uploading
```
