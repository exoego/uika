(ns leiningen.uika
  "lein uika dump-classpath [output] / lein uika upgrade-check <before> <after>.

  Options come from a {:uika {...}} map in project.clj: :fail-on, :exclude-files,
  :jdk-release (0 disables the API layer; a positive value also records the project as
  running on that release rather than on the probed :java-cmd JVM), :class-load-logs
  (text format), :jfr (a recording or a directory of recordings mixed with text logs,
  converted with the JDK's own JFR reader, which needs lein itself on Java 17+),
  :draft-exclude-file
  (needs :class-load-logs or :jfr, and the CLI's own error names the singular CLI flag
  this map rejects), :cli-version, :cli-path. The CLI version defaults to this plugin's
  own, read from the jar's pom.properties, so one version bump updates both."
  (:require [clojure.java.io :as io]
            [exoego.uika.core :as core]
            [leiningen.core.classpath :as lein-cp]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as main]
            [leiningen.core.project :as project]
            [clojure.java.shell :as shell])
  (:import (java.io File)
           (java.util Properties)))

(defn- runtime-project
  "The project as it ships: without the :default profiles, which are exactly the
  development-only ones (:base injects nrepl into every dev task, :system and :user
  add machine- and developer-wide deps, :provided is what an uberjar leaves out).
  This is the lein spelling of dumping runtimeClasspath, not compileClasspath.
  Unmerging the composite rather than a hand-written list also keeps a profile the
  caller asked for explicitly, as in `lein with-profile +prod uika dump-classpath`.

  Repository config is merged back on top. :repositories, :mirrors and :local-repo
  conventionally live in ~/.lein/profiles.clj, so they arrive through :user, and
  get-dependencies reads them straight off the project map -- without this, the dump
  would be the one lein task that bypasses a corporate mirror or a relocated local
  repo. leiningen.uberjar does the same dance with project/whitelist-keys; the list
  below is get-dependencies' own select-keys, which whitelist-keys does not cover."
  [project]
  (merge (project/unmerge-profiles project [:default])
         (select-keys project [:repositories :mirrors :local-repo
                               :offline? :checksum :update])))

(defn- project-jvm
  "The JVM the project's own code runs on, probed for the two things uika needs of
  it: its java.home (whose ct.sym the CLI reads through UIKA_JDK) and its feature
  release. The command is the one leiningen.core.eval picks at eval.clj:254,
  `(or (:java-cmd project) JAVA_CMD \"java\")`, which is NOT the JVM this plugin
  runs on -- :eval-in-leiningen pins that to lein's own launcher. Measured on a
  project with :java-cmd on a 25 while lein ran on a 21: the dump claimed 21 and
  --jdk-release came out 20 instead of 24, resolving escapes against a JDK four
  releases off.

  -XshowSettings:properties writes both values to stderr in one cheap start-up. A
  JVM that cannot be probed (not on PATH, or not a HotSpot-family launcher) falls
  back to this JVM with a warning rather than failing the task."
  [project]
  (let [cmd (or (:java-cmd project) (System/getenv "JAVA_CMD") "java")
        fallback (core/this-jvm)]
    (if (= (str cmd) (str (io/file (:home fallback) "bin" "java")))
      fallback
      (try
        (let [{:keys [exit err]} (shell/sh (str cmd) "-XshowSettings:properties" "-version")]
          (or (when (zero? (long exit)) (core/parse-jvm-properties err))
              (do (main/warn (str "uika: could not read the version of " cmd
                                  "; falling back to lein's own JVM"))
                  fallback)))
        (catch Exception e
          (main/warn (str "uika: could not run " cmd " (" (.getMessage e)
                          "); falling back to lein's own JVM"))
          fallback)))))

(defn- jar-or-zip?
  "The filter leiningen.core.classpath/resolve-managed-dependencies applies one layer
  above get-dependencies. A `:extension \"pom\"` BOM resolves to a .pom that lein
  never puts on the classpath and that the CLI cannot open (`not a zip/jar`, exit 2)."
  [^File file]
  (boolean (and file (re-find #"\.(jar|zip)$" (.getName file)))))

(def ^:private option-keys
  "Every key the :uika map accepts. Destructuring drops what it does not name, so
  without an explicit check a misspelling -- :class-load-log, the Clojure tool's
  singular spelling, or :exclude-file -- would silently disable the flag instead of
  failing, and the check would run on CLI defaults with nothing said."
  #{:fail-on :exclude-files :jdk-release :class-load-logs :jfr :draft-exclude-file
    :cli-version :cli-path})

(defn- check-options
  "Rejects a misspelled :uika key. Both subtasks run it, since both read the map and a
  key neither recognises would otherwise silently disable the flag it was meant to set."
  [opts]
  (when-let [unknown (seq (sort (remove option-keys (keys opts))))]
    (main/abort (str "uika: unknown :uika option(s) " (pr-str (vec unknown))
                     "; known: " (pr-str (vec (sort option-keys)))))))

(defn- dump-classpath [project args]
  (check-options (:uika project))
  ;; eval/prep, not `lein compile`: compile short-circuits when :aot yields no stale
  ;; namespace, so it never reaches prep -- the only thing that runs :prep-tasks. A
  ;; :java-source-paths project with no :aot would otherwise never run javac and would
  ;; dump none of its own classes. Like the sbt plugin compiling as a side effect.
  (eval/prep project)
  (let [runtime (runtime-project project)
        graph (lein-cp/get-dependencies :dependencies :managed-dependencies runtime)
        artifacts (vec (for [coordinate (keys graph)
                             :let [[lib version] coordinate
                                   file (:file (meta coordinate))]
                             :when (jar-or-zip? file)]
                         (core/lib->artifact lib version file)))
        ;; :compile-path and :target-path come from the ORIGINAL project, the one
        ;; eval/prep just built into. Unmerging re-runs profile-scope-target-path and
        ;; re-derives :compile-path from :target-path, so a profile that sets either
        ;; key (or a "%s" in :target-path, if the unmerged set is ever narrowed back
        ;; to a hand-written list) makes the runtime project name a directory nothing
        ;; compiled into -- which .isDirectory would then drop without a word.
        class-dirs (into [] (comp (distinct)
                                  (filter #(.isDirectory (io/file ^String %))))
                         (concat (:source-paths runtime)
                                 (:resource-paths runtime)
                                 [(:compile-path project)]))
        out (io/file (or (first args)
                         (io/file (:target-path project) "uika" "classpath.json")))]
    (io/make-parents out)
    ;; :jdk-release first, then :javac-options, then the probe. :javac-options is the
    ;; spelling that pins the API, which every other tool reads before any JVM evidence:
    ;; a project compiling --release 8 on a 21 JVM would otherwise be over-claimed, the
    ;; direction that loses findings with nothing to show. The probe answers "which JVM
    ;; does lein start for this project", the only evidence left when nothing declares.
    ;; The probe arm is floored: it is the one writer that can see a below-floor JVM
    ;; (a JDK 7 :java-cmd), and a dump naming a release ct.sym never carried hard-fails
    ;; the CLI's JDK-pair run later. dump-json omits the field when nothing is servable.
    (spit out (core/dump-json (str ":" (:name project)) artifacts class-dirs
                              (or (core/override-release (:jdk-release (:uika project)) main/info)
                                  (core/declared-release (:javac-options project))
                                  (let [feature (:feature (project-jvm project))]
                                    (when (>= feature core/min-release) feature)))))
    (main/info "uika classpath dump:" (str out))))

(defn- own-version
  "This plugin's version from its jar's pom.properties; nil in a source checkout."
  []
  (when-let [resource (io/resource (str "META-INF/maven/" core/cli-group
                                        "/lein-uika/pom.properties"))]
    (with-open [in (io/input-stream resource)]
      (let [props (doto (Properties.) (.load in))]
        (.getProperty props "version")))))

(defn- upgrade-check [project [before after]]
  (when-not (and before after)
    (main/abort "usage: lein uika upgrade-check <before.json> <after.json>"))
  (let [{:keys [fail-on exclude-files jdk-release class-load-logs jfr
                draft-exclude-file]
         :as opts} (:uika project)]
    (check-options opts)
    ;; Binary resolution sits INSIDE the try: an unsupported platform, a zip missing
    ;; the binary and a failed download all throw from here, and lein answers any
    ;; exception without :exit-code with a full cause trace. IOException is caught
    ;; alongside ex-info because a download failure is not an ex-info at all.
    (try
      (core/run-upgrade-check
       (core/resolve-binary opts own-version
                            "uika-cli version is unknown; set :uika {:cli-version \"...\"}")
       {:before before :after after
        :fail-on fail-on
        :exclude-file exclude-files
        ;; The declared :javac-options release backs the flag too, like the dump above.
        ;; `or`, so an explicit 0 keeps its off-switch meaning (0 is truthy in Clojure).
        :jdk-release (or jdk-release (core/declared-release (:javac-options project)))
        :class-load-log class-load-logs
        :jfr jfr
        ;; Conversions land under the project's own target space, like the JVM
        ;; plugins' build/target workdirs.
        :evidence-work-dir (io/file (:target-path project) "uika")
        :draft-exclude-file draft-exclude-file
        :jvm (project-jvm project)})
      (catch clojure.lang.ExceptionInfo e
        (main/abort (ex-message e)))
      (catch java.io.IOException e
        ;; With the class name: FileNotFoundException from a URL carries the URL as
        ;; its entire message, which alone does not separate a 404 from a proxy
        ;; rejection, a DNS failure or a full disk.
        (main/abort (str "uika: " (.getSimpleName (class e)) ": " (.getMessage e)))))))

(defn uika
  "Write uika classpath dumps and run upgrade checks.

  lein uika dump-classpath [output]     resolved runtime classpath as v2 JSON
  lein uika upgrade-check <a> <b>       compare two dumps with the uika CLI"
  [project & [subtask & args]]
  (case subtask
    "dump-classpath" (dump-classpath project args)
    "upgrade-check" (upgrade-check project args)
    (main/abort "usage: lein uika (dump-classpath [output] | upgrade-check <before> <after>)")))
