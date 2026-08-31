(ns leiningen.uika-test
  "In-process tests for the plugin's own namespace.

  it/run.sh drives a REAL `lein uika` and stays the only place the installed-plugin
  path runs. It is also why it can measure nothing: cloverage instruments namespaces
  in the JVM it reports from, and there that JVM is a child process. These tests call
  the same entry points in this JVM, which is what gives `make lein-coverage` a
  namespace to instrument. They go through the public `uika` task rather than the
  private helpers wherever the task reaches them, so the subtask dispatch is covered
  by the same call, and they do not restate what run.sh already asserts."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [exoego.uika.core :as core]
            [leiningen.core.main :as main]
            [leiningen.core.project :as project]
            [leiningen.uika :as uika])
  (:import (java.io File StringWriter)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "uika-lein-test" (make-array FileAttribute 0))))

(defn- abort-message
  "The message `f` aborted with, or nil when it returned. leiningen.core.main/abort
  only throws under a false *exit-process?*; without the binding it calls System/exit
  and takes the test JVM with it. It also prints the message, and `abort` binds *out*
  to whatever *err* is at that moment, so the capture has to sit on *err*."
  [f]
  (binding [main/*exit-process?* false
            *err* (StringWriter.)]
    (try
      (f)
      nil
      (catch clojure.lang.ExceptionInfo e (ex-message e)))))

(defn- write-project
  "A minimal consumer project on disk, read back through leiningen.core.project/read
  so the map carries the full profile stack a real `lein uika` sees. runtime-project
  is written against that stack, so a hand-built map would not exercise it."
  [^File dir & {:keys [javac-options uika]}]
  (.mkdirs (io/file dir "src"))
  (spit (io/file dir "project.clj")
        (pr-str (concat (list 'defproject 'example/consumer "0.1.0"
                              :dependencies [['org.apache.commons/commons-lang3 "3.20.0"]]
                              ;; A dev-only dep the dump must not carry, the same shape
                              ;; it/test-project uses.
                              :profiles {:dev {:dependencies [['commons-io/commons-io "2.20.0"]]}})
                        (when javac-options [:javac-options javac-options])
                        (when uika [:uika uika]))))
  (project/read (str (io/file dir "project.clj"))))

(defn- stub-cli
  "A uika-cli stand-in recording its argv and UIKA_JDK, then exiting `exit`. The JVM
  plugins' tests stub the CLI the same way: the real binary cannot report which flags
  it was handed, and the flag composition is the part this plugin owns."
  [^File dir exit]
  (let [script (io/file dir "uika-stub")
        record (io/file dir "argv.txt")]
    (spit script (str "#!/bin/sh\n"
                      "{ printf '%s\\n' \"$@\"; echo \"UIKA_JDK=${UIKA_JDK:-}\"; } > '"
                      record "'\n"
                      "exit " exit "\n"))
    (.setExecutable script true)
    [script record]))

(deftest classpath-filter-keeps-archives-only
  ;; resolve-managed-dependencies applies this one layer above get-dependencies, which
  ;; the plugin calls directly. A `:extension "pom"` BOM resolves to a .pom lein never
  ;; puts on a classpath and the CLI cannot open (`not a zip/jar`, exit 2).
  (let [jar-or-zip? #'uika/jar-or-zip?]
    (is (jar-or-zip? (io/file "/r/commons-lang3-3.20.0.jar")))
    (is (jar-or-zip? (io/file "/r/uika-cli-0.8.0-x86_64-unknown-linux-gnu.zip")))
    (is (not (jar-or-zip? (io/file "/r/spring-boot-dependencies-3.2.0.pom"))))
    (is (not (jar-or-zip? nil)))))

(deftest unknown-uika-option-aborts-naming-the-known-keys
  ;; Destructuring drops what it does not name, so a misspelling would otherwise
  ;; disable the flag it was meant to set and let the check run on CLI defaults with
  ;; nothing said. :class-load-log is the Clojure tool's singular spelling, the
  ;; likeliest way to write this one wrong.
  (let [msg (abort-message
             #(uika/uika {:uika {:class-load-log ["loads.log"]}} "dump-classpath"))]
    (is (some? msg))
    (is (str/includes? msg ":class-load-log"))
    (is (str/includes? msg ":class-load-logs")))
  (testing "both subtasks check, since both read the map"
    (is (str/includes? (abort-message
                        #(uika/uika {:uika {:exclude-file "x.toml"}}
                                    "upgrade-check" "a.json" "b.json"))
                       ":exclude-file"))))

(deftest subtask-dispatch-aborts-with-usage
  (is (str/includes? (abort-message #(uika/uika {} "dump")) "dump-classpath"))
  (is (str/includes? (abort-message #(uika/uika {})) "upgrade-check"))
  (testing "upgrade-check needs both dumps before anything is resolved"
    (is (str/includes? (abort-message #(uika/uika {} "upgrade-check" "before.json"))
                       "usage: lein uika upgrade-check"))))

(deftest runtime-project-drops-dev-deps-and-keeps-repository-config
  (let [project (write-project (temp-dir))
        runtime (#'uika/runtime-project project)
        names (set (map first (:dependencies runtime)))]
    (is (contains? names 'org.apache.commons/commons-lang3))
    (is (not (contains? names 'commons-io/commons-io)))
    ;; :repositories, :mirrors and :local-repo conventionally arrive through the :user
    ;; profile, which the unmerge removes. get-dependencies reads them straight off the
    ;; map, so without the merge-back this would be the one lein task that bypasses a
    ;; corporate mirror or a relocated local repo.
    (is (= (:repositories project) (:repositories runtime)))
    (is (= (:local-repo project) (:local-repo runtime)))))

(deftest project-jvm-probes-the-projects-java-cmd
  (let [this (core/this-jvm)]
    (testing "lein's own launcher is answered without starting a process"
      (is (= this (#'uika/project-jvm
                   {:java-cmd (str (io/file (:home this) "bin" "java"))}))))
    (testing "any other spelling goes through the probe and agrees with it"
      ;; The same JVM under a path the equality shortcut cannot match, so the
      ;; -XshowSettings:properties parse is what produces the answer.
      (is (= this (#'uika/project-jvm
                   {:java-cmd (str (io/file (:home this) "bin" "." "java"))}))))
    (testing "a JVM that cannot be run falls back with a warning, never fails the task"
      (let [warned (StringWriter.)
            jvm (binding [*err* warned]
                  (#'uika/project-jvm {:java-cmd "/nonexistent/bin/java"}))]
        (is (= this jvm))
        (is (str/includes? (str warned) "falling back to lein's own JVM"))))))

(deftest dump-records-runtime-deps-and-the-declared-release
  (let [dir (temp-dir)
        project (write-project dir :javac-options ["--release" "11"])
        out (io/file dir "dump.json")]
    (binding [main/*info* false]
      (uika/uika project "dump-classpath" (str out)))
    (let [dump (json/read-str (slurp out))
          artifacts (get dump "artifacts")
          module (first (get dump "modules"))]
      (is (= 2 (get dump "version")))
      (is (= ":consumer" (get module "module")))
      (is (some (fn [a] (and (= "org.apache.commons" (get a "group"))
                             (= "commons-lang3" (get a "name"))
                             (= "3.20.0" (get a "version"))
                             (str/ends-with? (get a "path") "commons-lang3-3.20.0.jar")))
                artifacts))
      (is (not-any? #(= "commons-io" (get % "name")) artifacts))
      ;; :javac-options is the spelling that pins the API, so it beats the probed JVM.
      ;; Reading the JVM first would over-claim a project compiling --release 11 on a
      ;; 21 runtime, the direction that loses findings with nothing to show.
      (is (= 11 (get dump "jdkRelease")))
      (is (= 11 (get module "jdkRelease")))
      (is (some #(str/ends-with? (get % "path") "src") (get module "classesDirs"))))))

(deftest dump-release-knob-beats-the-declared-options
  (let [dir (temp-dir)
        project (write-project dir
                               :javac-options ["--release" "11"]
                               :uika {:jdk-release 17})
        out (io/file dir "dump.json")]
    (binding [main/*info* false]
      (uika/uika project "dump-classpath" (str out)))
    ;; The knob is the only way to name a runtime the derivation cannot see, such as
    ;; compiling --release 11 and shipping on 17.
    (is (= 17 (get (json/read-str (slurp out)) "jdkRelease")))))

(deftest dump-defaults-its-output-under-the-target-path
  (let [dir (temp-dir)
        project (write-project dir)]
    (binding [main/*info* false]
      (uika/uika project "dump-classpath"))
    (is (.isFile (io/file (:target-path project) "uika" "classpath.json")))))

(deftest upgrade-check-composes-the-cli-flags-and-exports-uika-jdk
  (let [dir (temp-dir)
        [stub record] (stub-cli dir 0)
        before (doto (io/file dir "before.json") (spit "{}"))
        after (doto (io/file dir "after.json") (spit "{}"))
        this (core/this-jvm)
        project {:target-path (str dir)
                 :java-cmd (str (io/file (:home this) "bin" "java"))
                 :uika {:cli-path (str stub)
                        :fail-on "reachable"
                        :exclude-files ["uika-exclude.toml"]
                        :jdk-release 11}}]
    (with-out-str (uika/uika project "upgrade-check" (str before) (str after)))
    (let [recorded (str/split-lines (slurp record))]
      (is (= ["upgrade-check" "--before" (str before) "--after" (str after)
              "--fail-on" "reachable"
              "--exclude-file" "uika-exclude.toml"
              "--jdk-release" "11"]
             (vec (butlast recorded))))
      ;; UIKA_JDK is the ct.sym the CLI reads, so it has to name the PROJECT's JVM.
      ;; :eval-in-leiningen pins this plugin to lein's, which is a different JVM
      ;; whenever :java-cmd is set.
      (is (= (str "UIKA_JDK=" (:home this)) (last recorded))))))

(deftest upgrade-check-turns-a-nonzero-cli-exit-into-an-abort
  (let [dir (temp-dir)
        [stub _] (stub-cli dir 1)
        project {:target-path (str dir)
                 :uika {:cli-path (str stub) :jdk-release 0}}
        msg (abort-message
             #(with-out-str (uika/uika project "upgrade-check"
                                       (str (io/file dir "a.json"))
                                       (str (io/file dir "b.json")))))]
    ;; lein answers any exception without :exit-code with a full cause trace, so the
    ;; ex-info has to be caught here and turned into a plain message.
    (is (some? msg))
    (is (str/includes? msg "broken references"))))

(deftest own-version-never-throws-for-the-cli-version-default
  ;; nil in a bare source checkout, the plugin's own version once lein has written
  ;; target/classes/META-INF/maven/.../pom.properties, and both are correct. What must
  ;; not happen is a throw: resolve-binary calls this to default the CLI version, and a
  ;; throw there loses the usage hint that names :cli-version.
  (let [version (#'uika/own-version)]
    (is (or (nil? version) (and (string? version) (not (str/blank? version)))))))
