(ns exoego.uika
  "uika as a Clojure CLI tool: `clojure -Tuika dump-classpath` / `clojure -Tuika upgrade-check`.

  Installed as a git dependency (`:deps/root \"clojure-tool\"`), so releasing it costs
  nothing against the Maven Central quota. The repo tag doubles as the CLI version:
  when the tool itself is resolved as a git dep, its own `:git/tag` in the runtime
  basis names the matching uika-cli release. Everything not specific to tools.deps
  lives in exoego.uika.core, shared with the Leiningen plugin."
  (:require [clojure.java.basis :as basis]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.util.dir :as deps-dir]
            [exoego.uika.core :as core]))

(set! *warn-on-reflection* true)

(defn- project-basis
  "The PROJECT's basis, not this tool's own. `create-basis` re-resolves exactly the
  way `clojure -M`/`-X` would; the user config is excluded because the application
  ships without it. `:aliases` widens the resolution the same way the user's run
  alias would (e.g. `:aliases [:prod]`)."
  [dir {:keys [aliases]}]
  ;; with-dir rebinds the directory EVERY relative path resolves against -- the
  ;; :project file and the deps.edn's own :local/root entries alike -- so :project
  ;; stays the bare file name. A dir-joined :project would resolve twice.
  (deps-dir/with-dir (.getCanonicalFile (io/file dir))
    (deps/create-basis (cond-> {:user nil :project "deps.edn"}
                         (seq aliases) (assoc :aliases (mapv keyword aliases))))))

(defn dump-classpath
  "Writes the project's resolved classpath as a uika v2 dump.

  :output   file to write (default \"target/uika/classpath.json\")
  :dir      project directory (default: where the tool was invoked)
  :aliases  aliases to include in the resolution, e.g. [:prod]
  :class-dir extra classes directory to record (AOT output from tools.build
             compile-clj); the project :paths are always recorded so hinted-interop
             classes compiled there are scanned too."
  [{:keys [dir output class-dir] :as args}]
  (let [dir (str (or dir (System/getProperty "user.dir")))
        basis (project-basis dir args)
        ;; Source dirs go in as classesDirs: uika only parses .class files, so pure
        ;; .clj trees contribute nothing, but AOT output or java compilation landing
        ;; in :paths is scanned without further configuration.
        class-dirs (cond-> (into [] (comp (map #(str (io/file dir %)))
                                          (filter #(.isDirectory (io/file ^String %))))
                                 (:paths basis))
                     class-dir (conj (str (io/file dir ^String class-dir))))
        ;; One lib can contribute several paths, so this is a nested for, not a map.
        artifacts (vec (for [[lib {:mvn/keys [version] :keys [paths]}] (sort-by key (:libs basis))
                             path paths]
                         (core/lib->artifact lib version path)))
        out (let [f (io/file (or output "target/uika/classpath.json"))]
              (if (.isAbsolute f) f (io/file dir (str f))))]
    (io/make-parents out)
    (spit out (core/dump-json (str ":" (.getName (io/file dir))) artifacts class-dirs))
    (println "uika classpath dump:" (str out))
    (str out)))

(defn- own-git-tag
  "The :git/tag this tool was resolved with, when it came in as a git dep. That tag
  is the repo release tag, so stripping the v prefix yields the uika-cli version."
  []
  (some (fn [[lib {:git/keys [tag]}]]
          (when (and tag (str/starts-with? (str lib) "io.github.exoego/uika"))
            (cond-> tag (str/starts-with? tag "v") (subs 1))))
        (:libs (basis/current-basis))))

(defn upgrade-check
  "Runs uika upgrade-check over a before/after pair of dumps.

  :before / :after   dump files (required)
  :fail-on           never | reachable | any (CLI default when omitted)
  :exclude-file      one path or a vector of paths
  :jdk-release       JDK API release; 0 disables, default is the running JVM's
  :class-load-log    one path or a vector: runtime class-load evidence in the CLI's
                     text format (JFR conversion is not implemented in this tool yet)
  :draft-exclude-file  where the CLI writes draft exclude rules for symbols never
                     observed loading; needs :class-load-log
  :cli-version       uika-cli version; defaults to this tool's own git tag
                     (UIKA_CLI_VERSION is consulted in between)
  :cli-path          existing uika binary, skipping the download entirely
                     (UIKA_CLI_PATH does the same from the environment)"
  [{:keys [before after] :as args}]
  (when-not (and before after)
    (throw (ex-info "usage: clojure -Tuika upgrade-check :before <a.json> :after <b.json>" {})))
  (core/run-upgrade-check
   (core/resolve-binary args own-git-tag "uika-cli version is unknown; pass :cli-version")
   args))
