#!/usr/bin/env python3
"""Adds what `lein deploy staging` cannot: a sources jar (src + the shared
src-core), an empty javadoc jar (Central requires the file, not content; see
PUBLISHING.md), and md5/sha1 for both. The maven-metadata.xml lein writes is
deleted: JReleaser uploads per-version files and Central regenerates metadata."""
import hashlib, pathlib, sys, zipfile

plugin_dir, version = pathlib.Path(sys.argv[1]), sys.argv[2]
dest = plugin_dir / "target" / "staging-deploy" / "net" / "exoego" / "uika" / "lein-uika" / version
assert dest.is_dir(), f"run lein deploy staging first: {dest}"

def add_tree(zf: zipfile.ZipFile, root: pathlib.Path) -> None:
    for f in sorted(root.rglob("*")):
        if f.is_file():
            zf.write(f, f.relative_to(root))

sources = dest / f"lein-uika-{version}-sources.jar"
with zipfile.ZipFile(sources, "w", zipfile.ZIP_DEFLATED) as zf:
    add_tree(zf, plugin_dir / "src")
    add_tree(zf, plugin_dir.parent / "clojure-tool" / "src-core")

javadoc = dest / f"lein-uika-{version}-javadoc.jar"
with zipfile.ZipFile(javadoc, "w") as zf:
    zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")

for f in (sources, javadoc):
    data = f.read_bytes()
    for algorithm in ("md5", "sha1"):
        f.with_suffix(f.suffix + "." + algorithm).write_text(
            hashlib.new(algorithm, data).hexdigest())

for meta in dest.parent.glob("maven-metadata.xml*"):
    meta.unlink()
print(f"staged extras for lein-uika {version} under {dest}")
