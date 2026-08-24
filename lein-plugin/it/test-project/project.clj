(defproject example/consumer "0.1.0"
  :description "uika lein plugin integration fixture"
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.apache.commons/commons-lang3 #=(eval (or (System/getenv "COMMONS_LANG3_VERSION") "3.20.0"))]]
  ;; A dev-only dependency the dump must NOT contain: the dump is taken from the
  ;; project unmerged of the :default profiles (run.sh asserts absence).
  :profiles {:dev {:dependencies [[commons-io "2.20.0"]]}}
  ;; Java, no :aot: `lein compile` alone would not run javac here, so this locks the
  ;; dump on eval/prep rather than the compile task.
  :java-source-paths ["java"]
  ;; The shape `lein new app` emits. "%s" is re-formatted from the active profile
  ;; list on every unmerge, so this is the fixture that would catch the dump naming
  ;; a :compile-path nothing compiled into.
  :target-path "target/%s"
  :plugins [[net.exoego.uika/lein-uika "0.0.0-dev"]]
  :uika {:fail-on "reachable"
         :exclude-files ["uika-exclude.toml"]
         :jdk-release 11})
