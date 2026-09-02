(ns exoego.uika-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [exoego.uika :as uika]
            [exoego.uika.core :as uika.core])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def fixture (str (io/file "test-resources" "fixture")))

(defn- temp-dir []
  (str (Files/createTempDirectory "uika-test" (make-array FileAttribute 0))))

(defn- executable-stub
  "A runnable no-op binary under `dir`. resolve-binary now refuses a path that is not an
  executable file, so a test that only needs \"some binary\" has to create a real one."
  [dir name]
  (let [binary (io/file dir name)]
    (spit binary "#!/bin/sh\nexit 0\n")
    (.setExecutable binary true false)
    binary))

(deftest dump-records-coordinates-and-local-deps
  (let [out (str (io/file (temp-dir) "dump.json"))
        _ (uika/dump-classpath {:dir fixture :output out})
        dump (json/read-str (slurp out))
        artifacts (get dump "artifacts")
        module (first (get dump "modules"))]
    (is (= 2 (get dump "version")))
    (is (pos? (get dump "jdkRelease")))
    (is (= ":fixture" (get module "module")))
    ;; Maven libs carry full coordinates and the resolved jar path.
    (is (some (fn [a] (and (= "org.apache.commons" (get a "group"))
                           (= "commons-lang3" (get a "name"))
                           (= "3.20.0" (get a "version"))
                           (str/ends-with? (get a "path") "commons-lang3-3.20.0.jar")))
              artifacts))
    ;; A :local/root dep has no Maven coordinates the version diff could compare,
    ;; so it is emitted coordinate-less, like the JVM plugins' project deps.
    (is (some (fn [a] (and (nil? (get a "group"))
                           (str/includes? (get a "path") "shared")))
              artifacts))
    ;; The project's own source dirs are the module's classesDirs.
    (is (some #(str/ends-with? (get % "path") "src")
              (get module "classesDirs")))))

(deftest jdk-release-option-overrides-the-running-jvm
  ;; The tool runs project code on its own JVM, so that is the right default, but a
  ;; project built here and shipped on another release can only say so by hand. 0 keeps
  ;; its "switch the API layer off" meaning and leaves the recorded release derived.
  (let [release (fn [args]
                  (let [out (str (io/file (temp-dir) "dump.json"))]
                    (uika/dump-classpath (merge {:dir fixture :output out} args))
                    (json/read-str (slurp out))))]
    (let [dump (release {:jdk-release 11})]
      (is (= 11 (get dump "jdkRelease")))
      (is (= 11 (get (first (get dump "modules")) "jdkRelease"))))
    (is (= (.feature (Runtime/version)) (get (release {:jdk-release 0}) "jdkRelease")))))

(deftest jvm-properties-survive-a-spaced-java-home
  ;; `C:\Program Files\...` is the everyday case. A \S+ capture truncates at the
  ;; space, the ct.sym probe then misses, and the JDK API layer switches itself off
  ;; blaming a missing ct.sym -- silent, and wrong about the reason.
  (let [output (str "    java.home = /Library/Java/My JDK/Contents/Home\n"
                    "    java.specification.version = 21\n")]
    (is (= {:home "/Library/Java/My JDK/Contents/Home" :feature 21}
           (uika.core/parse-jvm-properties output))))
  (testing "a probe that produced nothing usable is nil, so callers can fall back"
    (is (nil? (uika.core/parse-jvm-properties "")))
    (is (nil? (uika.core/parse-jvm-properties "java.home = /x\n")))
    (is (nil? (uika.core/parse-jvm-properties
               "java.home = /x\njava.specification.version = twenty\n")))))

(deftest jvm-properties-parse-the-jdk8-spelling
  ;; JDK 8 says java.specification.version = 1.8, which a bare Long/parseLong cannot
  ;; read. A nil probe makes lein fall back to its OWN JVM and the dump then records the
  ;; writing JVM's release -- the issue #128 mis-attribution the probe exists to prevent.
  ;; 8 is inside the supported range (MIN_RELEASE is 8), so the spelling must parse.
  (is (= {:home "/opt/jdk8" :feature 8}
         (uika.core/parse-jvm-properties
          "java.home = /opt/jdk8\njava.specification.version = 1.8\n"))))

(deftest declared-release-reads-the-javac-spellings
  ;; Port of UikaCli.declaredRelease, for lein's :javac-options: the spelling that pins
  ;; the API must win over the probed runtime JVM, or a project compiling --release 8 on
  ;; a 21 JVM is over-claimed and findings are lost with nothing to show.
  (is (= 11 (uika.core/declared-release ["--release" "11"])))
  (is (= 11 (uika.core/declared-release ["--release=11"])))
  (is (= 8 (uika.core/declared-release ["-target" "1.8" "-source" "1.8"])))
  ;; --release wins over -target, position notwithstanding, like the Java side.
  (is (= 17 (uika.core/declared-release ["-target" "11" "--release" "17"])))
  (is (nil? (uika.core/declared-release [])))
  (is (nil? (uika.core/declared-release nil)))
  ;; Below 8 is no declaration at all, so one legacy module cannot drag the minimum
  ;; under the floor and switch the layer off.
  (is (nil? (uika.core/declared-release ["--release" "7"])))
  (is (nil? (uika.core/declared-release ["-Xlint:all"]))))

(deftest jfr-evidence-is-compiled-to-the-jdk17-floor
  ;; Guards build.clj's ["--release" "17"]: without it javac targets whatever JDK runs
  ;; the build and the published class dies with UnsupportedClassVersionError on a 17
  ;; runtime — which jfr-evidence would then MIS-report as "needs a Java 17+ runtime"
  ;; on a JVM that already is one. Mill and sbt carry the same guard.
  (let [resource (io/resource "net/exoego/uika/plugin/core/JfrEvidence.class")]
    (is (some? resource) "run clojure -T:build javac first (make clojure-test does)")
    ;; The class must come from target/core-classes, this build's own javac output,
    ;; not from some jar on the classpath, or the guard validates bytes the current
    ;; build.clj never produced.
    (is (str/includes? (str resource) "core-classes") (str resource))
    (when resource
      (with-open [in (io/input-stream resource)]
        (let [header (.readNBytes in 8)
              major (+ (* 256 (bit-and (aget header 6) 0xff))
                       (bit-and (aget header 7) 0xff))]
          (is (<= major 61) (str "class-file major " major " (61 = JDK 17)")))))))

(deftest ports-stay-in-sync-with-uikacli
  ;; core.clj hand-ports UikaCli's constants because the Java class is never compiled
  ;; for the Clojure frontends. Scraping the Java source pins the ports, so a change on
  ;; the Java side fails here instead of stranding lein and the -T tool on stale values.
  ;; Scrapes are anchored to the declarations and nil-guarded: a Java reformat fails as
  ;; a clean comparison, and a lookalike constant elsewhere cannot win the match.
  (let [java-src (slurp (io/file ".." "jvm-plugin-core" "src" "main" "java"
                                 "net" "exoego" "uika" "plugin" "core" "UikaCli.java"))
        scrape (fn [re] (second (re-find re java-src)))]
    (is (= @#'uika.core/min-release
           (some-> (scrape #"int MIN_RELEASE = (\d+);") parse-long)))
    (is (= @#'uika.core/cli-group (scrape #"String GROUP = \"([^\"]+)\";")))
    (is (= @#'uika.core/cli-artifact (scrape #"String ARTIFACT = \"([^\"]+)\";")))
    ;; The published-classifier list is compared WHOLE, scraped from the one message the
    ;; Java side maintains next to its dispatch. A fixed vocabulary would let a new
    ;; platform slip past, and a whole-file classifier scrape would count javadoc text.
    (let [java-list (scrape #"\(available: ([^)]+)\)")
          fake (fn [os arch]
                 (let [orig-os (System/getProperty "os.name")
                       orig-arch (System/getProperty "os.arch")]
                   (try
                     (System/setProperty "os.name" os)
                     (System/setProperty "os.arch" arch)
                     (try (#'uika.core/platform-classifier)
                          (catch clojure.lang.ExceptionInfo e (ex-message e)))
                     (finally
                       (System/setProperty "os.name" orig-os)
                       (System/setProperty "os.arch" orig-arch)))))]
      (is (some? java-list) "could not scrape the (available: ...) list from UikaCli.java")
      (is (str/includes? (str (fake "solaris" "sparc"))
                         (str "(available: " java-list ")"))
          "the ported error message must carry the Java side's list verbatim")
      ;; The DISPATCH is pinned too, driven by the scraped list rather than a hardcoded
      ;; table: every published classifier must come back from the cond for a matching
      ;; os/arch, so a misspelled token cannot hide behind an accurate error string.
      (doseq [classifier (map str/trim (str/split (str java-list) #","))]
        (let [[os-token arch] (str/split classifier #"-" 2)
              os ({"linux" "Linux" "macos" "Mac OS X" "windows" "Windows 11"} os-token)]
          (is (some? os)
              (str "unknown OS token in " classifier "; extend the sync test's os map"))
          (when os
            (is (= classifier (fake os arch))
                (str classifier " did not dispatch from " os "/" arch))))))))

(deftest the-command-port-carries-every-uikacli-flag
  ;; core.clj hand-ports UikaCli.runUpgradeCheck's command building, and its docstring
  ;; already says that a flag added there needs the key here and, for Leiningen, in
  ;; option-keys. Nothing enforced it. Five integrations share the Java builder and pick a
  ;; new flag up for free, so the two Clojure front ends are the only ones that can fall
  ;; behind -- silently, with every suite still green. Scraping both sources is what turns
  ;; that into a failure.
  ;;
  ;; Only QUOTED occurrences count, on both sides. Each file mentions flags in prose too
  ;; (the CLI's error names --class-load-log; a blank :fail-on must not become
  ;; `--fail-on ""`), and counting those would let a flag pass on a comment alone.
  (let [flags-in (fn [text]
                   (->> (re-seq #"\"(--[a-z-]+)\"" text) (map second) distinct vec))
        from (fn [text marker]
               (subs text (or (str/index-of text marker)
                              (throw (ex-info (str "not found: " marker) {})))))
        java-src (slurp (io/file ".." "jvm-plugin-core" "src" "main" "java"
                                 "net" "exoego" "uika" "plugin" "core" "UikaCli.java"))
        ;; From the signature, so the javadoc above it (which names most of the flags)
        ;; cannot stand in for the builder that actually emits them.
        java-flags (flags-in (from java-src "public static int runUpgradeCheck("))
        core-src (slurp (io/file "src-core" "exoego" "uika" "core.clj"))
        core-body (from core-src "(defn run-upgrade-check")
        lein-src (slurp (io/file ".." "lein-plugin" "src" "leiningen" "uika.clj"))]
    (is (seq java-flags) "could not scrape any flag from UikaCli.runUpgradeCheck")
    ;; Sequences, not sets: the two builders emit the flags in the same order, which is
    ;; what lets a recorded argv from one front end be read like any other's.
    (is (= java-flags (flags-in core-body))
        "UikaCli.runUpgradeCheck and core.clj's port disagree about the command line")

    ;; The -T tool needs no translation table: every flag's key is the flag without its
    ;; dashes, and they arrive through one destructuring form.
    (let [destructured (set (str/split (second (re-find #"\{:keys \[([^\]]+)\]\}" core-body))
                                       #"\s+"))]
      (doseq [flag java-flags]
        (is (contains? destructured (subs flag 2))
            (str flag " has no " (subs flag 2) " key in run-upgrade-check's option map"))))

    ;; Leiningen does need one: three of its keys are plural, and --before/--after are
    ;; positional arguments of the subtask rather than :uika map keys. A flag added to
    ;; UikaCli therefore has to gain an entry HERE too, which is the forcing function --
    ;; option-keys REJECTS an unknown key, so a missing one is a hard error for users.
    (let [positional #{"--before" "--after"}
          lein-keys {"--fail-on" "fail-on"
                     "--exclude-file" "exclude-files"
                     "--jdk-release" "jdk-release"
                     "--class-load-log" "class-load-logs"
                     "--draft-exclude-file" "draft-exclude-file"}
          option-keys (set (str/split
                            (second (re-find #"(?s)option-keys.*?#\{([^}]+)\}" lein-src))
                            #"\s+"))
          lein-body (from lein-src "(defn- upgrade-check")
          lein-destructured (set (str/split
                                  (second (re-find #"\{:keys \[([^\]]+)\]" lein-body))
                                  #"\s+"))]
      (is (= (set (remove positional java-flags)) (set (keys lein-keys)))
          "a flag moved in UikaCli; name its Leiningen key in this table")
      (doseq [[flag key-name] lein-keys]
        (is (contains? option-keys (str ":" key-name))
            (str flag " is not reachable from Leiningen: :" key-name
                 " is missing from option-keys, which rejects unknown keys"))
        (is (contains? lein-destructured key-name)
            (str ":" key-name " is accepted but never read into run-upgrade-check"))))))

(deftest dump-json-omits-an-unservable-release
  ;; The lein probe arm can face a below-floor project JVM (a JDK 7 :java-cmd), and a
  ;; dump naming a release ct.sym never carried hard-fails the CLI's JDK-pair run.
  ;; Nothing servable means no field at all, the shape DumpFormat's writer emits.
  (let [dump (json/read-str (uika.core/dump-json ":x" [] [] nil))]
    (is (not (contains? dump "jdkRelease")))
    (is (not (contains? (first (get dump "modules")) "jdkRelease")))))

(deftest upgrade-check-forwards-flags-and-fails-on-violations
  (let [dir (temp-dir)
        stub (io/file dir "uika")
        before (io/file dir "before.json")
        after (io/file dir "after.json")
        exclude (io/file dir "exclude.toml")]
    ;; The stub records its argument list next to --before, exit code via UIKA_STUB_EXIT.
    (spit stub "#!/bin/sh\necho \"$@\" > \"$3.args\"\nexit ${UIKA_STUB_EXIT:-0}\n")
    (.setExecutable stub true false)
    (spit before "{}")
    (spit after "{}")
    (spit exclude "")
    (uika/upgrade-check {:before (str before) :after (str after)
                         :fail-on "reachable"
                         :exclude-file [(str exclude)]
                         :jdk-release 11
                         :class-load-log [(str dir "/loads.log")]
                         :draft-exclude-file (str dir "/draft.toml")
                         :cli-path (str stub)})
    (let [args (slurp (str before ".args"))]
      (is (str/includes? args "--fail-on reachable"))
      (is (str/includes? args (str "--exclude-file " exclude)))
      (is (str/includes? args "--jdk-release 11"))
      (is (str/includes? args (str "--class-load-log " dir "/loads.log")))
      (is (str/includes? args (str "--draft-exclude-file " dir "/draft.toml"))))
    (testing "a vector value is unwrapped, and blank is unset"
      ;; (str ["x"]) is a legal filename, so the draft would land in ["x"] with exit 0.
      ;; The lein keys next to this one are vectors, which is how it gets written.
      (uika/upgrade-check {:before (str before) :after (str after)
                           :class-load-log [(str dir "/loads.log")]
                           :draft-exclude-file [(str dir "/draft.toml")]
                           :cli-path (str stub)})
      (is (str/includes? (slurp (str before ".args"))
                         (str "--draft-exclude-file " dir "/draft.toml")))
      (uika/upgrade-check {:before (str before) :after (str after)
                           :fail-on ""
                           :class-load-log ""
                           :draft-exclude-file ""
                           :cli-path (str stub)})
      (let [args (slurp (str before ".args"))]
        (is (not (str/includes? args "--draft-exclude-file")))
        (is (not (str/includes? args "--class-load-log")))
        ;; The Java side has guarded --fail-on with isBlank since it was written. A
        ;; CI-templated "" must run on the CLI default here too, not reach clap as
        ;; --fail-on "" and die with a usage error at exit 2.
        (is (not (str/includes? args "--fail-on")))))
    (testing "a CLI that found violations fails the command"
      (let [failing-stub (io/file dir "uika-fail")]
        (spit failing-stub "#!/bin/sh\nexit 1\n")
        (.setExecutable failing-stub true false)
        (is (thrown-with-msg? Exception #"broken references"
              (uika/upgrade-check {:before (str before) :after (str after)
                                   :cli-path (str failing-stub)})))))))

(deftest usage-hint-names-the-documented-invocation
  ;; -Tuika only resolves for a development `-Ttools install` from a local or git
  ;; coordinate. The documented install is a deps.edn alias carrying :ns-default, which
  ;; needs -T:uika, so the hint pointed users at a form their install does not have.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"clojure -T:uika upgrade-check"
       (uika/upgrade-check {}))))

(deftest version-from-libs-reads-the-tools-own-maven-coordinate
  ;; The deps.edn alias flow: the one :mvn/version names the matching uika-cli
  ;; release. A git or :local/root install has no such coordinate on purpose
  ;; (undocumented install paths fall back to :cli-version / UIKA_CLI_VERSION).
  (is (= "1.2.3" (uika/version-from-libs
                  {'net.exoego.uika/clojure-uika {:mvn/version "1.2.3"}})))
  (is (nil? (uika/version-from-libs
             {'io.github.exoego/uika {:git/tag "v1.2.3" :git/sha "abcdef"}})))
  ;; Another lib's coordinate never masquerades as the tool's own version.
  (is (nil? (uika/version-from-libs
             {'org.clojure/clojure {:mvn/version "1.12.5"}}))))

(deftest blank-cli-knobs-are-treated-as-unset
  ;; The `env` helper blanks empty variables because an empty string is truthy in Clojure.
  ;; A CI-templated :exec-args reaches the CONFIG keys the same way, so they need the same
  ;; guard: :cli-path "" used to exec the empty string, and :cli-version "" used to build a
  ;; Central URL with an empty version segment.
  ;;
  ;; The env reader is stubbed. Without that this test reads the ambient environment: it
  ;; FAILS on a machine with UIKA_CLI_PATH exported (which lein-plugin/it/run.sh and the
  ;; docs both use) and DOWNLOADS from Maven Central with UIKA_CLI_VERSION set.
  (let [no-env (constantly nil)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"usage hint"
         (uika.core/resolve-binary {:cli-path ""} (constantly nil) "usage hint" no-env)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"usage hint"
         (uika.core/resolve-binary {:cli-version ""} (constantly nil) "usage hint" no-env)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"usage hint"
         (uika.core/resolve-binary {:cli-path "   "} (constantly nil) "usage hint" no-env)))
    ;; A real value still wins, so the guard cannot have swallowed the knob itself.
    (let [binary (executable-stub (temp-dir) "uika")]
      (is (= (.getPath binary)
             (.getPath (uika.core/resolve-binary
                        {:cli-path (.getPath binary)} (constantly nil) "usage hint" no-env))))
      ;; And the environment fallback the guard argues from still works.
      (is (= (.getPath binary)
             (.getPath (uika.core/resolve-binary
                        {} (constantly nil) "usage hint"
                        {"UIKA_CLI_PATH" (.getPath binary)})))))))

(deftest an-explicit-binary-is-checked-before-processbuilder-sees-it
  ;; Port of UikaCli.overrideFrom, which the four JVM plugins already run: a path that is
  ;; not a file, or a file that lost its executable bit, must fail HERE. Handed on, it dies
  ;; inside ProcessBuilder with a message naming neither uika nor the knob. Losing the bit
  ;; is the everyday case: actions/upload-artifact does not preserve it, and shipping the
  ;; binary as a CI artifact is what the docs recommend.
  (let [dir (temp-dir)
        no-env (constantly nil)
        missing (io/file dir "nowhere" "uika")
        plain (io/file dir "not-executable")]
    (spit plain "#!/bin/sh\nexit 0\n")
    (.setExecutable plain false false)
    ;; The message names the SOURCE the value came from. :cli-path and UIKA_CLI_PATH are
    ;; two spellings of one knob, and blaming the variable for a project.clj value sends
    ;; the reader to inspect an environment that was never consulted.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":cli-path does not name a file"
         (uika.core/resolve-binary {:cli-path (.getPath missing)}
                                   (constantly nil) "usage hint" no-env)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"UIKA_CLI_PATH does not name a file"
         (uika.core/resolve-binary {} (constantly nil) "usage hint"
                                   {"UIKA_CLI_PATH" (.getPath missing)})))
    ;; A directory exists and still cannot be run; pointing the knob at the install
    ;; directory instead of the binary inside it is the likely slip.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":cli-path does not name a file"
         (uika.core/resolve-binary {:cli-path dir}
                                   (constantly nil) "usage hint" no-env)))
    (when-not (.canExecute plain)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":cli-path is not executable"
           (uika.core/resolve-binary {:cli-path (.getPath plain)}
                                     (constantly nil) "usage hint" no-env))))))

(deftest a-misspelled-option-is-an-error-not-a-silent-no-op
  ;; Destructuring drops what it does not name, so a typo used to disable the flag it was
  ;; meant to set and let the run continue on CLI defaults with nothing said. The
  ;; Leiningen plugin has refused unknown keys all along; this front end did not, which
  ;; made the two halves of one core disagree about the same mistake.
  ;;
  ;; The likeliest typo is the SIBLING's spelling. They differ on
  ;; :exclude-file/:exclude-files and :class-load-log/:class-load-logs, so the two cases
  ;; below are the ones a reader moving between the tools actually writes.
  (let [dir (temp-dir)
        stub (io/file dir "uika")
        before (io/file dir "before.json")
        after (io/file dir "after.json")]
    (spit stub "#!/bin/sh\nexit 0\n")
    (.setExecutable stub true false)
    (spit before "{}")
    (spit after "{}")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":exclude-files.*known.*:exclude-file"
         (uika/upgrade-check {:before (str before) :after (str after)
                              :cli-path (str stub)
                              :exclude-files ["a.toml"]})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":class-load-logs"
         (uika/upgrade-check {:before (str before) :after (str after)
                              :cli-path (str stub)
                              :class-load-logs ["a.log"]})))
    ;; dump-classpath takes a different set and checks it too.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":outputt"
         (uika/dump-classpath {:dir fixture :outputt "x.json"})))
    ;; And every documented key still passes, so the guard cannot have swallowed one.
    (is (nil? (#'uika/check-options
               {:before "b" :after "a" :fail-on "any" :exclude-file [] :jdk-release 11
                :jfr "d" :class-load-log [] :draft-exclude-file "d.toml"
                :evidence-work-dir "w" :cli-version "1" :cli-path "p"}
               @#'uika/check-option-keys)))
    (is (nil? (#'uika/check-options
               {:dir "." :output "o" :class-dir "c" :jdk-release 11 :aliases [:prod]}
               @#'uika/dump-option-keys)))))

(deftest jfr-recordings-are-converted-for-the-cli
  ;; A REAL recording, not a synthetic file: only the JDK's own writer produces the
  ;; chunk format the converter reads. `java -version` under StartFlightRecording
  ;; loads hundreds of JDK classes, which is plenty of jdk.ClassLoad events.
  (let [dir (temp-dir)
        stub (io/file dir "uika")
        before (io/file dir "before.json")
        after (io/file dir "after.json")
        evidence-dir (io/file dir "evidence")
        recording (io/file evidence-dir "probe.jfr")
        java-bin (str (io/file (System/getProperty "java.home") "bin" "java"))]
    (.mkdirs evidence-dir)
    (spit stub "#!/bin/sh\necho \"$@\" > \"$3.args\"\nexit 0\n")
    (.setExecutable stub true false)
    (spit before "{}")
    (spit after "{}")
    (spit (io/file evidence-dir "loads.log") "[class,load] com.example.FromText\n")
    (let [{:keys [exit]} (shell/sh java-bin
                                   (str "-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,"
                                        "jdk.ClassLoad#stackTrace=true,filename=" recording)
                                   "-version")]
      (is (zero? (long exit))))
    (uika/upgrade-check {:before (str before) :after (str after)
                         :jfr (str evidence-dir)
                         :evidence-work-dir (str (io/file dir "work"))
                         :cli-path (str stub)})
    (let [args (slurp (str before ".args"))
          converted (re-find #"\S*jfr-1-probe\.log" args)]
      ;; The directory entry stays on the command (its text log still matters) and
      ;; the conversion is appended next to it.
      (is (str/includes? args (str "--class-load-log " evidence-dir)))
      (is converted)
      ;; Which shape depends on the JDK: an event whose stack survived becomes a
      ;; framed block, a stackless one a bare tagged line. Either proves the
      ;; converter wrote the CLI's text format.
      (let [text (slurp converted)]
        (is (or (str/includes? text "Java stack when loading ")
                (str/includes? text "[class,load] ")))))))
