(ns leiningen.uika
  "lein uika dump-classpath [output] / lein uika upgrade-check <before> <after>.

  Options come from a {:uika {...}} map in project.clj: :fail-on, :exclude-files,
  :jdk-release (0 disables), :class-load-logs (text format), :cli-version,
  :cli-path. The CLI version defaults to this plugin's own, read from the jar's
  pom.properties, so one version bump updates both."
  (:require [clojure.java.io :as io]
            [exoego.uika.core :as core]
            [leiningen.core.classpath :as lein-cp]
            [leiningen.core.main :as main]
            [leiningen.core.project :as project])
  (:import (java.util Properties)))

(defn- runtime-project
  "The project as it ships: without the :base/:user/:dev additions (lein injects
  nrepl through :base into every dev task) and without :provided, which an uberjar
  excludes. This is the lein spelling of dumping runtimeClasspath, not
  compileClasspath."
  [project]
  (project/unmerge-profiles project [:base :user :dev :provided]))

(defn- dump-classpath [project args]
  ;; Build outputs first, like the sbt plugin compiling as a side effect: an :aot
  ;; project's compiled interop lands in :compile-path and gets scanned.
  (main/apply-task "compile" project [])
  (let [project (runtime-project project)
        graph (lein-cp/get-dependencies :dependencies :managed-dependencies project)
        artifacts (vec (for [coordinate (keys graph)
                             :let [[lib version] coordinate
                                   file (:file (meta coordinate))]
                             :when file]
                         (core/lib->artifact lib version file)))
        class-dirs (into [] (comp (filter some?)
                                  (distinct)
                                  (filter #(.isDirectory (io/file ^String %))))
                         (concat (:source-paths project)
                                 [(:compile-path project)]))
        out (io/file (or (first args)
                         (io/file (:target-path project) "uika" "classpath.json")))]
    (io/make-parents out)
    (spit out (core/dump-json (str ":" (:name project)) artifacts class-dirs))
    (main/info "uika classpath dump:" (str out))))

(defn- own-version
  "This plugin's version from its jar's pom.properties; nil in a source checkout."
  []
  (when-let [resource (io/resource "META-INF/maven/net.exoego.uika/lein-uika/pom.properties")]
    (with-open [in (io/input-stream resource)]
      (let [props (doto (Properties.) (.load in))]
        (.getProperty props "version")))))

(defn- upgrade-check [project [before after]]
  (when-not (and before after)
    (main/abort "usage: lein uika upgrade-check <before.json> <after.json>"))
  (let [{:keys [fail-on exclude-files jdk-release class-load-logs] :as opts} (:uika project)]
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
        :jdk-release jdk-release
        :class-load-log class-load-logs})
      (catch clojure.lang.ExceptionInfo e
        (main/abort (ex-message e)))
      (catch java.io.IOException e
        (main/abort (str "uika: " (.getMessage e)))))))

(defn uika
  "Write uika classpath dumps and run upgrade checks.

  lein uika dump-classpath [output]     resolved runtime classpath as v2 JSON
  lein uika upgrade-check <a> <b>       compare two dumps with the uika CLI"
  [project & [subtask & args]]
  (case subtask
    "dump-classpath" (dump-classpath project args)
    "upgrade-check" (upgrade-check project args)
    (main/abort "usage: lein uika (dump-classpath [output] | upgrade-check <before> <after>)")))
