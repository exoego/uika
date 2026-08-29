.PHONY: help build check test fmt fmt-check clean probe placeholder-check \
	rewrite rewrite-check \
	cargo-build cargo-release cargo-test cargo-clippy cargo-fmt cargo-fmt-check \
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
UIKA_VERSION ?= $(shell sed -n 's/^version = "\(.*\)"/\1/p' cli/Cargo.toml | head -1)
TMPDIR ?= /tmp
SBT_CACHE_DIR ?= $(TMPDIR)/uika-sbt
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

sbt-compile:
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) compile

sbt-scripted:
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) checkClassFileVersions scripted

sbt-clean:
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) clean

# clean, because maven-compiler-plugin's incremental check does not treat a changed
# maven.compiler.release as an input. Without it a local floor edit recompiles nothing and
# the class-file guard green-lights the stale classes.
maven-verify:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B clean verify

maven-clean:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B clean

mill-compile:
	cd $(MILL_PLUGIN_DIR) && $(MILL) compile

mill-test:
	cd $(MILL_PLUGIN_DIR) && $(MILL) test

mill-clean:
	cd $(MILL_PLUGIN_DIR) && $(MILL) clean

# cargo-build supplies the real binary for the round-trip integration test:
# the tool writes v2 JSON by hand instead of sharing DumpFormat, so only a run
# against the real CLI can catch the two drifting apart.
clojure-test: cargo-build
	cd $(CLOJURE_TOOL_DIR) && $(CLOJURE) -T:build javac
	cd $(CLOJURE_TOOL_DIR) && UIKA_BIN=$(abspath target/debug/uika) $(CLOJURE) -M:test

clojure-clean:
	rm -rf $(CLOJURE_TOOL_DIR)/.cpcache $(CLOJURE_TOOL_DIR)/target

clojure-stage:
	cd $(CLOJURE_TOOL_DIR) && UIKA_VERSION=$(UIKA_VERSION) $(CLOJURE) -T:build stage

# Real-CLI round trip, same reason as clojure-test: the dump JSON is hand-written.
# mise exec puts lein itself on PATH for the script.
lein-test: cargo-build
	UIKA_BIN=$(abspath target/debug/uika) UIKA_IT_ALT_JAVA=$(UIKA_IT_ALT_JAVA) \
		mise exec -- sh $(LEIN_PLUGIN_DIR)/it/run.sh

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
