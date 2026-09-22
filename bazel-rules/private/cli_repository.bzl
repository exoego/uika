"""Downloads the uika CLI jar.

A repository rule rather than a download at run time, unlike the Leiningen plugin and the
Clojure tool: Bazel's repository cache is exactly the right place for a pinned jar, so a
second run needs no network and an air-gapped build can prime it.

One jar for every host, which UikaCli starts on the check binary's own Java runtime. There
is no platform table to keep in step with anything.
"""

load(":version.bzl", "UIKA_VERSION")

_CENTRAL = "https://repo1.maven.org/maven2"

_JAR = "uika-cli.jar"

# Kept in step with UikaCli.JAR_CLASSIFIER by hand. The jar is a classified artifact, not
# the coordinate's main one, so the published file name carries this.
_CLASSIFIER = "jvm"

_BUILD = """\
exports_files(["{file}"])

filegroup(
    name = "cli",
    srcs = ["{file}"],
    visibility = ["//visibility:public"],
)
"""

def _uika_cli_impl(repository_ctx):
    override = repository_ctx.os.environ.get("UIKA_CLI_PATH", "")
    if override:
        # The CLI is chosen at RUN time as well (UpgradeCheckMain reads the same
        # variable), so this only avoids a pointless download. Symlinked rather than
        # copied so rebuilding the CLI in place is picked up without a refetch. The link
        # keeps the suffix, because that is how UikaCli tells the jar from an executable.
        name = _JAR if override.lower().endswith(".jar") else "uika"
        repository_ctx.symlink(override, name)
        repository_ctx.file("BUILD.bazel", _BUILD.format(file = name))
        return

    version = repository_ctx.attr.version
    url = "{}/net/exoego/uika/uika-cli/{}/uika-cli-{}-{}.jar".format(
        repository_ctx.attr.repository,
        version,
        version,
        _CLASSIFIER,
    )

    # A released archive carries the jar's checksum (private/checksums.bzl, stamped by
    # stage.sh once the jar exists), so the release path is verified with nothing to wire
    # up. Consuming the rules at a git revision leaves it empty by construction.
    expected = repository_ctx.attr.sha256
    result = repository_ctx.download(
        url = url,
        output = _JAR,
        sha256 = expected,
    )
    if not expected:
        # Bazel itself says NOTHING about an unpinned download. The familiar "canonical
        # reproducible form" note comes from http_archive, which reports it by hand, so a
        # bare repository rule that stays quiet leaves the download silently unverified.
        # Verified against a cold fetch with an empty --repository_cache. The hash is the
        # one just downloaded, so it is paste-ready but proves only that this fetch and a
        # later one agree.
        print("uika: {} was downloaded without a checksum. Pin it with".format(url) +
              " uika.cli(sha256 = \"{}\")".format(result.sha256))
    repository_ctx.file("BUILD.bazel", _BUILD.format(file = _JAR))

uika_cli_repository = repository_rule(
    implementation = _uika_cli_impl,
    doc = "The uika CLI jar.",
    attrs = {
        "version": attr.string(default = UIKA_VERSION),
        # No default: the stamped checksum only describes UIKA_VERSION, so whether it
        # applies depends on the version being requested. That decision belongs in the
        # module extension, which is the one place that knows both.
        "sha256": attr.string(doc = "sha256 of the CLI jar."),
        "repository": attr.string(default = _CENTRAL),
    },
    environ = ["UIKA_CLI_PATH"],
)
