"""The module extension that declares `@uika_cli`.

Declared by this module itself, so a build that only loads the rules gets a working
`uika_upgrade_check` with nothing to wire up. A root module that wants to pin the version or
its checksums repeats the `uika.cli` tag and wins, because its own declaration is read last.
"""

load("//private:checksums.bzl", "UIKA_CLI_SHA256")
load("//private:cli_repository.bzl", "uika_cli_repository")
load("//private:version.bzl", "UIKA_VERSION")

_cli = tag_class(
    attrs = {
        "version": attr.string(
            default = UIKA_VERSION,
            doc = "uika-cli version. Defaults to the release this ruleset belongs to, so one" +
                  " coordinate bump moves both.",
        ),
        "sha256": attr.string(
            doc = "sha256 of the CLI jar. A released archive already pins it, so this is" +
                  " only needed when consuming the rules at a git revision, from another" +
                  " repository, or with a version override.",
        ),
        "repository": attr.string(doc = "Maven repository base URL to download from."),
    },
)

def _uika_impl(module_ctx):
    version = UIKA_VERSION
    sha256 = ""
    repository = None
    for module in module_ctx.modules:
        for tag in module.tags.cli:
            # Modules are visited root first, so a dependency cannot override the root's
            # choice; only fill in what nobody has stated yet.
            if tag.version:
                version = tag.version
            if tag.sha256:
                sha256 = tag.sha256
            if tag.repository:
                repository = tag.repository
        if module.tags.cli:
            break

    if not sha256:
        # The stamped checksum describes UIKA_VERSION's jar and nothing else, so a version
        # override has to drop it. Carrying it over verified the requested version against
        # another version's hash and failed the fetch outright, which made a released
        # archive plus any version override unusable.
        sha256 = UIKA_CLI_SHA256 if version == UIKA_VERSION else ""

    uika_cli_repository(
        name = "uika_cli",
        version = version,
        sha256 = sha256,
        **({"repository": repository} if repository else {})
    )

uika = module_extension(
    implementation = _uika_impl,
    tag_classes = {"cli": _cli},
)
