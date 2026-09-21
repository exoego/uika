"""Checksum of the uika-cli jar this release was cut with.

Empty in git and filled in by the release packaging step (bazel-rules/stage.sh), which
runs after the jar has been built, so a released archive pins it. A build consuming the
repository at a git revision gets an empty string and an unpinned download, and can pin it
by hand with the `uika.cli` tag.
"""

UIKA_CLI_SHA256 = ""
