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
                         :cli-path (str stub)})
    (let [args (slurp (str before ".args"))]
      (is (str/includes? args "--fail-on reachable"))
      (is (str/includes? args (str "--exclude-file " exclude)))
      (is (str/includes? args "--jdk-release 11")))
    (testing "a CLI that found violations fails the command"
      (let [failing-stub (io/file dir "uika-fail")]
        (spit failing-stub "#!/bin/sh\nexit 1\n")
        (.setExecutable failing-stub true false)
        (is (thrown-with-msg? Exception #"broken references"
              (uika/upgrade-check {:before (str before) :after (str after)
                                   :cli-path (str failing-stub)})))))))
