(ns exoego.uika
  "uika as a Clojure CLI tool: `clojure -Tuika dump-classpath` / `clojure -Tuika upgrade-check`.

  Installed as a git dependency (`:deps/root \"clojure-tool\"`), so releasing it costs
  nothing against the Maven Central quota. The repo tag doubles as the CLI version:
  when the tool itself is resolved as a git dep, its own `:git/tag` in the runtime
  basis names the matching uika-cli release."
  (:require [clojure.data.json :as json]
            [clojure.java.basis :as basis]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.util.dir :as deps-dir])
  (:import (java.nio.file Files Path StandardCopyOption)
           (java.util.zip ZipFile)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; dump

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

(defn- lib->artifacts
  "Dump artifact maps for one resolved lib. Maven libs carry coordinates; git and
  local deps have none that the version diff could compare, so they are emitted
  coordinate-less like the other plugins' project/file dependencies."
  [lib {:mvn/keys [version] :keys [paths]}]
  (let [group (or (namespace lib) (name lib))
        artifact (name lib)]
    (for [path paths]
      (if version
        {"group" group "name" artifact "version" version "root" 0 "path" path}
        {"root" 0 "path" path}))))

(defn- dump-json
  "The v2 dump as a string: one module, one empty root (paths stay absolute).
  jdkRelease is this JVM's feature release, same as DumpFormat.buildJvmRelease."
  [dir basis class-dirs]
  (let [artifacts (vec (mapcat (fn [[lib coord]] (lib->artifacts lib coord))
                               (sort-by key (:libs basis))))
        module {"module" (str ":" (.getName (io/file dir)))
                "classesDirs" (mapv (fn [^String p] {"root" 0 "path" p}) class-dirs)
                "artifactRefs" (vec (range (count artifacts)))}]
    (json/write-str {"version" 2
                     "jdkRelease" (.feature (Runtime/version))
                     "roots" [""]
                     "artifacts" artifacts
                     "modules" [module]})))

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
        out (let [f (io/file (or output "target/uika/classpath.json"))]
              (if (.isAbsolute f) f (io/file dir (str f))))]
    (io/make-parents out)
    (spit out (dump-json dir basis class-dirs))
    (println "uika classpath dump:" (str out))
    (str out)))

;; ---------------------------------------------------------------------------
;; upgrade-check

(defn- platform-classifier
  "Port of UikaCli.platformClassifier; keep the two in sync."
  []
  (let [os (str/lower-case (System/getProperty "os.name" ""))
        arch (str/lower-case (System/getProperty "os.arch" ""))
        x64 (contains? #{"amd64" "x86_64"} arch)
        arm64 (contains? #{"aarch64" "arm64"} arch)]
    (cond
      (and (str/includes? os "linux") x64) "linux-x86_64"
      (and (str/includes? os "mac") arm64) "macos-aarch64"
      (and (str/includes? os "mac") x64) "macos-x86_64"
      (and (str/includes? os "windows") x64) "windows-x86_64"
      :else (throw (ex-info (str "no uika-cli binary is published for " os "/" arch)
                            {:os os :arch arch})))))

(defn- own-git-tag
  "The :git/tag this tool was resolved with, when it came in as a git dep. That tag
  is the repo release tag, so stripping the v prefix yields the uika-cli version."
  []
  (some (fn [[lib {:git/keys [tag]}]]
          (when (and tag (str/starts-with? (str lib) "io.github.exoego/uika"))
            (cond-> tag (str/starts-with? tag "v") (subs 1))))
        (:libs (basis/current-basis))))

(defn- fetch-cli
  "Downloads and extracts the platform binary, mirroring UikaCli.extractBinary's
  layout and its skip-if-already-extracted behaviour. tools.deps cannot resolve a
  zip-packaged artifact (jar-only), so this is a plain download from Maven Central;
  override the URL with UIKA_CLI_URL for mirrors and air-gapped setups."
  [version]
  (let [classifier (platform-classifier)
        binary-name (if (str/starts-with? classifier "windows") "uika.exe" "uika")
        cache-dir (io/file (System/getProperty "user.home")
                           ".cache" "uika" (str "cli-" version "-" classifier))
        binary (io/file cache-dir binary-name)]
    (when-not (.isFile binary)
      (.mkdirs cache-dir)
      (let [url (or (System/getenv "UIKA_CLI_URL")
                    (str "https://repo1.maven.org/maven2/net/exoego/uika/uika-cli/"
                         version "/uika-cli-" version "-" classifier ".zip"))
            zip-file (io/file cache-dir "cli.zip")]
        (println "uika: fetching" url)
        (with-open [in (io/input-stream url)]
          (io/copy in zip-file))
        (with-open [zf (ZipFile. zip-file)]
          (let [entry (or (->> (enumeration-seq (.entries zf))
                               (remove #(.isDirectory ^java.util.zip.ZipEntry %))
                               (filter #(let [n (.getName ^java.util.zip.ZipEntry %)]
                                          (or (= n binary-name)
                                              (str/ends-with? n (str "/" binary-name)))))
                               first)
                          (throw (ex-info (str binary-name " not found in " zip-file) {})))
                ;; temp + move, so a concurrent invocation never sees a partial binary
                tmp (Files/createTempFile (.toPath cache-dir) "uika" ".tmp"
                                          (make-array java.nio.file.attribute.FileAttribute 0))]
            (with-open [in (.getInputStream zf entry)]
              (Files/copy ^java.io.InputStream in ^Path tmp
                          ^"[Ljava.nio.file.CopyOption;"
                          (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING])))
            (.setExecutable (.toFile ^Path tmp) true false)
            (Files/move tmp (.toPath binary)
                        (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
        (.delete zip-file)))
    binary))

(defn- effective-jdk-release
  "Port of UikaCli.effectiveJdkRelease: clamp to the build JVM's ct.sym ceiling,
  nil disables. The plugins run on a JVM, so the layer defaults ON."
  [target]
  (when (and target (pos? (long target)))
    (let [ct-sym-max (dec (.feature (Runtime/version)))
          effective (min (long target) ct-sym-max)]
      (if (or (< effective 8)
              (not (.isFile (io/file (System/getProperty "java.home") "lib" "ct.sym"))))
        (do (println "uika: skipping the JDK API layer (no usable ct.sym in the build JVM)")
            nil)
        (do (when (< effective (long target))
              (println (str "uika: JDK API layer clamped to release " effective
                            " (the build JVM's ct.sym has no release " target ")")))
            effective)))))

(defn upgrade-check
  "Runs uika upgrade-check over a before/after pair of dumps.

  :before / :after   dump files (required)
  :fail-on           never | reachable | any (CLI default when omitted)
  :exclude-file      one path or a vector of paths
  :jdk-release       JDK API release; 0 disables, default is the running JVM's
  :class-load-log    one path or a vector: runtime class-load evidence in the CLI's
                     text format (JFR conversion is not implemented in this tool yet)
  :cli-version       uika-cli version; defaults to this tool's own git tag
  :cli-path          existing uika binary, skipping the download entirely"
  [{:keys [before after fail-on exclude-file jdk-release class-load-log cli-version cli-path]}]
  (when-not (and before after)
    (throw (ex-info "usage: clojure -Tuika upgrade-check :before <a.json> :after <b.json>" {})))
  (let [binary (if cli-path
                 (io/file (str cli-path))
                 (fetch-cli (str (or cli-version
                                     (System/getenv "UIKA_CLI_VERSION")
                                     (own-git-tag)
                                     (throw (ex-info "uika-cli version is unknown; pass :cli-version" {}))))))
        release (effective-jdk-release (or jdk-release (.feature (Runtime/version))))
        ->vec #(cond (nil? %) [] (sequential? %) (mapv str %) :else [(str %)])
        command (-> [(str binary) "upgrade-check" "--before" (str before) "--after" (str after)]
                    (into (when fail-on ["--fail-on" (name fail-on)]))
                    (into (mapcat #(vector "--exclude-file" %) (->vec exclude-file)))
                    (into (when release ["--jdk-release" (str release)]))
                    (into (mapcat #(vector "--class-load-log" %) (->vec class-load-log))))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true))]
    ;; UIKA_JDK, like the plugins: the child reads this JVM's ct.sym regardless of
    ;; the caller's JAVA_HOME.
    (when release
      (.put (.environment builder) "UIKA_JDK" (System/getProperty "java.home")))
    (let [process (.start builder)]
      ;; Streamed line by line, not inherited: -T tools may run under a wrapper that
      ;; captures stdout, the same reason the JVM plugins use a logger.
      (with-open [rdr (io/reader (.getInputStream process))]
        (doseq [line (line-seq rdr)]
          (println line)))
      (let [exit (.waitFor process)]
        (when-not (zero? exit)
          (throw (ex-info (case exit
                            1 "uika upgrade-check found broken references (see output above)"
                            (str "uika upgrade-check failed with exit code " exit))
                          {:exit exit})))))))
