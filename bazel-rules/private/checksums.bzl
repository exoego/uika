"""Checksums of the uika-cli distribution zips this release's binaries hashed to.

Empty in git and filled in by the release packaging step (bazel-rules/stage.sh), which
runs after the per-platform binaries have been built and downloaded, so a released archive
pins every platform it can reach. A build consuming the repository at a git revision gets
an empty map and an unpinned download, and can pin it by hand with the `uika.cli` tag.
"""

UIKA_CLI_SHA256 = {}
