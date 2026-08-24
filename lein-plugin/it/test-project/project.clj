(defproject example/consumer "0.1.0"
  :description "uika lein plugin integration fixture"
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.apache.commons/commons-lang3 #=(eval (or (System/getenv "COMMONS_LANG3_VERSION") "3.20.0"))]]
  ;; A dev-only dependency the dump must NOT contain: the dump is taken from the
  ;; project unmerged of :base/:user/:dev/:provided (run.sh asserts absence).
  :profiles {:dev {:dependencies [[commons-io "2.20.0"]]}}
  :plugins [[net.exoego.uika/lein-uika "0.0.0-dev"]]
  :uika {:fail-on "reachable"
         :exclude-files ["uika-exclude.toml"]
         :jdk-release 11})
