.PHONY: help build check test fmt fmt-check clean probe \
	cargo-build cargo-release cargo-test cargo-clippy cargo-fmt cargo-fmt-check \
	gradle-build gradle-check gradle-test gradle-clean \
	sbt-compile sbt-scripted sbt-clean \
	maven-verify maven-clean \
	mill-compile mill-test mill-clean \
	clojure-test clojure-clean \
	lein-test lein-clean lein-stage \
	bazel-test bazel-clean bazel-stage \
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
		'  make gradle-check' \
		'  make sbt-scripted' \
		'  make maven-verify' \
		'  make mill-test' \
		'  make clojure-test' \
		'  make lein-test' \
		'  make bazel-test' \
		'  make native-publish-local UIKA_VERSION=0.1.0' \
		'  make stage-all UIKA_VERSION=0.1.0'

build: cargo-build gradle-build sbt-compile maven-verify mill-compile

check: cargo-fmt-check cargo-clippy cargo-test gradle-check sbt-scripted maven-verify mill-test clojure-test lein-test bazel-test

test: cargo-test gradle-test sbt-scripted maven-verify mill-test clojure-test lein-test bazel-test

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

maven-verify:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B verify

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
	cd $(CLOJURE_TOOL_DIR) && UIKA_BIN=$(abspath target/debug/uika) $(CLOJURE) -M:test

clojure-clean:
	rm -rf $(CLOJURE_TOOL_DIR)/.cpcache

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

# Real-CLI round trip, same reason as clojure-test and lein-test: the dump is written by
# a tool the Rust side never sees, so only a run against the real binary catches drift.
bazel-test: cargo-build
	UIKA_BIN=$(abspath target/debug/uika) mise exec -- sh $(BAZEL_RULES_DIR)/it/run.sh

# The workspace copy is disposable, but its output base is not: expunge through the copy
# while it still exists, or Bazel keeps a multi-gigabyte tree for a directory that is gone.
bazel-clean:
	@if [ -d "$(BAZEL_IT_DIR)/ws" ]; then \
		cd $(BAZEL_IT_DIR)/ws && $(BAZELISK) clean --expunge >/dev/null 2>&1 || true; \
	fi
	rm -rf $(BAZEL_IT_DIR)

# The Bazel module is distributed as a release archive rather than through a registry, so
# staging it is a tarball rather than a deploy. Copied with -L first, because the four
# jvm-plugin-core sources under bazel-rules/java are symlinks and an archive of symlinks
# pointing out of the module root is useless to a consumer. The version is stamped into the
# copy, the same way UIKA_VERSION is stamped into the CLI, so git keeps the placeholder.
bazel-stage:
	rm -rf $(BAZEL_STAGE_DIR)
	mkdir -p $(BAZEL_STAGE_DIR)
	cp -RL $(BAZEL_RULES_DIR) $(BAZEL_STAGE_DIR)/bazel-rules
	rm -rf $(BAZEL_STAGE_DIR)/bazel-rules/it
	sed -i.bak 's/0\.0\.0-dev/$(UIKA_VERSION)/' \
		$(BAZEL_STAGE_DIR)/bazel-rules/MODULE.bazel \
		$(BAZEL_STAGE_DIR)/bazel-rules/private/version.bzl
	rm -f $(BAZEL_STAGE_DIR)/bazel-rules/MODULE.bazel.bak \
		$(BAZEL_STAGE_DIR)/bazel-rules/private/version.bzl.bak
	tar -czf $(BAZEL_STAGE_DIR)/uika-bazel-$(UIKA_VERSION).tar.gz \
		-C $(BAZEL_STAGE_DIR) bazel-rules

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
	$(MAKE) lein-stage UIKA_VERSION=$(UIKA_VERSION)
	$(MAKE) bazel-stage UIKA_VERSION=$(UIKA_VERSION)
