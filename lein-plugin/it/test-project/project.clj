(defproject example/consumer "0.1.0"
  :description "uika lein plugin integration fixture"
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.apache.commons/commons-lang3 #=(eval (or (System/getenv "COMMONS_LANG3_VERSION") "3.20.0"))]]
  :plugins [[net.exoego.uika/lein-uika "0.0.0-dev"]]
  :uika {:fail-on "reachable"})
