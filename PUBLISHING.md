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
