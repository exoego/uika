# Notes for Agents: maven-plugin/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

## Maven Plugin Notes

- Maven: reactor dependencies are attributed with `"project"` and are never
  dropped from the dump; an unpackaged sibling falls back to its output
  directory. Keeping their coordinates is safe because project-attributed
  coordinates are excluded from the version DIFF (they stay in the version
  maps on purpose -- suggest's file->coordinate attribution reads them).
