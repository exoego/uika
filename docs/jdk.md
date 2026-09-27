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

## Where the stubs come from

The stubs come from the `ct.sym` file of the JDK named by `UIKA_JDK`, checked
first and authoritative when set, else `JAVA_HOME`. `UIKA_JDK` may be a JDK home
or a `ct.sym` file, and the plugins export it themselves so that the release
they pass and the `ct.sym` the CLI reads come from one JVM.

`ct.sym` carries every release below the JDK that ships it, and from JDK 22 on
that JDK's own release too. The layered release has to be one of those, so it
cannot be newer than the JDK uika finds, and on JDK 21 and earlier it has to be
older. A JDK upgrade check reads both of its sides from `ct.sym` as well. Only
on JDK 21 and earlier does a side that names the JDK's own release come from
its `jmods/` instead. Either way, checking an upgrade *to* the JDK you now run
needs only that one JDK. From JDK 22 on it does not need `jmods/`, which
Temurin 25 does not ship. Both sources are plain files, so uika reads them
without starting that JDK.

The JDK uika finds has to be at least as new as the release you move to. By
default a plugin hands uika the JDK the build runs on, so a change that moves a
module above that JDK fails the check with exit code 2 until the build runs on
the newer one.

Stubs from JDK 22 on carry `PermittedSubclasses`, so a JDK upgrade check sees a
class that became sealed when the JDK uika finds is 22 or later. Stubs from JDK
21 and earlier do not carry it, so on those JDKs the check cannot see sealing
changes. It drops the attribute from the `jmods/` side too, since reporting
sealing from that side alone would be a false positive.

## Not the same as checking a JDK upgrade

The layer puts one release under a library pair.
[A JDK upgrade check](../README.md#checking-a-jdk-upgrade) makes two releases
the compared pair instead, and reports what moving between them breaks.
