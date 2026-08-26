#!/bin/sh
# Builds the release archive of the Bazel module. Run through `make bazel-stage`.
#
# The copy is made with -L because the four jvm-plugin-core sources under java/ are
# committed symlinks pointing out of the module root, which is fine in this repository and
# useless to a consumer. The version and the CLI checksums are stamped into the COPY, so
# git keeps the placeholders, the same arrangement cli/Cargo.toml has with UIKA_VERSION.
set -eu

version=${1:?usage: stage.sh <version> <rules-dir> <stage-dir> <native-dist-dir>}
rules=${2:?}
stage=${3:?}
native=${4:?}

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

sed -i.bak "s/0\.0\.0-dev/$version/" \
  "$stage/bazel-rules/MODULE.bazel" \
  "$stage/bazel-rules/private/version.bzl"
rm -f "$stage/bazel-rules/MODULE.bazel.bak" "$stage/bazel-rules/private/version.bzl.bak"

# Whatever platforms this run actually built. A partial set still pins what it names, and
# the release workflow builds all four before it gets here.
entries=""
for classifier in linux-x86_64 macos-aarch64 macos-x86_64 windows-x86_64; do
  zip="$native/$classifier/uika-$version-$classifier.zip"
  if [ -f "$zip" ]; then
    entries="$entries    \"$classifier\": \"$(sha256 "$zip")\",
"
  else
    echo "stage.sh: no $zip, leaving $classifier unpinned" >&2
  fi
done

if [ -n "$entries" ]; then
  {
    sed 's/^UIKA_CLI_SHA256 = {}$/UIKA_CLI_SHA256 = {/' \
      "$stage/bazel-rules/private/checksums.bzl"
    printf '%s' "$entries"
    echo "}"
  } > "$stage/bazel-rules/private/checksums.bzl.new"
  mv "$stage/bazel-rules/private/checksums.bzl.new" "$stage/bazel-rules/private/checksums.bzl"
fi

tar -czf "$stage/uika-bazel-$version.tar.gz" -C "$stage" bazel-rules
echo "staged $stage/uika-bazel-$version.tar.gz"
