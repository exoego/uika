(ns exoego.uika
  "uika as a Clojure CLI tool: `clojure -T:uika dump-classpath` / `clojure -T:uika upgrade-check`.

  Published to Maven Central as net.exoego.uika/clojure-uika and consumed through a
  deps.edn alias that carries :ns-default itself, because tools.deps resolves no
  usage data for :mvn coordinates (coord-usage :mvn is TBD upstream), so a
  `-Ttools`-installed Maven tool would need every function call qualified. The
  tool's own :mvn/version in the runtime basis names the matching uika-cli
  release, so the alias pins both. Everything not specific to tools.deps lives
  in exoego.uika.core, shared with the Leiningen plugin."
  (:require [clojure.java.basis :as basis]
            [clojure.java.io :as io]
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
             classes compiled there are scanned too.
  :jdk-release the release to record the application as running on, instead of this
             JVM's. The same knob upgrade-check takes, for a project whose runtime is
             not the JVM that writes the dump. 0 leaves the recorded release derived,
             since it only switches the API layer off."
  [{:keys [dir output class-dir jdk-release] :as args}]
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
    (spit out (core/dump-json (str ":" (.getName (io/file dir))) artifacts class-dirs
                              (or (core/override-release jdk-release)
                                  (.feature (Runtime/version)))))
    (println "uika classpath dump:" (str out))
    (str out)))

(defn version-from-libs
  "The version this tool was resolved with, out of a basis :libs map: its own
  Maven coordinate, the deps.edn alias flow the docs describe. A git or
  :local/root install carries no such coordinate and falls back to
  :cli-version / UIKA_CLI_VERSION, with the resolve-binary usage hint naming
  the former."
  [libs]
  (some (fn [[lib {:mvn/keys [version]}]]
          (when (= lib 'net.exoego.uika/clojure-uika) version))
        libs))

(defn- own-version
  []
  (version-from-libs (:libs (basis/current-basis))))

(defn upgrade-check
  "Runs uika upgrade-check over a before/after pair of dumps.

  :before / :after   dump files (required)
  :fail-on           never | reachable | any (CLI default when omitted)
  :exclude-file      one path or a vector of paths
  :jdk-release       JDK API release; 0 disables, default is the running JVM's
  :jfr               runtime class-load evidence as JFR: a recording, or a directory
                     holding recordings and text logs mixed. Recordings are converted
                     with the JDK's own JFR reader before the CLI runs, which needs
                     this tool on Java 17+
  :class-load-log    one path or a vector: runtime class-load evidence in the CLI's
                     text format (a recording given here is converted too)
  :draft-exclude-file
                     where the CLI writes draft exclude rules for symbols never
                     observed loading, which needs :class-load-log too
  :cli-version       uika-cli version; defaults to this tool's own version, read
                     from its Maven coordinate in the runtime basis
                     (UIKA_CLI_VERSION is consulted in between, and a source
                     install has no coordinate, so it needs one of the two)
  :cli-path          existing uika binary, skipping the download entirely
                     (UIKA_CLI_PATH does the same from the environment)"
  [{:keys [before after] :as args}]
  (when-not (and before after)
    (throw (ex-info "usage: clojure -Tuika upgrade-check :before <a.json> :after <b.json>" {})))
  (core/run-upgrade-check
   (core/resolve-binary args own-version "uika-cli version is unknown; pass :cli-version")
   args))
