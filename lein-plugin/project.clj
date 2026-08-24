;; The version is stamped by the release workflow via UIKA_VERSION, exactly as
;; -PuikaVersion and -Drevision do for the other plugins; the in-tree placeholder
;; matches cli/Cargo.toml. project.clj is code, so the env read needs no plugin.
(defproject net.exoego.uika/lein-uika #=(eval (or (System/getenv "UIKA_VERSION") "0.0.0-dev"))
  :description "Leiningen plugin for writing uika resolved classpath dumps and running upgrade checks"
  :url "https://github.com/exoego/uika"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  :scm {:name "git"
        :url "https://github.com/exoego/uika"
        :connection "scm:git:https://github.com/exoego/uika.git"
        :developerConnection "scm:git:git@github.com:exoego/uika.git"}
  ;; Central validates a developers section; lein has no first-class key for it.
  :pom-addition [:developers [:developer
                              [:id "exoego"]
                              [:name "TATSUNO Yasuhiro"]
                              [:url "https://github.com/exoego"]]]
  ;; The dump/CLI logic is shared with the Clojure CLI tool by source inclusion,
  ;; the same pattern the JVM plugins use for jvm-plugin-core. src-core is free of
  ;; tools.deps on purpose: Leiningen resolves with its own Aether, and a second
  ;; resolver on the plugin classpath would be pure baggage.
  :source-paths ["src" "../clojure-tool/src-core"]
  :dependencies [[org.clojure/data.json "2.5.2"]]
  ;; get-dependencies grew its :managed-dependencies arity in 2.7.0. Without a floor
  ;; an older lein answers with a raw ArityException instead of lein's own message.
  :min-lein-version "2.9.0"
  ;; Central requires both jars next to the main artifact. :classifiers makes lein
  ;; build them and stage them with the md5/sha1 pair, so no post-hoc fixup script is
  ;; needed; the sources jar follows :source-paths, so it cannot drift from the list
  ;; above. Javadoc is empty like every other uika plugin (see PUBLISHING.md).
  :classifiers {:sources {}
                :javadoc {:source-paths ^:replace []
                          :resource-paths ^:replace []
                          :java-source-paths ^:replace []
                          :omit-source true}}
  ;; eval-in-leiningen: this is a plugin, its code runs inside lein's own JVM.
  :eval-in-leiningen true
  ;; :no-auth, not just </dev/null in the Makefile: lein skips its credentials probe
  ;; only for a URL matching #"(file|scp|scpexe)://", and a single-slash relative
  ;; file: URL does not match, so `lein deploy staging` prompts for a username and
  ;; blocks on stdin. Spelling the URL file://target/... instead would make "target"
  ;; the URI authority and deploy to /staging-deploy.
  :deploy-repositories [["staging" {:url "file:target/staging-deploy"
                                    :no-auth true
                                    :sign-releases false}]])
