(ns build
  "Stages the clojure-uika artifacts for the Maven Central deployment:
  `clojure -T:build stage` writes the jar, a sources jar, an empty javadoc jar
  (Central requires the jar to exist, not to have content; readers have the
  sources jar, like every other uika plugin) and the pom, each with the md5/sha1
  pair jreleaser.yml expects staged (its deployer has checksums: false), into
  target/staging-deploy in Maven repository layout.

  UIKA_VERSION names the release, injected by the release workflow exactly as it
  is for the other plugins; the in-tree default matches their placeholder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import (java.security MessageDigest)))

(def ^:private lib 'net.exoego.uika/clojure-uika)
(def ^:private version
  (or (not-empty (System/getenv "UIKA_VERSION")) "0.0.0-dev"))
(def ^:private class-dir "target/classes")

;; JfrEvidence only, not all of jvm-plugin-core: it is the one class core.clj cannot
;; hand-port (binary JFR parsing belongs to the JDK's own reader), while the small
;; UikaCli ports stay in src-core with their keep-in-sync markers.
(def ^:private core-java-src
  "../jvm-plugin-core/src/main/java/net/exoego/uika/plugin/core/JfrEvidence.java")
(def ^:private core-java-dir "target/core-java")
(def ^:private core-classes-dir "target/core-classes")

(defn javac
  "Compiles the vendored jvm-plugin-core source (JfrEvidence) into
  target/core-classes, which the :test alias puts on the classpath and `stage`
  copies into the jar. --release 17 is the same floor every build compiling
  jvm-plugin-core sets, and it is the tool's floor only for JFR conversion: the
  class loads lazily, so everything else still runs on older JVMs."
  [_]
  (b/copy-file {:src core-java-src
                :target (str core-java-dir
                             "/net/exoego/uika/plugin/core/JfrEvidence.java")})
  (b/javac {:src-dirs [core-java-dir]
            :class-dir core-classes-dir
            :javac-opts ["--release" "17"]}))

(defn- hex-digest [algorithm ^java.io.File file]
  (let [digest (.digest (MessageDigest/getInstance algorithm)
                        (java.nio.file.Files/readAllBytes (.toPath file)))]
    (str/join (map #(format "%02x" %) digest))))

(defn- stage-file
  "Copies `src` into the staging repo under `file-name` and writes the md5/sha1
  pair next to it."
  [staging-dir ^java.io.File src file-name]
  (let [dest (io/file staging-dir file-name)]
    (io/make-parents dest)
    (io/copy src dest)
    (doseq [[algorithm ext] [["MD5" ".md5"] ["SHA-1" ".sha1"]]]
      (spit (io/file staging-dir (str file-name ext)) (hex-digest algorithm dest)))))

(defn- strip-repositories
  "write-pom emits an empty <repositories/> element even with :mvn/repos gone,
  and PomChecker rejects the element's presence. A POPULATED block instead means
  the :mvn/repos dissoc regressed, which must fail here rather than in the
  Central deployment's all-or-nothing validation."
  [^java.io.File pom]
  (let [text (slurp pom)]
    (when (str/includes? text "<repositories>")
      (throw (ex-info "pom carries a populated <repositories> block; the :mvn/repos dissoc regressed" {})))
    (spit pom (str/replace text #"(?m)^\s*<repositories/>\r?\n" ""))))

(defn stage [_]
  (doseq [path ["target/classes" "target/staging-deploy" "target/javadoc-empty"
                "target/sources" core-java-dir core-classes-dir]]
    (b/delete {:path path}))
  (let [basis (-> (b/create-basis {:project "deps.edn"})
                  ;; write-pom copies non-central entries from :mvn/repos into a
                  ;; <repositories> block, and the user/root deps.edn contributes
                  ;; clojars. PomChecker rejects any <repositories> block outright
                  ;; (the same rule lein-stage works around), failing the whole
                  ;; all-or-nothing deployment. Dropping the key still leaves an
                  ;; empty <repositories/> element behind, which strip-repositories
                  ;; removes below.
                  (dissoc :mvn/repos))
        artifact (name lib)
        ;; Derived from lib like the pom path below: a hardcoded segment would stage
        ;; under the old group after a rename and fail Central's path-vs-pom validation.
        staging-dir (str "target/staging-deploy/"
                         (str/replace (namespace lib) "." "/")
                         "/" artifact "/" version)
        jar-file (str "target/" artifact "-" version ".jar")
        sources-file (str "target/" artifact "-" version "-sources.jar")
        javadoc-file (str "target/" artifact "-" version "-javadoc.jar")]
    (javac nil)
    (b/copy-dir {:src-dirs ["src" "src-core" core-classes-dir] :target-dir class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :scm {:url "https://github.com/exoego/uika"
                        :connection "scm:git:https://github.com/exoego/uika.git"
                        :developerConnection "scm:git:git@github.com:exoego/uika.git"
                        :tag (str "v" version)}
                  :pom-data [[:description "Clojure CLI tool for writing uika resolved classpath dumps and running upgrade checks"]
                             [:url "https://github.com/exoego/uika"]
                             [:licenses
                              [:license
                               [:name "Apache License 2.0"]
                               [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]
                             [:developers
                              [:developer
                               [:id "exoego"]
                               [:name "TATSUNO Yasuhiro"]
                               [:url "https://github.com/exoego"]]]]})
    ;; Strip before b/jar so the copy embedded under META-INF/maven matches the
    ;; staged .pom byte for byte.
    (strip-repositories (io/file class-dir "META-INF" "maven" (namespace lib) artifact "pom.xml"))
    ;; The jar already holds the Clojure sources (nothing is AOT-compiled); the
    ;; Central-required sources jar carries the same trees plus the vendored
    ;; JfrEvidence.java in place of its compiled class.
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (b/copy-dir {:src-dirs ["src" "src-core" core-java-dir] :target-dir "target/sources"})
    (b/jar {:class-dir "target/sources" :jar-file sources-file})
    (.mkdirs (io/file "target/javadoc-empty"))
    (b/jar {:class-dir "target/javadoc-empty" :jar-file javadoc-file})
    (stage-file staging-dir
                (io/file class-dir "META-INF" "maven" (namespace lib) artifact "pom.xml")
                (str artifact "-" version ".pom"))
    (doseq [f [jar-file sources-file javadoc-file]]
      (stage-file staging-dir (io/file f) (.getName (io/file f))))
    (println "staged" artifact version "into" staging-dir)))
