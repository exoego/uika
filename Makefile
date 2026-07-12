.PHONY: help build check test fmt fmt-check clean \
	cargo-build cargo-release cargo-test cargo-fmt cargo-fmt-check \
	gradle-build gradle-check gradle-test gradle-clean \
	sbt-compile sbt-scripted sbt-clean \
	maven-verify maven-clean \
	native-publish-local stage-all

CARGO ?= cargo
GRADLE ?= mise exec -- gradle
SBT ?= mise exec -- sbt
MAVEN ?= mise exec -- mvn
GRADLE_PLUGIN_DIR ?= gradle-plugin
SBT_PLUGIN_DIR ?= sbt-plugin
MAVEN_PLUGIN_DIR ?= maven-plugin
UIKA_VERSION ?= $(shell sed -n 's/^version = "\(.*\)"/\1/p' cli/Cargo.toml | head -1)
TMPDIR ?= /tmp
SBT_CACHE_DIR ?= $(TMPDIR)/uika-sbt
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
		'  make check        Run formatting checks and all plugin checks' \
		'  make fmt          Format Rust sources' \
		'  make clean        Remove Rust and JVM plugin build outputs' \
		'' \
		'Useful direct targets:' \
		'  make cargo-release' \
		'  make gradle-check' \
		'  make sbt-scripted' \
		'  make maven-verify' \
		'  make native-publish-local UIKA_VERSION=0.1.0' \
		'  make stage-all UIKA_VERSION=0.1.0'

build: cargo-build gradle-build sbt-compile maven-verify

check: cargo-fmt-check cargo-test gradle-check sbt-scripted maven-verify

test: cargo-test gradle-test sbt-scripted maven-verify

fmt: cargo-fmt

fmt-check: cargo-fmt-check

clean: gradle-clean sbt-clean maven-clean
	$(CARGO) clean

cargo-build:
	$(CARGO) build

cargo-release:
	$(CARGO) build --release

cargo-test:
	$(CARGO) test

cargo-fmt:
	$(CARGO) fmt

cargo-fmt-check:
	$(CARGO) fmt -- --check

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
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) scripted

sbt-clean:
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) clean

maven-verify:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B verify

maven-clean:
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B clean

native-publish-local:
	$(GRADLE) -p binary-publishing publishToMavenLocal -PuikaVersion=$(UIKA_VERSION)

# Stage every Maven artifact locally; JReleaser signs and uploads the result
# (see jreleaser.yml). binary-publishing expects ZIPs under dist/native/<classifier>/.
stage-all:
	$(GRADLE) -p binary-publishing publishAllPublicationsToStagingRepository -PuikaVersion=$(UIKA_VERSION)
	$(GRADLE) -p $(GRADLE_PLUGIN_DIR) publishAllPublicationsToStagingRepository -PuikaVersion=$(UIKA_VERSION)
	cd $(SBT_PLUGIN_DIR) && $(SBT) $(SBT_FLAGS) 'set ThisBuild / version := "$(UIKA_VERSION)"' publish
	$(MAVEN) -f $(MAVEN_PLUGIN_DIR)/pom.xml -B -Prelease -Drevision=$(UIKA_VERSION) -DskipTests -Dinvoker.skip=true deploy
