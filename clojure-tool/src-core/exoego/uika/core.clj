(ns exoego.uika.core
  "Shared between the Clojure CLI tool and the Leiningen plugin: v2 dump writing,
  CLI fetch and execution, and the --jdk-release clamp. Deliberately free of
  tools.deps so the Leiningen plugin does not drag a second resolver onto its
  plugin classpath. Ports of jvm-plugin-core carry keep-in-sync markers."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files Path StandardCopyOption)
           (java.util.zip ZipFile)))

(set! *warn-on-reflection* true)

(defn lib->artifact
  "One v2 dump artifact entry for a resolved lib. A bare symbol means group = name,
  the Clojure convention (commons-io/commons-io is written commons-io). A nil version
  collapses the entry to path-only: that is what git, :local/root and file
  dependencies are, nothing the version diff compares."
  [lib version path]
  (if version
    {"group" (or (namespace lib) (name lib)) "name" (name lib) "version" version
     "root" 0 "path" (str path)}
    {"root" 0 "path" (str path)}))

(defn dump-json
  "The v2 dump as a string: one module, one empty root (paths stay absolute).
  `artifacts` are lib->artifact entries. jdkRelease is this JVM's feature release,
  same as DumpFormat.buildJvmRelease."
  [module-name artifacts class-dirs]
  (let [artifact-maps (vec artifacts)
        module {"module" module-name
                "classesDirs" (mapv (fn [^String p] {"root" 0 "path" p}) class-dirs)
                "artifactRefs" (vec (range (count artifact-maps)))}]
    (json/write-str {"version" 2
                     "jdkRelease" (.feature (Runtime/version))
                     "roots" [""]
                     "artifacts" artifact-maps
                     "modules" [module]})))

(defn- env
  "An environment variable, treating blank as unset. A CI `env:` block whose value
  interpolates an unset input exports the empty string, which is truthy in Clojure:
  UIKA_CLI_PATH= would exec \"\" and UIKA_CLI_VERSION= would build a Central URL with
  an empty version segment. The Java side guards the same way (UikaCli's isBlank)."
  [name]
  (let [value (System/getenv name)]
    (when-not (str/blank? value) value)))

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

(defn fetch-cli
  "Downloads and extracts the platform binary, mirroring UikaCli.extractBinary's
  layout and its skip-if-already-extracted behaviour. tools.deps and Leiningen both
  resolve jar artifacts only, so the zip-packaged distribution is a plain download
  from Maven Central; override the URL with UIKA_CLI_URL for mirrors and air-gapped
  setups."
  [version]
  (let [classifier (platform-classifier)
        binary-name (if (str/starts-with? classifier "windows") "uika.exe" "uika")
        cache-dir (io/file (System/getProperty "user.home")
                           ".cache" "uika" (str "cli-" version "-" classifier))
        binary (io/file cache-dir binary-name)]
    (when-not (.isFile binary)
      (.mkdirs cache-dir)
      (let [url (or (env "UIKA_CLI_URL")
                    (str "https://repo1.maven.org/maven2/net/exoego/uika/uika-cli/"
                         version "/uika-cli-" version "-" classifier ".zip"))
            ;; Invocation-unique, deleted below: a fixed name in the shared cache
            ;; would let two cold-cache invocations read each other's half-written
            ;; download. The binary itself is already temp-file + atomic move.
            zip-file (.toFile (Files/createTempFile (.toPath cache-dir) "cli" ".zip"
                                                    (make-array java.nio.file.attribute.FileAttribute 0)))]
        (println "uika: fetching" url)
        (try
          (with-open [in (io/input-stream url)]
            ;; 64 KiB, not io/copy's 1 KiB default: the zip is multi-MB.
            (io/copy in zip-file :buffer-size 65536))
          (with-open [zf (ZipFile. ^java.io.File zip-file)]
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
              (try
                (with-open [in (.getInputStream zf entry)]
                  (Files/copy ^java.io.InputStream in ^Path tmp
                              ^"[Ljava.nio.file.CopyOption;"
                              (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING])))
                (.setExecutable (.toFile ^Path tmp) true false)
                (Files/move tmp (.toPath binary)
                            (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                (finally
                  ;; A truncated entry or a full disk between createTempFile and the
                  ;; move would otherwise orphan a binary-sized .tmp in the shared
                  ;; cache on every retry, and nothing ever reaps it.
                  (Files/deleteIfExists tmp)))))
          (finally
            (.delete ^java.io.File zip-file)))))
    binary))

(defn resolve-binary
  "The uika binary to run. An explicit path wins (:cli-path, else UIKA_CLI_PATH);
  otherwise the first known version of :cli-version, UIKA_CLI_VERSION, or
  `default-version-fn` is fetched. Config beats environment for BOTH knobs: an
  ambient UIKA_CLI_PATH must not silently defeat a path written in project.clj,
  which is how the version knob already behaved.

  Shared so the two frontends cannot drift, and so UIKA_CLI_PATH works in both. It
  used to be read only by the Leiningen plugin, where the positional argv has no
  room for the per-run override that `-T` expresses as :cli-path."
  [{:keys [cli-path cli-version]} default-version-fn usage-hint]
  (if-let [path (or cli-path (env "UIKA_CLI_PATH"))]
    (io/file (str path))
    (fetch-cli (str (or cli-version
                        (env "UIKA_CLI_VERSION")
                        (default-version-fn)
                        (throw (ex-info usage-hint {})))))))

(defn- release-number
  "The knob as a long. Deliberately NOT part of the UikaCli.effectiveJdkRelease port:
  the Java side takes an int from typed plugin config, while both Clojure frontends
  take whatever the user wrote in project.clj or :exec-args, where a string is the
  natural spelling next to :fail-on \"reachable\". A bare (long \"11\") throws a
  ClassCastException no caller catches, so lein answers it with a full cause trace."
  [target]
  (let [reject #(throw (ex-info (str "uika: :jdk-release must be a number, got "
                                     (pr-str target))
                                {:jdk-release target}))]
    (cond
      (nil? target) nil
      (number? target) (long target)
      (string? target) (try
                         (Long/parseLong (str/trim target))
                         (catch NumberFormatException _ (reject)))
      :else (reject))))

(defn- effective-jdk-release
  "Port of UikaCli.effectiveJdkRelease: clamp to the build JVM's ct.sym ceiling,
  nil disables. The plugins run on a JVM, so the layer defaults ON."
  [target']
  (when-let [target (release-number target')]
    (when (pos? target)
      (let [ct-sym-max (dec (.feature (Runtime/version)))
            effective (min target ct-sym-max)]
        (if (or (< effective 8)
                (not (.isFile (io/file (System/getProperty "java.home") "lib" "ct.sym"))))
          (do (println "uika: skipping the JDK API layer (no usable ct.sym in the build JVM)")
              nil)
          (do (when (< effective target)
                (println (str "uika: JDK API layer clamped to release " effective
                              " (the build JVM's ct.sym has no release " target ")")))
              effective))))))

(defn run-upgrade-check
  "Runs the binary and throws on a non-zero exit. Output is streamed line by line,
  not inherited: the caller may sit under a wrapper that captures stdout, the same
  reason the JVM plugins route through a logger."
  [binary {:keys [before after fail-on exclude-file jdk-release class-load-log]}]
  (let [release (effective-jdk-release (or jdk-release (.feature (Runtime/version))))
        ->vec #(cond (nil? %) [] (sequential? %) (mapv str %) :else [(str %)])
        command (-> [(str binary) "upgrade-check" "--before" (str before) "--after" (str after)]
                    (into (when fail-on ["--fail-on" (name fail-on)]))
                    (into (mapcat #(vector "--exclude-file" %) (->vec exclude-file)))
                    (into (when release ["--jdk-release" (str release)]))
                    (into (mapcat #(vector "--class-load-log" %) (->vec class-load-log))))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true))]
    ;; UIKA_JDK, like the JVM plugins: the child reads this JVM's ct.sym regardless
    ;; of the caller's JAVA_HOME.
    (when release
      (.put (.environment builder) "UIKA_JDK" (System/getProperty "java.home")))
    (let [process (.start builder)]
      (with-open [rdr (io/reader (.getInputStream process))]
        (doseq [line (line-seq rdr)]
          (println line)))
      (let [exit (.waitFor process)]
        (when-not (zero? exit)
          (throw (ex-info (case exit
                            1 "uika upgrade-check found broken references (see output above)"
                            (str "uika upgrade-check failed with exit code " exit))
                          {:exit exit})))))))
