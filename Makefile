.PHONY: help build check test fmt fmt-check clean probe placeholder-check \
	rewrite rewrite-check coverage \
	cargo-build cargo-release cargo-test cargo-clippy cargo-fmt cargo-fmt-check \
	cargo-coverage gradle-coverage maven-coverage clojure-coverage lein-coverage \
	sbt-coverage mill-coverage bazel-coverage jacoco-tools \
	gradle-build gradle-check gradle-test gradle-clean \
	sbt-compile sbt-scripted sbt-clean \
	maven-verify maven-clean \
	mill-compile mill-test mill-clean \
	clojure-test clojure-clean clojure-stage \
	lein-test lein-clean lein-stage \
	bazel-test bazel-maven-test bazel-clean bazel-stage \
	native-publish-local stage-all

CARGO ?= cargo
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
UIKA_VERSION ?= $(shell sed -n 's/^version = "\(.*\)"/\1/p' cli/Cargo.toml | head -1)
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
		'  make build        Build Rust CLI and JVM build-tool plugins' \
		'  make test         Run Rust tests and JVM build-tool plugin tests' \
		'  make coverage     Write the coverage reports ci.yml uploads to Codecov' \
		'  make check        Run formatting and lint checks and all plugin checks' \
		'  make fmt          Format Rust sources' \
		'  make clean        Remove Rust and JVM plugin build outputs' \
		'' \
		'Useful direct targets:' \
		'  make cargo-release' \
		'  make probe        Answer-check fixture verdicts against a real JVM' \
		'  make rewrite      Apply OpenRewrite recipes to Java sources (rewrite-check verifies only)' \
		'  make gradle-check' \
		'  make sbt-scripted' \
		'  make maven-verify' \
		'  make mill-test' \
		'  make clojure-test' \
		'  make lein-test' \
		'  make bazel-test' \
		'  make bazel-maven-test' \
		'  make native-publish-local UIKA_VERSION=0.1.0' \
		'  make stage-all UIKA_VERSION=0.1.0'

build: cargo-build gradle-build sbt-compile maven-verify mill-compile

# Every in-tree version placeholder must be 0.0.0-dev, which is structurally
# unpublishable. A plausible placeholder (0.1.0 was the old one in the JVM plugins) makes
# an UNSTAMPED local build embed it as the CLI default, silently fetching that uika-cli
# release from Central instead of failing the resolution loudly.
placeholder-check:
	@for f in gradle-plugin/build.gradle.kts sbt-plugin/build.sbt maven-plugin/pom.xml \
	  mill-plugin/build.mill lein-plugin/project.clj clojure-tool/build.clj \
	  cli/Cargo.toml bazel-rules/private/version.bzl; do \
	  grep -q '0\.0\.0-dev' $$f || { echo "$$f lost its 0.0.0-dev version placeholder" >&2; exit 1; }; \
	done
	@echo "version placeholders: all 0.0.0-dev"

check: placeholder-check rewrite-check cargo-fmt-check cargo-clippy cargo-test gradle-check sbt-scripted maven-verify mill-test clojure-test lein-test bazel-test bazel-maven-test

test: rewrite cargo-test gradle-test sbt-scripted maven-verify mill-test clojure-test lein-test bazel-test bazel-maven-test

# Every front end; ci.yml uploads one flag per target.
coverage: cargo-coverage gradle-coverage maven-coverage clojure-coverage lein-coverage \
	sbt-coverage mill-coverage bazel-coverage

jacoco-tools:
	@mkdir -p $(JACOCO_DIR)
	$(MAVEN) -q -B dependency:copy -DoutputDirectory=$(JACOCO_DIR) \
		-Dartifact=org.jacoco:org.jacoco.agent:$(JACOCO_VERSION):jar:runtime
	$(MAVEN) -q -B dependency:copy -DoutputDirectory=$(JACOCO_DIR) \
		-Dartifact=org.jacoco:org.jacoco.cli:$(JACOCO_VERSION):jar:nodeps

fmt: cargo-fmt

fmt-check: cargo-fmt-check

clean: gradle-clean sbt-clean maven-clean mill-clean clojure-clean lein-clean bazel-clean
	$(CARGO) clean

cargo-build:
	$(CARGO) build

cargo-release:
	$(CARGO) build --release

cargo-test:
	$(CARGO) test

cargo-clippy:
	$(CARGO) clippy --all-targets --all-features

cargo-fmt:
	$(CARGO) fmt

cargo-fmt-check:
	$(CARGO) fmt -- --check

# --remap-path-prefix makes the lcov SF: paths repo-root relative, which is what Codecov
# resolves against. Without it they are absolute and machine-specific.
cargo-coverage:
	mkdir -p $(COVERAGE_DIR)
	$(CARGO) llvm-cov --workspace --locked --remap-path-prefix \
		--lcov --output-path $(COVERAGE_DIR)/lcov.info

# Debug binary on purpose: probe verdicts are optimization-independent, the
# fixtures are tiny, and cargo test has already built target/debug in CI.
probe: cargo-build
	UIKA=target/debug/uika JAVA="$(JAVA)" sh tools/jvm-probe/run-fixtures.sh

# Applies the OpenRewrite recipes in tools/openrewrite/rewrite.gradle, passed as an
# init script so the plugin builds stay untouched. Runs through the gradle-plugin
# build, which mounts jvm-plugin-core, so the shared sources and their symlink
# consumers are covered. The maven mojos, the bazel-rules mains and tools/jvm-probe
# sit outside every Gradle source set, so the recipes do not reach them.
rewrite:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) --init-script $(CURDIR)/tools/openrewrite/rewrite.gradle rewriteRun

rewrite-check:
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) --init-script $(CURDIR)/tools/openrewrite/rewrite.gradle rewriteDryRun

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
		UIKA_JACOCO_EXEC=$(CURDIR)/$(SBT_JACOCO_DIR)/scripted.exec $(SBT) $(SBT_FLAGS) 'scripted uika/*'
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

# cargo-build supplies the real binary for the round-trip integration test:
# the tool writes v2 JSON by hand instead of sharing DumpFormat, so only a run
# against the real CLI can catch the two drifting apart.
clojure-test: cargo-build
	cd $(CLOJURE_TOOL_DIR) && $(CLOJURE) -T:build javac
	cd $(CLOJURE_TOOL_DIR) && UIKA_BIN=$(abspath target/debug/uika) $(CLOJURE) -M:test

# Cloverage writes SF: paths relative to this directory, so they need the prefix Codecov
# resolves from. Rewritten in place, which is safe because the run above regenerates the file.
# CLOJURE_LCOV is not a knob: cloverage is run without --output, so this is where it writes.
CLOJURE_LCOV = $(CLOJURE_TOOL_DIR)/target/coverage/lcov.info
clojure-coverage: cargo-build
	cd $(CLOJURE_TOOL_DIR) && $(CLOJURE) -T:build javac
	cd $(CLOJURE_TOOL_DIR) && UIKA_BIN=$(abspath target/debug/uika) $(CLOJURE) -M:coverage
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
lein-test: cargo-build
	cd $(LEIN_PLUGIN_DIR) && $(LEIN) test
	UIKA_BIN=$(abspath target/debug/uika) UIKA_IT_ALT_JAVA=$(UIKA_IT_ALT_JAVA) \
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
# key on the project map instead of in project.clj keeps resolution working, because
# :eval-in-leiningen has already put the plugin's own deps on the classpath by the
# time update-in rewrites the map. Sources and javadoc jars come from :classifiers.
lein-stage:
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

# Bazel's own JaCoCo, so nothing from jacoco-tools is involved. The report lands outside the
# module because stage.sh cuts the release archive with `cp -RL` and would carry it along.
# The shell ITs are not in this number: they drive `bazel run` in a temp workspace, so the
# mains stay at zero and only the manifest test's reach is measured.
bazel-coverage:
	mkdir -p $(COVERAGE_DIR)
	cd $(BAZEL_RULES_DIR) && $(BAZELISK) coverage --symlink_prefix=/ \
		--combined_report=lcov //java:manifest_test
	sed 's|^SF:|SF:$(BAZEL_RULES_DIR)/|' \
		"`cd $(BAZEL_RULES_DIR) && $(BAZELISK) info --symlink_prefix=/ output_path`/_coverage/_coverage_report.dat" \
		> $(COVERAGE_DIR)/bazel.lcov

# Real-CLI round trip, same reason as clojure-test and lein-test: the dump is written by
# a tool the Rust side never sees, so only a run against the real binary catches drift.
bazel-test: cargo-build bazel-unit-test
	UIKA_BIN=$(abspath target/debug/uika) mise exec -- sh $(BAZEL_RULES_DIR)/it/run.sh

# The pairing the Bazel issue is named after. Split from bazel-test because this one needs
# the network: the two lock files are pinned so nothing is resolved, but the artifacts
# themselves come from Maven Central.
bazel-maven-test: cargo-build
	UIKA_BIN=$(abspath target/debug/uika) mise exec -- sh $(BAZEL_RULES_DIR)/it/run-maven.sh

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
# staging it is a tarball rather than a deploy. It must run AFTER the native binaries are
# in dist/native, because that is where the CLI checksums the archive pins come from.
bazel-stage:
	sh $(BAZEL_RULES_DIR)/stage.sh $(UIKA_VERSION) $(BAZEL_RULES_DIR) \
		$(BAZEL_STAGE_DIR) dist/native

native-publish-local:
	$(GRADLE) -p binary-publishing publishToMavenLocal -PuikaVersion=$(UIKA_VERSION)

# Stage every Maven artifact locally; JReleaser signs and uploads the result
# (see jreleaser.yml). binary-publishing expects ZIPs under dist/native/<classifier>/.
stage-all:
	$(GRADLE) -p binary-publishing publishAllPublicationsToStagingRepository -PuikaVersion=$(UIKA_VERSION)
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) publishAllPublicationsToStagingRepository -PuikaVersion=$(UIKA_VERSION)
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) 'set ThisBuild / version := "$(UIKA_VERSION)"' publish
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B -Prelease -Drevision=$(UIKA_VERSION) -DskipTests -Dinvoker.skip=true deploy
	cd $(MILL_PLUGIN_DIR) && UIKA_VERSION=$(UIKA_VERSION) $(MILL) publishM2Local + stageChecksums
	$(MAKE) clojure-stage UIKA_VERSION=$(UIKA_VERSION)
	$(MAKE) lein-stage UIKA_VERSION=$(UIKA_VERSION)
	$(MAKE) bazel-stage UIKA_VERSION=$(UIKA_VERSION)
