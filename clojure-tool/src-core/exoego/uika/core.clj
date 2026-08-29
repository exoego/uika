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
  `artifacts` are lib->artifact entries.

  jdkRelease is the feature release of the JVM the PROJECT runs on, which for the
  `-T` tool is this one but for the Leiningen plugin is not: :eval-in-leiningen
  pins the plugin to lein's own JVM while project code runs under :java-cmd. The
  field exists so upgrade-check can spot a JDK move between two dumps, so it has
  to track the runtime, not whoever happened to write the file.

  It is written on the module as well as on the dump, the shape the JVM plugins
  emit for builds that mix releases across modules. Neither frontend has more than
  one module, so here the two are always the same number."
  ([module-name artifacts class-dirs]
   (dump-json module-name artifacts class-dirs (.feature (Runtime/version))))
  ([module-name artifacts class-dirs jdk-release]
   (let [artifact-maps (vec artifacts)
         ;; Omitted when nil, like DumpFormat's writer: a below-floor project JVM (lein
         ;; probing a JDK 7 :java-cmd) has nothing servable to record, and a dump naming
         ;; a below-floor release hard-fails the CLI's JDK-pair run.
         module (cond-> {"module" module-name
                         "classesDirs" (mapv (fn [^String p] {"root" 0 "path" p}) class-dirs)
                         "artifactRefs" (vec (range (count artifact-maps)))}
                  jdk-release (assoc "jdkRelease" jdk-release))]
     (json/write-str (cond-> {"version" 2
                              "roots" [""]
                              "artifacts" artifact-maps
                              "modules" [module]}
                       jdk-release (assoc "jdkRelease" jdk-release))))))

(defn- env
  "An environment variable, treating blank as unset. A CI `env:` block whose value
  interpolates an unset input exports the empty string, which is truthy in Clojure:
  UIKA_CLI_PATH= would exec \"\" and UIKA_CLI_VERSION= would build a Central URL with
  an empty version segment. The Java side guards the same way (UikaCli's isBlank)."
  [name]
  (let [value (System/getenv name)]
    (when-not (str/blank? value) value)))

(def cli-group
  "Port of UikaCli.GROUP; keep the two in sync (pinned by the sync test scraping the
  Java source). A coordinate rename updates the four JVM plugins through the constant
  (Bazel's cli_repository.bzl hand-syncs its own path and needs its own edit), and
  without the port it would silently strand the two Clojure frontends."
  "net.exoego.uika")

(def cli-artifact
  "Port of UikaCli.ARTIFACT; keep the two in sync."
  "uika-cli")

(def min-release
  "Port of UikaCli.MIN_RELEASE; keep the two in sync. The lowest release the JDK API
  layer can serve, since ct.sym carries no older stubs."
  8)

(defn- platform-classifier
  "Port of UikaCli.platformClassifier; keep the two in sync."
  []
  ;; Locale.ROOT like the Java original: the default locale turns an uppercase I
  ;; into a dotless one on Turkish-locale machines, which would split the two ports.
  (let [os (.toLowerCase ^String (System/getProperty "os.name" "") java.util.Locale/ROOT)
        arch (.toLowerCase ^String (System/getProperty "os.arch" "") java.util.Locale/ROOT)
        x64 (contains? #{"amd64" "x86_64"} arch)
        arm64 (contains? #{"aarch64" "arm64"} arch)]
    (cond
      (and (str/includes? os "linux") x64) "linux-x86_64"
      (and (str/includes? os "mac") arm64) "macos-aarch64"
      (and (str/includes? os "mac") x64) "macos-x86_64"
      (and (str/includes? os "windows") x64) "windows-x86_64"
      :else (throw (ex-info (str "no uika-cli binary is published for " os "/" arch
                                 " (available: linux-x86_64, macos-aarch64,"
                                 " macos-x86_64, windows-x86_64)")
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
                    (str "https://repo1.maven.org/maven2/"
                         (str/replace cli-group "." "/") "/" cli-artifact "/"
                         version "/" cli-artifact "-" version "-" classifier ".zip"))
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
  room for the per-run override that `-T` expresses as :cli-path.

  The environment reader is a parameter on the four-argument arity purely so a test
  can be hermetic: System/getenv cannot be stubbed, and a developer or CI runner
  with UIKA_CLI_PATH exported would otherwise steer the test, or with
  UIKA_CLI_VERSION set make it download from Maven Central."
  ([config default-version-fn usage-hint]
   (resolve-binary config default-version-fn usage-hint env))
  ([{:keys [cli-path cli-version]} default-version-fn usage-hint env]
  ;; blank->nil on the CONFIG keys too, not just the environment: the `env` helper above
  ;; exists because an empty string is truthy in Clojure, and a CI-templated :exec-args
  ;; reaches these two keys the same way a CI `env:` block reaches the variables. Without
  ;; it :cli-path "" execs the empty string and :cli-version "" builds a Central URL with
  ;; an empty version segment.
  (let [cli-path (when-not (str/blank? (some-> cli-path str)) cli-path)
        cli-version (when-not (str/blank? (some-> cli-version str)) cli-version)]
    (if-let [path (or cli-path (env "UIKA_CLI_PATH"))]
      (io/file (str path))
      (fetch-cli (str (or cli-version
                          (env "UIKA_CLI_VERSION")
                          (default-version-fn)
                          (throw (ex-info usage-hint {})))))))))

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
      ;; Integral only: (long 11.9) truncates to 11 without a word, while the string
    ;; "11.9" is rejected -- the same typo should not land two different ways.
    (and (number? target) (== target (long target))) (long target)
      (string? target) (try
                         (Long/parseLong (str/trim target))
                         (catch NumberFormatException _ (reject)))
      :else (reject))))

(defn parse-jvm-properties
  "java.home and the feature release out of `java -XshowSettings:properties -version`
  output, or nil when either is missing.

  The value runs to END OF LINE, never `\\S+`: a java.home with a space in it
  (`C:\\Program Files\\...` is the common one, and macOS allows it too) would be
  truncated at the space, the ct.sym probe would then miss, and the JDK API layer
  would silently switch off with a message blaming a missing ct.sym."
  [output]
  (let [value (fn [key]
                (some-> (re-find (re-pattern (str key "\\s*=\\s*(.*)")) (str output))
                        second
                        str/trim
                        not-empty))
        home (value "java\\.home")
        spec (value "java\\.specification\\.version")]
    (when (and home spec)
      ;; JDK 8 spells the release 1.8; UikaCli.parseRelease strips the same prefix on
      ;; the Java side. Without it the probe answers nil, lein falls back to its OWN
      ;; JVM, and the dump records the writing JVM's release -- the issue #128
      ;; mis-attribution -- for a release that is inside the supported range.
      (let [spec (if (str/starts-with? spec "1.") (subs spec 2) spec)]
        (try
          {:home home :feature (Long/parseLong spec)}
          (catch NumberFormatException _ nil))))))

(defn declared-release
  "Port of UikaCli.declaredRelease and parseRelease; keep them in sync. The API release
  a compiler-option list targets, or nil when it declares none. Both the space-separated
  and the --release=17 forms, -target as the weaker fallback, and the legacy 1.8 /
  jvm-1.8 spellings normalized to 8. Anything below min-release is no declaration at all, so one
  legacy module cannot drag a build's minimum under the floor and switch the layer off."
  [options]
  (let [parse (fn [value]
                (when value
                  (let [text (str/trim (str value))
                        text (if (str/starts-with? text "jvm-") (subs text 4) text)
                        text (if (str/starts-with? text "1.") (subs text 2) text)
                        release (try (Long/parseLong text)
                                     (catch NumberFormatException _ nil))]
                    (when (and release (>= release min-release)) release))))
        options (mapv str (or options []))
        release-flags #{"--release" "-release" "--java-output-version" "-java-output-version"}
        target-flags #{"-target" "--target"}]
    (loop [i 0 target nil]
      (if (>= i (count options))
        target
        (let [option (nth options i)
              sep (let [e (str/index-of option "=") c (str/index-of option ":")]
                    (cond (and e c) (min e c) e e :else c))
              flag (if sep (subs option 0 sep) option)
              value (if sep
                      (subs option (inc sep))
                      (when (< (inc i) (count options)) (nth options (inc i))))
              release (parse value)]
          (cond
            (nil? release) (recur (inc i) target)
            (contains? release-flags flag) release
            (and (nil? target) (contains? target-flags flag)) (recur (inc i) release)
            :else (recur (inc i) target)))))))

(defn override-release
  "Port of UikaCli.overrideRelease; keep the two in sync. The release an explicit
  :jdk-release names for the DUMP, or nil when it names none.

  The knob answers two questions at once, and this is the second one: which release the
  application runs on. It is the escape hatch for what the derivation cannot see, a project
  that ships on a JVM newer than anything it declares. Zero is not that statement, it means
  \"switch the API layer off\", so the dump keeps its derived value rather than taking JDK
  move detection down with the layer. Below min-release is dropped for a harder reason: a dump naming
  it sends upgrade-check to ask ct.sym for a release it has never carried, failing the run."
  [value]
  (when-let [release (release-number value)]
    (when (>= (long release) min-release) (long release))))

(defn this-jvm
  "The JVM running this code, in the shape effective-jdk-release and the UIKA_JDK
  export want. The `-T` tool runs project code on the same JVM, so this is its
  answer; the Leiningen plugin overrides it with the project's :java-cmd JVM."
  []
  {:home (System/getProperty "java.home")
   :feature (.feature (Runtime/version))})

(defn- effective-jdk-release
  "Port of UikaCli.effectiveJdkRelease: clamp to the ct.sym ceiling of `jvm`,
  nil disables. The plugins run on a JVM, so the layer defaults ON.

  `jvm` is the project's runtime JVM, not necessarily the one evaluating this: its
  ct.sym is what the CLI reads (it is exported as UIKA_JDK), so the ceiling and the
  file check have to come from the same JVM the flag is about."
  [target' {:keys [home feature]}]
  (when-let [target (release-number target')]
    (when (pos? target)
      (let [ct-sym-max (dec (long feature))
            effective (min target ct-sym-max)]
        ;; The messages name `home`, never "the build JVM". For the Leiningen plugin the two
        ;; differ by design, and blaming lein's own JVM sends the user to inspect a ct.sym
        ;; that was never consulted. Two reasons, two messages, for the same reason.
        (cond
          (< effective min-release)
          (do (println (str "uika: skipping the JDK API layer (release " effective
                            " is below the lowest release ct.sym serves, " min-release ")"))
              nil)

          (not (.isFile (io/file home "lib" "ct.sym")))
          (do (println (str "uika: skipping the JDK API layer (no usable ct.sym in " home ")"))
              nil)

          :else
          (do (when (< effective target)
                (println (str "uika: JDK API layer clamped to release " effective
                              " (the ct.sym in " home " has no release " target ")")))
              effective))))))

(defn- jfr-evidence
  "The compiled net.exoego.uika.plugin.core.JfrEvidence out of jvm-plugin-core, as
  {:class c}, or {:missing why} when this frontend cannot convert recordings. Looked
  up reflectively rather than imported: an import would fail the whole namespace
  load on a source install that never ran javac, taking the text-log flow down with
  it. Java 17 is the class-file floor every build compiling jvm-plugin-core guards,
  and an older JVM answers the load with UnsupportedClassVersionError, not
  ClassNotFoundException, so the two absences get their own messages."
  []
  (try {:class (Class/forName "net.exoego.uika.plugin.core.JfrEvidence")}
       (catch ClassNotFoundException _
         {:missing (str "the compiled JfrEvidence class is not on the classpath"
                        " (the Maven-distributed artifact carries it; a source"
                        " install does not)")})
       (catch UnsupportedClassVersionError _
         {:missing (str "JFR conversion needs a Java 17+ runtime, and this JVM is "
                        (.feature (Runtime/version)))})))

(defn- rewrite-evidence
  "JfrEvidence.rewrite: recordings among `entries` (files, or found under directory
  entries) are converted into `work-dir`'s workdir subdirectory and replaced by the
  converted text paths; text entries pass through unchanged. Reflection throughout,
  for the reason jfr-evidence gives; the workdir leaf name comes from the class's
  own WORK_DIR_NAME so the frontends cannot drift from the JVM plugins."
  [^Class cls entries work-dir]
  (let [method (.getMethod cls "rewrite"
                           (into-array Class [java.util.List java.nio.file.Path
                                              java.util.function.Consumer]))
        work-dir-name (str (-> cls (.getField "WORK_DIR_NAME") (.get nil)))
        result (.invoke method nil
                        (object-array
                         [(mapv (fn [entry] (.toPath (io/file (str entry)))) entries)
                          (.toPath (io/file (str work-dir) work-dir-name))
                          (reify java.util.function.Consumer
                            (accept [_ line] (println line)))]))]
    (mapv str result)))

(defn run-upgrade-check
  "Runs the binary and throws on a non-zero exit. Output is streamed line by line,
  not inherited: the caller may sit under a wrapper that captures stdout, the same
  reason the JVM plugins route through a logger.

  Port of UikaCli.runUpgradeCheck's command building. Keep the two in sync. A flag
  added there also needs the key here and, for Leiningen, in `option-keys`."
  [binary {:keys [before after fail-on exclude-file jdk-release class-load-log
                  draft-exclude-file jvm jfr evidence-work-dir]}]
  (let [jvm (or jvm (this-jvm))
        release (effective-jdk-release (or jdk-release (:feature jvm)) jvm)
        ;; Blank drops out for the reason `env` gives above, since a CI-templated
        ;; project.clj interpolating an unset input yields "" rather than nil.
        ->vec #(into [] (comp (map str) (remove str/blank?))
                     (cond (nil? %) [] (sequential? %) % :else [%]))
        ;; :jfr values join the class-load list and the WHOLE list goes through
        ;; JfrEvidence.rewrite, the JVM plugins' shape: a recording handed to
        ;; :class-load-log is converted too, and a directory entry keeps its text
        ;; logs while contributing any recordings found under it. Without the
        ;; compiled class, text-only entries keep the old pass-through (a source
        ;; install loses only conversion), while an explicit :jfr fails with the
        ;; reason instead of forwarding a binary the CLI would silently skip.
        evidence (let [entries (into (->vec class-load-log) (->vec jfr))]
                   (if (empty? entries)
                     entries
                     (let [{:keys [class missing]} (jfr-evidence)]
                       (cond
                         class (rewrite-evidence class entries
                                                 (or evidence-work-dir "target/uika"))
                         (seq (->vec jfr))
                         (throw (ex-info (str "uika: cannot convert JFR evidence: "
                                              missing)
                                         {:jfr jfr}))
                         :else entries))))
        command (-> [(str binary) "upgrade-check" "--before" (str before) "--after" (str after)]
                    ;; Blank drops out like the Java side's isBlank guard: a CI-templated
                    ;; :fail-on "" must run on the CLI default, not hand clap --fail-on ""
                    ;; and die with a usage error at exit 2.
                    (into (let [value (some-> fail-on name)]
                            (when-not (str/blank? value) ["--fail-on" value])))
                    (into (mapcat #(vector "--exclude-file" %) (->vec exclude-file)))
                    (into (when release ["--jdk-release" (str release)]))
                    (into (mapcat #(vector "--class-load-log" %) evidence))
                    ;; ->vec even for this single-valued flag. Its lein neighbours are
                    ;; vectors, and a bare (str ["x.toml"]) is a legal filename, so the
                    ;; draft would land in `["x.toml"]` with the run still exiting 0.
                    ;; No pairing check needed. The CLI's error names --class-load-log.
                    (into (mapcat #(vector "--draft-exclude-file" %)
                                  (->vec draft-exclude-file))))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true))]
    ;; UIKA_JDK, like the JVM plugins: the child reads the PROJECT JVM's ct.sym
    ;; regardless of the caller's JAVA_HOME.
    (when release
      (.put (.environment builder) "UIKA_JDK" (:home jvm)))
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
