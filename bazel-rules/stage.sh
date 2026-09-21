#!/bin/sh
# Builds the release archive of the Bazel module. Run through `make bazel-stage`.
#
# The copy is made with -L because the four jvm-plugin-core sources under java/ are
# committed symlinks pointing out of the module root, which is fine in this repository and
# useless to a consumer. The version and the CLI checksum are stamped into the COPY, so
# git keeps the placeholders, the same arrangement cli/Cargo.toml has with UIKA_VERSION.
set -eu

version=${1:?usage: stage.sh <version> <rules-dir> <stage-dir> <cli-jar>}
rules=${2:?}
stage=${3:?}
jar=${4:?}

# An archive without the pin would download the jar unverified on every consumer, and a
# release cannot be amended. The jar is one file the release always builds, so its absence
# is a broken pipeline rather than a platform that was skipped.
if [ ! -f "$jar" ]; then
  echo "stage.sh: no CLI jar at $jar" >&2
  exit 1
fi

sha256() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

rm -rf "$stage"
mkdir -p "$stage"
cp -RL "$rules" "$stage/bazel-rules"
# The integration test carries a local_path_override back into this repository, so it is
# not only dead weight in the archive but actively misleading.
rm -rf "$stage/bazel-rules/it" "$stage/bazel-rules/stage.sh"
# cp -RL dereferences symlinks, so a convenience symlink left by a hand-run bazel in
# this directory would have folded a whole output base into the archive. The Makefile
# suppresses them; this is the belt for anyone who ran bazel here directly. The lock
# file is dropped too: Bazel ignores a non-root module's lock file.
rm -rf "$stage"/bazel-rules/bazel-* "$stage/bazel-rules/MODULE.bazel.lock"

sed -i.bak "s/0\.0\.0-dev/$version/" \
  "$stage/bazel-rules/MODULE.bazel" \
  "$stage/bazel-rules/private/version.bzl"
rm -f "$stage/bazel-rules/MODULE.bazel.bak" "$stage/bazel-rules/private/version.bzl.bak"

sed -i.bak "s/^UIKA_CLI_SHA256 = \"\"\$/UIKA_CLI_SHA256 = \"$(sha256 "$jar")\"/" \
  "$stage/bazel-rules/private/checksums.bzl"
rm -f "$stage/bazel-rules/private/checksums.bzl.bak"

tar -czf "$stage/uika-bazel-$version.tar.gz" -C "$stage" bazel-rules
echo "staged $stage/uika-bazel-$version.tar.gz"
