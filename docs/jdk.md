# The JDK API layer

A reference whose hierarchy escapes into an unanalyzed class is counted as
[unverified](../README.md#how-it-works) rather than silently ignored. A dump
names the application's runtime classpath, never the JDK's own classes, so a
hierarchy that walks up into a JDK type escapes. Every tool layers the JDK API
of one release under the resolution scope to conclude those references, at the
release [`jdkRelease`](build-tools.md#jdkrelease) derives or
[`--jdk-release N`](cli.md#options-shared-by-check-and-upgrade-check) names.

## Under both sides of the comparison

The layer sits under the old and the new side alike, so a gap in it resolves the
same way on both and cancels out. It can turn an unverified reference into a
conclusion, never invent a violation.

On a guava 22 -> 23 check of selenium-remote-driver it takes 16 unverified
references to 0 with the broken count unchanged.

## Where the stubs come from

The stubs come from the `ct.sym` file of the JDK named by `UIKA_JDK`, checked
first and authoritative when set, else `JAVA_HOME`. `UIKA_JDK` may be a JDK home
or a `ct.sym` file, and the plugins export it themselves so that the release
they pass and the `ct.sym` the CLI reads come from one JVM.

`ct.sym` carries the releases below the JDK that ships it, never that JDK's own,
so the layered release has to be older than the JDK uika finds. That JDK's own
release comes from its `jmods/`, which a JDK upgrade check reads when one of its
sides is that release, so checking an upgrade *to* the JDK you now run needs
only that one JDK. Both sources are plain files, so uika still runs on no JVM.

A JDK upgrade check cannot see sealing changes, because `ct.sym` stubs do not
carry `PermittedSubclasses` and reporting them from the `jmods` side alone would
be a false positive.

## Not the same as checking a JDK upgrade

The layer puts one release under a library pair.
[A JDK upgrade check](../README.md#checking-a-jdk-upgrade) makes two releases
the compared pair instead, and reports what moving between them breaks.
