(ns exoego.uika-integration-test
  "Feeds dumps produced by this tool to the REAL uika binary. The dump JSON here is
  hand-written rather than shared from DumpFormat, so only this round trip can catch
  the two drifting apart; the stub-CLI test never would."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [exoego.uika :as uika])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "uika-it" (make-array FileAttribute 0))))

(defn- write-project [dir version]
  (.mkdirs (io/file dir "src"))
  (spit (io/file dir "deps.edn")
        (str "{:paths [\"src\"]\n"
             " :deps {org.apache.commons/commons-lang3 {:mvn/version \"" version "\"}}}")))

(deftest real-cli-reads-the-dump-and-reports-the-version-change
  (if-let [bin (System/getenv "UIKA_BIN")]
    (let [dir (temp-dir)
          before (str (io/file dir "before.json"))
          after (str (io/file dir "after.json"))]
      (write-project dir "3.4")
      (uika/dump-classpath {:dir dir :output before})
      (write-project dir "3.20.0")
      (uika/dump-classpath {:dir dir :output after})
      (let [out (with-out-str
                  (uika/upgrade-check {:before before :after after :cli-path bin}))]
        ;; The CLI parsed both hand-written dumps, attributed the coordinates, and
        ;; scanned the classpath. Violation-detection quality is the CLI's own
        ;; golden tests' job, not this round trip's.
        (is (str/includes? out "CHANGED org.apache.commons:commons-lang3 3.4 -> 3.20.0") out)
        (is (str/includes? out "scanned") out)))
    ;; Visible, not silent: a skipped integration leg that looks green is how a
    ;; format drift would sneak through. make clojure-test always sets UIKA_BIN.
    (do (println "SKIP: UIKA_BIN not set; real-CLI round trip not run (use make clojure-test)")
        (is true))))
