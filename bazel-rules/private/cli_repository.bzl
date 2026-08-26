"""Downloads the uika CLI binary for the host platform.

A repository rule rather than a download at run time, unlike the Leiningen plugin and the
Clojure tool: Bazel's repository cache is exactly the right place for a pinned binary, so a
second run needs no network and an air-gapped build can prime it.
"""

load(":checksums.bzl", "UIKA_CLI_SHA256")
load(":version.bzl", "UIKA_VERSION")

_CENTRAL = "https://repo1.maven.org/maven2"

# Kept in step with UikaCli.platformClassifier, which is what the JVM plugins resolve.
_CLASSIFIERS = {
    ("linux", "x86_64"): "linux-x86_64",
    ("linux", "amd64"): "linux-x86_64",
    ("mac os x", "aarch64"): "macos-aarch64",
    ("mac os x", "arm64"): "macos-aarch64",
    ("mac os x", "x86_64"): "macos-x86_64",
    ("mac os x", "amd64"): "macos-x86_64",
}

def _classifier(repository_ctx):
    """The published classifier for the host platform.

    Windows short-circuits ahead of the table because only one Windows binary is published,
    so there is no architecture to consult and no second row that could ever match.
    """
    name = repository_ctx.os.name.lower()
    arch = repository_ctx.os.arch.lower()
    if name.startswith("windows"):
        return "windows-x86_64"
    classifier = _CLASSIFIERS.get((name, arch))
    if not classifier:
        fail("no uika-cli binary is published for {}/{} (available: {})".format(
            name,
            arch,
            "linux-x86_64, macos-aarch64, macos-x86_64, windows-x86_64",
        ))
    return classifier

_BUILD = """\
exports_files(["{binary}"])

filegroup(
    name = "binary",
    srcs = ["{binary}"],
    visibility = ["//visibility:public"],
)
"""

def _uika_cli_impl(repository_ctx):
    override = repository_ctx.os.environ.get("UIKA_CLI_PATH", "")
    if override:
        # The binary is chosen at RUN time as well (UpgradeCheckMain reads the same
        # variable), so this only avoids a pointless download. Symlinked rather than
        # copied so rebuilding the binary in place is picked up without a refetch.
        repository_ctx.symlink(override, "uika")
        repository_ctx.file("BUILD.bazel", _BUILD.format(binary = "uika"))
        return

    classifier = _classifier(repository_ctx)
    version = repository_ctx.attr.version
    binary = "uika.exe" if classifier.startswith("windows") else "uika"
    url = "{}/net/exoego/uika/uika-cli/{}/uika-cli-{}-{}.zip".format(
        repository_ctx.attr.repository,
        version,
        version,
        classifier,
    )
    # A released archive carries every platform's checksum (private/checksums.bzl, stamped
    # by stage.sh once the binaries exist), so the release path is verified with nothing to
    # wire up. Consuming the rules at a git revision leaves the map empty by construction.
    expected = repository_ctx.attr.sha256.get(classifier, "")
    result = repository_ctx.download_and_extract(
        url = url,
        sha256 = expected,
        stripPrefix = "uika-{}-{}".format(version, classifier),
    )
    if not expected:
        # Bazel itself says NOTHING about an unpinned download_and_extract. The familiar
        # "canonical reproducible form" note comes from http_archive, which reports it by
        # hand, so a bare repository rule that stays quiet leaves the download silently
        # unverified. Verified against a cold fetch with an empty --repository_cache.
        # The hash is the one just downloaded, so it is paste-ready but proves only that
        # this fetch and a later one agree.
        print("uika: {} was downloaded without a checksum. Pin it with".format(url) +
              " uika.cli(sha256 = {{\"{}\": \"{}\"}})".format(classifier, result.sha256))
    if binary != "uika.exe":
        # Bazel's zip extractor drops the executable bit that the release archive carries.
        chmod = repository_ctx.execute(["chmod", "+x", binary])
        if chmod.return_code != 0:
            fail("could not make {} executable: {}".format(binary, chmod.stderr))
    repository_ctx.file("BUILD.bazel", _BUILD.format(binary = binary))

uika_cli_repository = repository_rule(
    implementation = _uika_cli_impl,
    doc = "The uika CLI binary for the host platform.",
    attrs = {
        "version": attr.string(default = UIKA_VERSION),
        "sha256": attr.string_dict(
            default = UIKA_CLI_SHA256,
            doc = "Maven classifier to sha256 of its distribution zip.",
        ),
        "repository": attr.string(default = _CENTRAL),
    },
    environ = ["UIKA_CLI_PATH"],
)
