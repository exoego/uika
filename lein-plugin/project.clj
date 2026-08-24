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
  ;; eval-in-leiningen: this is a plugin, its code runs inside lein's own JVM.
  :eval-in-leiningen true
  :deploy-repositories [["staging" {:url "file:target/staging-deploy" :sign-releases false}]])
