# Notes for Agents: sbt-plugin/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

## sbt Plugin Notes

- `uikaUpgradeCheck` is NOT aggregated in sbt (`uikaUpgradeCheck / aggregate :=
  false`). Every value it reads is ThisBuild- or root-scoped and the dumps
  already cover the whole build, so aggregating spawned one identical CLI run
  per project, in parallel, racing on the shared retrieve directory and on the
  JFR work directory whose stale-conversion sweep deletes a sibling's fresh
  output. For the same scoping reason the task reads `uikaJdkRelease` through
  `LocalRootProject /`, as it already did for `uikaJfr`. The general rule for
  whole-build ScopeFilter tasks: an inputKey must live in buildSettings with an
  aggregate opt-out, while a plain taskKey (`uikaDumpClasspath`) belongs in
  buildSettings OUTRIGHT — a projectSettings definition leaves every project
  holding a live full-merge instance, which shell aggregation, `all`-joined
  invocations and task-graph dependencies run in parallel against the one
  `uikaOutput` path (IO.write truncates in place, so racing writers interleave
  the JSON). Explicitly scoped invocations delegate to the one instance.
- sbt: `internalDependencyClasspath` entries are emitted as coordinate-less
  artifacts in each module's list. They are NOT in `update.value`, and without
  them per-module checking cannot resolve inter-module references (the merged
  check never noticed because every module contributes its own classesDirs to
  the union). Evaluating the dump task compiles those siblings.
