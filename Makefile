.PHONY: help build check test clean probe placeholder-check \
	rewrite rewrite-check coverage \
	java-cli-coverage gradle-coverage maven-coverage clojure-coverage \
	lein-coverage sbt-coverage mill-coverage bazel-coverage jacoco-tools \
	gradle-build gradle-check gradle-check-coverage gradle-test gradle-clean \
	java-cli-build java-cli-test java-cli-bless java-cli-clean \
	sbt-compile sbt-scripted sbt-clean \
	maven-verify maven-clean \
	mill-compile mill-test mill-clean \
	clojure-test clojure-clean clojure-stage \
	lein-test lein-clean lein-stage \
	bazel-unit-test bazel-test bazel-maven-test bazel-clean bazel-stage \
	cli-publish-local stage-all

JAVA ?= mise exec -- java
GRADLE ?= mise exec -- gradle
SBT ?= mise exec -- sbt
MAVEN ?= mise exec -- mvn
# Mill bootstraps its own distribution from the //| mill-version header, so mise only
# has to supply the JVM the launcher script runs on.
MILL ?= mise exec -- ./mill
CLOJURE ?= mise exec -- clojure
LEIN ?= mise exec -- lein
# Bazelisk, not bazel: bazel-rules/it/test-workspace/.bazelversion pins the release.
BAZELISK ?= mise exec -- bazelisk
GRADLE_PLUGIN_DIR ?= gradle-plugin
JAVA_CLI_DIR ?= cli-java
SBT_PLUGIN_DIR ?= sbt-plugin
MAVEN_PLUGIN_DIR ?= maven-plugin
MILL_PLUGIN_DIR ?= mill-plugin
CLOJURE_TOOL_DIR ?= clojure-tool
LEIN_PLUGIN_DIR ?= lein-plugin
BAZEL_RULES_DIR ?= bazel-rules
BAZEL_STAGE_DIR ?= dist/bazel
COVERAGE_DIR ?= target/coverage
# Neither sbt nor Mill has a JaCoCo binding to resolve an agent and write a report the way
# the Gradle and Maven builds do, so both jars are fetched here and the agent is handed to
# them as a path. dependency:copy needs no pom, so it runs from the repository root.
JACOCO_VERSION ?= 0.8.15
JACOCO_DIR ?= $(CURDIR)/target/jacoco
JACOCO_AGENT = $(JACOCO_DIR)/org.jacoco.agent-$(JACOCO_VERSION)-runtime.jar
JACOCO_CLI = $(JACOCO_DIR)/org.jacoco.cli-$(JACOCO_VERSION)-nodeps.jar
UIKA_VERSION ?= 0.0.0-dev
TMPDIR ?= /tmp
SBT_CACHE_DIR ?= $(TMPDIR)/uika-sbt
SBT_CLI_STUB = $(CURDIR)/$(SBT_PLUGIN_DIR)/target/uika-cli-path-stub
# run.sh copies the test workspace here; the path is stable so Bazel reuses one output base.
BAZEL_IT_DIR ?= $(TMPDIR)/uika-bazel-it
BAZEL_MAVEN_IT_DIR ?= $(TMPDIR)/uika-bazel-maven-it
SBT_FLAGS ?= -Dsbt.supershell=false -batch \
	-sbt-dir $(SBT_CACHE_DIR)/sbt-dir \
	-ivy $(SBT_CACHE_DIR)/ivy \
	-Dsbt.global.base=$(SBT_CACHE_DIR)/global \
	-Dsbt.boot.directory=$(SBT_CACHE_DIR)/boot

help:
	@printf '%s\n' \
		'Targets:' \
		'  make build        Build the CLI jar and the JVM build-tool plugins' \
		'  make test         Run the CLI and build-tool plugin tests' \
		'  make coverage     Write the coverage reports ci.yml uploads to Codecov' \
		'  make check        Run the lint checks and every test suite' \
		'  make clean        Remove every build output' \
		'' \
		'Useful direct targets:' \
		'  make probe        Answer-check fixture verdicts against a real JVM' \
		'  make rewrite      Apply OpenRewrite recipes to Java sources (rewrite-check verifies only)' \
		'  make java-cli-test' \
		'  make java-cli-bless   Rewrite the goldens from the current output' \
		'  make gradle-check' \
		'  make sbt-scripted' \
		'  make maven-verify' \
		'  make mill-test' \
		'  make clojure-test' \
		'  make lein-test' \
		'  make bazel-test' \
		'  make bazel-maven-test' \
		'  make cli-publish-local UIKA_VERSION=0.1.0' \
		'  make stage-all UIKA_VERSION=0.1.0'

build: java-cli-build gradle-build sbt-compile maven-verify mill-compile

# Every in-tree version placeholder must be 0.0.0-dev, which is structurally
# unpublishable. A plausible placeholder (0.1.0 was the old one in the JVM plugins) makes
# an UNSTAMPED local build embed it as the CLI default, silently fetching that uika-cli
# release from Central instead of failing the resolution loudly.
placeholder-check:
	@for f in gradle-plugin/build.gradle.kts sbt-plugin/build.sbt maven-plugin/pom.xml \
	  mill-plugin/build.mill lein-plugin/project.clj clojure-tool/build.clj \
	  cli-java/build.gradle.kts bazel-rules/private/version.bzl; do \
	  grep -q '0\.0\.0-dev' $$f || { echo "$$f lost its 0.0.0-dev version placeholder" >&2; exit 1; }; \
	done
	@echo "version placeholders: all 0.0.0-dev"

check: placeholder-check rewrite-check java-cli-test gradle-check sbt-scripted maven-verify mill-test clojure-test lein-test bazel-test bazel-maven-test

test: rewrite java-cli-test gradle-test sbt-scripted maven-verify mill-test clojure-test lein-test bazel-test bazel-maven-test

# Every front end; ci.yml uploads one flag per target.
coverage: java-cli-coverage gradle-coverage maven-coverage clojure-coverage lein-coverage \
	sbt-coverage mill-coverage bazel-coverage

jacoco-tools:
	@mkdir -p $(JACOCO_DIR)
	$(MAVEN) -q -B dependency:copy -DoutputDirectory=$(JACOCO_DIR) \
		-Dartifact=org.jacoco:org.jacoco.agent:$(JACOCO_VERSION):jar:runtime
	$(MAVEN) -q -B dependency:copy -DoutputDirectory=$(JACOCO_DIR) \
		-Dartifact=org.jacoco:org.jacoco.cli:$(JACOCO_VERSION):jar:nodeps

clean: java-cli-clean gradle-clean sbt-clean maven-clean mill-clean clojure-clean lein-clean bazel-clean

# The jar, with the launcher's relaunch switched off: the verdicts are what is checked,
# and a second JVM per scenario would only add start-up time.
probe: java-cli-build
	UIKA="$(JAVA) -Duika.child=true -jar $(JAVA_CLI_JAR)" JAVA="$(JAVA)" sh tools/jvm-probe/run-fixtures.sh

# Applies the OpenRewrite recipes in tools/openrewrite/rewrite.gradle, passed as an
# init script so the plugin builds stay untouched. Runs through the gradle-plugin
# build, which mounts jvm-plugin-core, so the shared sources and their symlink
# consumers are covered. The maven mojos, the bazel-rules mains and tools/jvm-probe
# sit outside every Gradle source set, so the recipes do not reach them.
rewrite:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) --init-script $(CURDIR)/tools/openrewrite/rewrite.gradle rewriteRun

rewrite-check:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) --init-script $(CURDIR)/tools/openrewrite/rewrite.gradle rewriteDryRun

# The version is passed so the jar's name is the one JAVA_CLI_JAR expects, whatever
# UIKA_VERSION the caller's environment holds.
JAVA_CLI_JAR = $(abspath $(JAVA_CLI_DIR)/build/libs/uika-cli-$(UIKA_VERSION).jar)
java-cli-build:
	$(GRADLE) -p $(JAVA_CLI_DIR) jar -PuikaVersion=$(UIKA_VERSION)

java-cli-test:
	$(GRADLE) -p $(JAVA_CLI_DIR) test

# Rewrites cli-java/tests/golden from the current output. Only after the diff is verified as an
# intended detection change, since the goldens are what catches an unintended one.
java-cli-bless:
	$(GRADLE) -p $(JAVA_CLI_DIR) test --tests 'net.exoego.uika.cli.GoldenTest' -PuikaBless=true

java-cli-clean:
	$(GRADLE) -p $(JAVA_CLI_DIR) clean

java-cli-coverage:
	$(GRADLE) -p $(JAVA_CLI_DIR) jacocoTestReport -PuikaCoverage=true

# Runs both CLIs over every jar in the local Gradle cache and compares stdout and exit
# codes. Not hermetic, so not part of check. MODE is dump, diff or check.
gradle-build:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) build

gradle-check:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) check

gradle-test:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) test

gradle-clean:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) clean

gradle-coverage:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) jacocoTestReport -PuikaCoverage=true

# gradle-check and gradle-coverage in one invocation, so the instrumented tests run once and
# the rest of check still runs. This is what ci.yml runs.
gradle-check-coverage:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) check jacocoTestReport -PuikaCoverage=true

# The binary the uika-cli-path scripted group points UIKA_CLI_PATH at. It only has to be
# an executable file that records its argv: the group asserts acquisition was skipped, not
# what a real check reports.
$(SBT_CLI_STUB):
	@mkdir -p $(dir $@)
	@printf '#!/bin/sh\nprintf "%%s " "$$@" > "$$3.args"\nexit 0\n' > $@
	@chmod +x $@

sbt-compile:
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) compile

# Two invocations, not one. The uika-cli-path group asserts that UIKA_CLI_PATH short-
# circuits CLI acquisition, and sbt's scripted framework has no per-test environment hook:
# scriptedLaunchOpts carries JVM options only, and the forked sbt inherits whatever ran it.
# Setting the variable for the whole run would defeat the uika group, whose upgrade-check
# test exists to exercise the resolver.
sbt-scripted: $(SBT_CLI_STUB)
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) checkClassFileVersions 'scripted uika/*'
	cd $(SBT_PLUGIN_DIR) && UIKA_CLI_PATH=$(SBT_CLI_STUB) $(SBT) $(SBT_FLAGS) 'scripted uika-cli-path/*'

sbt-clean:
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) clean

# The plugin classes only ever load in the sbt that `scripted` forks, which is why
# scriptedLaunchOpts is the only place an agent can go. jvm-plugin-core rides along on
# purpose: codecov.yml scores those paths as their own component, so this merges with the
# Gradle and Maven measurements of the same lines rather than competing with them.
SBT_JACOCO_DIR = $(SBT_PLUGIN_DIR)/target/jacoco
sbt-coverage: jacoco-tools $(SBT_CLI_STUB)
	rm -rf $(SBT_JACOCO_DIR)
	mkdir -p $(SBT_JACOCO_DIR)
	cd $(SBT_PLUGIN_DIR) && UIKA_JACOCO_AGENT=$(JACOCO_AGENT) \
		UIKA_JACOCO_EXEC=$(CURDIR)/$(SBT_JACOCO_DIR)/scripted.exec $(SBT) $(SBT_FLAGS) \
		checkClassFileVersions 'scripted uika/*'
	cd $(SBT_PLUGIN_DIR) && UIKA_JACOCO_AGENT=$(JACOCO_AGENT) UIKA_CLI_PATH=$(SBT_CLI_STUB) \
		UIKA_JACOCO_EXEC=$(CURDIR)/$(SBT_JACOCO_DIR)/scripted.exec $(SBT) $(SBT_FLAGS) 'scripted uika-cli-path/*'
	$(JAVA) -jar $(JACOCO_CLI) report $(SBT_JACOCO_DIR)/scripted.exec \
		--classfiles $(SBT_PLUGIN_DIR)/target/scala-2.12/sbt-1.0/classes \
		--sourcefiles $(SBT_PLUGIN_DIR)/src/main/scala \
		--sourcefiles jvm-plugin-core/src/main/java \
		--xml $(SBT_JACOCO_DIR)/jacoco.xml

# clean, because maven-compiler-plugin's incremental check does not treat a changed
# maven.compiler.release as an input. Without it a local floor edit recompiles nothing and
# the class-file guard green-lights the stale classes.
maven-verify:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B clean verify

maven-clean:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B clean

maven-coverage:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B -Pcoverage clean verify

mill-compile:
	cd $(MILL_PLUGIN_DIR) && $(MILL) compile

mill-test:
	cd $(MILL_PLUGIN_DIR) && $(MILL) test

mill-clean:
	cd $(MILL_PLUGIN_DIR) && $(MILL) clean

# `test`, never `testLocal`: the agent rides on forkArgs and testLocal forks nothing. `test`
# is a command rather than a cached task, so it cannot replay and report an empty exec file.
MILL_JACOCO_DIR = $(MILL_PLUGIN_DIR)/out/jacoco
mill-coverage: jacoco-tools
	rm -rf $(MILL_JACOCO_DIR)
	mkdir -p $(MILL_JACOCO_DIR)
	cd $(MILL_PLUGIN_DIR) && UIKA_JACOCO_AGENT=$(JACOCO_AGENT) \
		UIKA_JACOCO_EXEC=$(CURDIR)/$(MILL_JACOCO_DIR)/test.exec $(MILL) test
	$(JAVA) -jar $(JACOCO_CLI) report $(MILL_JACOCO_DIR)/test.exec \
		--classfiles $(MILL_PLUGIN_DIR)/out/compile.dest/classes \
		--sourcefiles $(MILL_PLUGIN_DIR)/src \
		--sourcefiles jvm-plugin-core/src/main/java \
		--xml $(MILL_JACOCO_DIR)/jacoco.xml

# java-cli-build supplies the real CLI for the round-trip integration test:
# the tool writes v2 JSON by hand instead of sharing DumpFormat, so only a run
# against the real CLI can catch the two drifting apart.
clojure-test: java-cli-build
	cd $(CLOJURE_TOOL_DIR) && $(CLOJURE) -T:build javac
	cd $(CLOJURE_TOOL_DIR) && UIKA_BIN=$(JAVA_CLI_JAR) $(CLOJURE) -M:test

# Cloverage writes SF: paths relative to this directory, so they need the prefix Codecov
# resolves from. Rewritten in place, which is safe because the run above regenerates the file.
# CLOJURE_LCOV is not a knob: cloverage is run without --output, so this is where it writes.
CLOJURE_LCOV = $(CLOJURE_TOOL_DIR)/target/coverage/lcov.info
clojure-coverage: java-cli-build
	cd $(CLOJURE_TOOL_DIR) && $(CLOJURE) -T:build javac
	cd $(CLOJURE_TOOL_DIR) && UIKA_BIN=$(JAVA_CLI_JAR) $(CLOJURE) -M:coverage
	sed 's|^SF:|SF:$(CLOJURE_TOOL_DIR)/|' $(CLOJURE_LCOV) > $(CLOJURE_LCOV).tmp
	mv $(CLOJURE_LCOV).tmp $(CLOJURE_LCOV)

clojure-clean:
	rm -rf $(CLOJURE_TOOL_DIR)/.cpcache $(CLOJURE_TOOL_DIR)/target

clojure-stage:
	cd $(CLOJURE_TOOL_DIR) && UIKA_VERSION=$(UIKA_VERSION) $(CLOJURE) -T:build stage

# Real-CLI round trip, same reason as clojure-test: the dump JSON is hand-written.
# mise exec puts lein itself on PATH for the script.
# The unit suite runs here too, not only under lein-coverage: a test reached by nothing
# but the coverage target is a test that rots without failing anything.
lein-test: java-cli-build
	cd $(LEIN_PLUGIN_DIR) && $(LEIN) test
	UIKA_BIN=$(JAVA_CLI_JAR) UIKA_IT_ALT_JAVA=$(UIKA_IT_ALT_JAVA) \
		mise exec -- sh $(LEIN_PLUGIN_DIR)/it/run.sh

# Cloverage instruments namespaces in the JVM it reports from, so it drives the unit
# suite; it/run.sh forks `lein uika` as a child process and can be measured by nothing.
# -p src only: ../clojure-tool/src-core is on :source-paths but belongs to the Clojure
# tool's component, and clojure-coverage already measures it.
# The SF: paths come out relative to lein-plugin, so they need the prefix Codecov
# resolves from, exactly as clojure-coverage does it. Rewritten in place, which is safe
# because the run above regenerates the file. LEIN_LCOV is not a knob: cloverage is run
# without --output, so this is where it writes.
LEIN_LCOV = $(LEIN_PLUGIN_DIR)/target/coverage/lcov.info
lein-coverage:
	cd $(LEIN_PLUGIN_DIR) && $(LEIN) with-profile +coverage run -m cloverage.coverage -- \
		-p src -s test --lcov --no-html
	sed 's|^SF:|SF:$(LEIN_PLUGIN_DIR)/|' $(LEIN_LCOV) > $(LEIN_LCOV).tmp
	mv $(LEIN_LCOV).tmp $(LEIN_LCOV)

lein-clean:
	rm -rf $(LEIN_PLUGIN_DIR)/target $(LEIN_PLUGIN_DIR)/it/test-project/target $(LEIN_PLUGIN_DIR)/pom.xml

# update-in :repositories empty: lein emits <repositories> into the pom, which
# PomChecker rejects ("The <repositories> block should not be present") and
# jreleaser.yml's applyMavenCentralRules turns into a failed release. Emptying the
# key on the project map instead of in project.clj keeps the plugin's own deps
# resolvable, because :eval-in-leiningen loads them the way plugins are loaded, and
# load-plugins merges :plugin-repositories into the key update-in just emptied.
# Sources and javadoc jars come from :classifiers.
#
# The separate javac line is what update-in cannot cover: leiningen compiles Java in a
# SUBPROCESS, whose profile carries ^:displace [org.clojure/clojure <lein's own version>]
# and resolves it through :repositories, not :plugin-repositories. So it needs a remote
# unless the artifact is already in ~/.m2 -- which it is on any machine that has built
# this plugin once, and is not on a release runner. That is why v0.9.0 died here with
# "Could not find artifact org.clojure:clojure:jar:1.12.2" after passing locally.
# Compiling first, with the repositories still in place, puts the jar in the local repo;
# the deploy's own javac then resolves it offline. Declaring clojure in :dependencies
# instead does NOT displace lein's copy (tested), and would put clojure in the pom.
lein-stage:
	cd $(LEIN_PLUGIN_DIR) && UIKA_VERSION=$(UIKA_VERSION) $(LEIN) javac
	cd $(LEIN_PLUGIN_DIR) && UIKA_VERSION=$(UIKA_VERSION) $(LEIN) \
		update-in :repositories empty -- deploy staging

# The ruleset's own Java classes, in the module's own workspace. No network: the test
# carries a plain main() rather than a JUnit suite precisely so the ruleset keeps
# rules_java as its only dependency.
# --symlink_prefix=/ suppresses the bazel-out/bazel-bin convenience symlinks. Without it
# this target plants them in the ruleset itself, and stage.sh copies the module with
# `cp -RL`, which DEREFERENCES them -- the release archive would carry the whole output
# base. .gitignore's note that "the integration test runs in a temp copy and never creates
# them here" is what this target would otherwise falsify.
bazel-unit-test:
	cd $(BAZEL_RULES_DIR) && $(BAZELISK) test --symlink_prefix=/ //java:manifest_test

# TWO measurements, because no one tool sees both halves. Bazel's own JaCoCo covers
# //java:manifest_test and nothing else: every other entry point in this ruleset runs under
# `bazel run` in the integration test's throwaway workspace, which `bazel coverage` has no
# view of. So the ITs are instrumented by hand, the way sbt and Mill are and for the same
# reason, with the agent reaching each java_binary through a --jvmopt line the IT writes
# into the workspace .bazelrc. One check invocation alone takes UpgradeCheckMain from 13/69
# to 37/69, so leaving them out was not a rounding difference.
#
# Both reports land outside the module because stage.sh cuts the release archive with
# `cp -RL` and would carry them along. ci.yml uploads the pair under one flag and Codecov
# takes the union, the same shape the Maven plugin's surefire and invoker reports use.
BAZEL_IT_JACOCO = $(CURDIR)/$(COVERAGE_DIR)/bazel-it.exec
bazel-coverage: jacoco-tools java-cli-build
	mkdir -p $(COVERAGE_DIR)
	cd $(BAZEL_RULES_DIR) && $(BAZELISK) coverage --symlink_prefix=/ \
		--combined_report=lcov //java:manifest_test
	sed 's|^SF:|SF:$(BAZEL_RULES_DIR)/|' \
		"`cd $(BAZEL_RULES_DIR) && $(BAZELISK) info --symlink_prefix=/ output_path`/_coverage/_coverage_report.dat" \
		> $(COVERAGE_DIR)/bazel.lcov
	UIKA_BIN=$(JAVA_CLI_JAR) UIKA_JACOCO_AGENT=$(JACOCO_AGENT) \
		UIKA_JACOCO_EXEC=$(BAZEL_IT_JACOCO) mise exec -- sh $(BAZEL_RULES_DIR)/it/run.sh
	mise exec -- sh $(BAZEL_RULES_DIR)/it/jacoco-report.sh $(JACOCO_CLI) \
		$(BAZEL_IT_JACOCO) $(BAZEL_IT_DIR)/ws $(CURDIR)/$(BAZEL_RULES_DIR)/java \
		$(CURDIR)/$(COVERAGE_DIR)/bazel-it.xml

# Real-CLI round trip, same reason as clojure-test and lein-test: the dump is written by
# a tool the Rust side never sees, so only a run against the real binary catches drift.
bazel-test: java-cli-build bazel-unit-test
	UIKA_BIN=$(JAVA_CLI_JAR) mise exec -- sh $(BAZEL_RULES_DIR)/it/run.sh

# The pairing the Bazel issue is named after. Split from bazel-test because this one needs
# the network: the two lock files are pinned so nothing is resolved, but the artifacts
# themselves come from Maven Central.
bazel-maven-test: java-cli-build
	UIKA_BIN=$(JAVA_CLI_JAR) mise exec -- sh $(BAZEL_RULES_DIR)/it/run-maven.sh

# The workspace copies are disposable, but their output bases are not: expunge through each
# copy while it still exists, or Bazel keeps a multi-gigabyte tree for a directory that is
# gone.
bazel-clean:
	@if [ -d "$(BAZEL_RULES_DIR)/bazel-out" ] || [ -d "$$HOME/.cache/bazel" ]; then \
		cd $(BAZEL_RULES_DIR) && $(BAZELISK) clean --expunge >/dev/null 2>&1 || true; \
	fi
	@rm -rf $(BAZEL_RULES_DIR)/bazel-bin $(BAZEL_RULES_DIR)/bazel-out \
		$(BAZEL_RULES_DIR)/bazel-testlogs $(BAZEL_RULES_DIR)/bazel-bazel-rules
	@for dir in $(BAZEL_IT_DIR) $(BAZEL_MAVEN_IT_DIR); do \
		if [ -d "$$dir/ws" ]; then \
			cd $$dir/ws && $(BAZELISK) clean --expunge >/dev/null 2>&1 || true; \
		fi; \
	done
	rm -rf $(BAZEL_IT_DIR) $(BAZEL_MAVEN_IT_DIR)

# The Bazel module is distributed as a release archive rather than through a registry, so
# staging it is a tarball rather than a deploy. The archive pins the checksum of the CLI
# jar. In a release the jar is already there, built by the uika-cli staging step, and Gradle
# leaves an up-to-date jar alone, so the hash is of the very file that goes to Central.
bazel-stage: java-cli-build
	sh $(BAZEL_RULES_DIR)/stage.sh $(UIKA_VERSION) $(BAZEL_RULES_DIR) \
		$(BAZEL_STAGE_DIR) $(JAVA_CLI_JAR)

cli-publish-local:
	$(GRADLE) -p $(JAVA_CLI_DIR) publishToMavenLocal -PuikaVersion=$(UIKA_VERSION)

# Stage every Maven artifact locally; JReleaser signs and uploads the result
# (see jreleaser.yml).
stage-all:
	$(GRADLE) -p $(JAVA_CLI_DIR) publishAllPublicationsToStagingRepository -PuikaVersion=$(UIKA_VERSION)
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) publishAllPublicationsToStagingRepository -PuikaVersion=$(UIKA_VERSION)
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) 'set ThisBuild / version := "$(UIKA_VERSION)"' publish
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B -Prelease -Drevision=$(UIKA_VERSION) -DskipTests -Dinvoker.skip=true deploy
	cd $(MILL_PLUGIN_DIR) && UIKA_VERSION=$(UIKA_VERSION) $(MILL) publishM2Local + stageChecksums
	$(MAKE) clojure-stage UIKA_VERSION=$(UIKA_VERSION)
	$(MAKE) lein-stage UIKA_VERSION=$(UIKA_VERSION)
	$(MAKE) bazel-stage UIKA_VERSION=$(UIKA_VERSION)
