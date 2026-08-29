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
