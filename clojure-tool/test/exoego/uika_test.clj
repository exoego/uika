(ns exoego.uika-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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
                           :class-load-log ""
                           :draft-exclude-file ""
                           :cli-path (str stub)})
      (let [args (slurp (str before ".args"))]
        (is (not (str/includes? args "--draft-exclude-file")))
        (is (not (str/includes? args "--class-load-log")))))
    (testing "a CLI that found violations fails the command"
      (let [failing-stub (io/file dir "uika-fail")]
        (spit failing-stub "#!/bin/sh\nexit 1\n")
        (.setExecutable failing-stub true false)
        (is (thrown-with-msg? Exception #"broken references"
              (uika/upgrade-check {:before (str before) :after (str after)
                                   :cli-path (str failing-stub)})))))))

(deftest version-from-libs-reads-maven-then-git-coordinates
  ;; The Maven coordinate is the deps.edn alias flow; the git tag is a -Ttools
  ;; install from the repo. Both name the matching uika-cli release.
  (is (= "1.2.3" (uika/version-from-libs
                  {'net.exoego.uika/clojure-uika {:mvn/version "1.2.3"}})))
  (is (= "1.2.3" (uika/version-from-libs
                  {'io.github.exoego/uika {:git/tag "v1.2.3" :git/sha "abcdef"}})))
  ;; Another lib's coordinate never masquerades as the tool's own version.
  (is (nil? (uika/version-from-libs
             {'org.clojure/clojure {:mvn/version "1.12.5"}}))))
