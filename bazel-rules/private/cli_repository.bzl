"""Downloads the uika CLI binary for the host platform.

A repository rule rather than a download at run time, unlike the Leiningen plugin and the
Clojure tool: Bazel's repository cache is exactly the right place for a pinned binary, so a
second run needs no network and an air-gapped build can prime it.
"""

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
    name = repository_ctx.os.name.lower()
    arch = repository_ctx.os.arch.lower()
    if name.startswith("windows"):
        return "windows-x86_64"
    classifier = _CLASSIFIERS.get((name, arch))
    if not classifier:
        fail("no uika-cli binary is published for {}/{} (available: {})".format(
            name,
            arch,
            ", ".join(sorted({v: None for v in _CLASSIFIERS.values()}.keys()) + ["windows-x86_64"]),
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
    repository_ctx.download_and_extract(
        url = "{}/net/exoego/uika/uika-cli/{}/uika-cli-{}-{}.zip".format(
            repository_ctx.attr.repository,
            version,
            version,
            classifier,
        ),
        # Empty means unpinned, which Bazel warns about and still caches. The release
        # archive of this ruleset is cut from the tag BEFORE the binaries are built, so it
        # cannot carry their checksums; a build that wants them pins them itself.
        sha256 = repository_ctx.attr.sha256.get(classifier, ""),
        stripPrefix = "uika-{}-{}".format(version, classifier),
    )
    if binary != "uika.exe":
        # Bazel's zip extractor drops the executable bit that the release archive carries.
        result = repository_ctx.execute(["chmod", "+x", binary])
        if result.return_code != 0:
            fail("could not make {} executable: {}".format(binary, result.stderr))
    repository_ctx.file("BUILD.bazel", _BUILD.format(binary = binary))

uika_cli_repository = repository_rule(
    implementation = _uika_cli_impl,
    doc = "The uika CLI binary for the host platform.",
    attrs = {
        "version": attr.string(default = UIKA_VERSION),
        "sha256": attr.string_dict(doc = "Maven classifier to sha256 of its distribution zip."),
        "repository": attr.string(default = _CENTRAL),
    },
    environ = ["UIKA_CLI_PATH"],
)
