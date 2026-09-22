# The classpath dump format

Every [build-tool integration](build-tools.md) writes the same JSON file, and
`uika upgrade-check` reads two of them. The format is a public contract: a
build with no integration of its own can write it from whatever knows its
resolved classpath, then run the [CLI](cli.md) on the pair.

## Shape

```json
{
  "version": 2,
  "jdkRelease": 17,
  "roots": [
    "/home/ci/.gradle/caches/modules-2/files-2.1/",
    "/home/ci/work/app/",
    ""
  ],
  "artifacts": [
    {"group": "com.google.guava", "name": "guava", "version": "33.4.0-jre",
     "root": 0, "path": "com.google.guava/guava/33.4.0-jre/2d7a/guava-33.4.0-jre.jar"},
    {"group": "org.example", "name": "shared", "version": "1.0", "project": ":shared",
     "root": 1, "path": "shared/build/libs/shared-1.0.jar"},
    {"root": 2, "path": "/opt/vendor/legacy.jar"}
  ],
  "modules": [
    {"module": ":app", "jdkRelease": 17,
     "classesDirs": [{"root": 1, "path": "app/build/classes/java/main"}],
     "artifactRefs": [0, 1, 2]},
    {"module": ":shared", "jdkRelease": 11,
     "classesDirs": [{"root": 1, "path": "shared/build/classes/java/main"}],
     "artifactRefs": [0]}
  ]
}
```

`version` is 2. `roots` is a table of path prefixes, and every path in the
file is `roots[root] + path`. The writer puts the artifact caches it knows in
the table and an empty string, so a path that matches no prefix is stored
whole under the empty root. A reader must not assume any root is non-empty,
ends in a separator, or sits at a fixed index.

`artifacts` is one table for the whole dump, with each artifact once. An entry
has `root` and `path`, and either all three of `group`, `name` and `version`
or none of them. An entry without coordinates is a file, git or local
dependency: it is scanned like any other, but no version diff can name it.
`project` marks an artifact produced by a module of the same build, holding
that module's name. Its coordinates are left out of the version diff, since the
build changed them itself, and when the file is missing (a baseline dump taken
without building) the check scans that module's `classesDirs` instead.

`modules` lists every module of the build, in the build's own order. `module`
is its name, unique within the dump, and the key `upgrade-check` pairs the two
dumps by. `classesDirs` are the module's own build outputs, as rooted paths.
They are scan targets and the roots of the
[reachability ranking](../README.md#violation-tiers-and-the-failon-threshold),
so an empty list leaves the module's violations unranked, which the gate
counts as 💥 since nothing can prove one unreachable.
`artifactRefs` are indices into `artifacts`, in resolution order. The order is
what the JVM would use, so it decides which copy of a duplicated class wins.

The two `jdkRelease` fields are optional and name the API release the checked
application runs on, never the JVM that wrote the file. A module carries one
when it declares a target release (`--release`, `targetCompatibility`,
`maven.compiler.release`, `-release`). The dump-level one is the lowest across
the modules, else the release of the JVM that ran the build, and it is what the
CLI assumes for a module that carries none. A JDK move between two dumps is
checked per module whose release moved, as its own run.

Paths are used as written. The integrations write absolute paths; a relative
path resolves against the working directory the CLI runs in.

## What the CLI does with it

`upgrade-check --before a.json --after b.json` diffs the coordinates per
module, and checks every module whose own resolution lost a version, against
that module's own classpath. A module the before dump does not have is diffed
against the union of the before dump's versions, scoped to its own coordinates,
and counted as new when that finds nothing. A module whose artifact list
vanished is counted as incomplete and not diffed. The
[README](../README.md#per-module-checking) explains the per-module semantics
and `--merged-classpath`, which checks the union of every module's classpath
once instead.

`check --classpath-file a.json` adds a dump's artifacts and build outputs to a
hand-assembled `check`, which is the way to check one library pair against a
real build's classpath.

## The older shape

A file without `version` is read as the first format: a `modules` list where
each artifact carries a `file` path in full and each entry of `classesDirs` is
a plain path string. Every field has the same meaning. The integrations stopped
writing it when the artifact table was introduced, and the CLI keeps reading it
so an old baseline still pairs with a new dump.

## Writing one yourself

The writer every JVM integration shares is `DumpFormat.writeV2` in
[`jvm-plugin-core`](../jvm-plugin-core/src/main/java/net/exoego/uika/plugin/core/DumpFormat.java),
and the Clojure front ends carry a hand port of it. A tool in another language
needs only the shape above. Two things matter more than the rest: keep one
module per unit the build resolves separately, so the check judges each
against what it really loads, and keep `artifactRefs` in resolution order.
