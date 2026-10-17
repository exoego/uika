(ns exoego.run-tests
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

;; Discovered from the directory, not listed, so a new test file cannot be skipped silently.
(defn- test-namespaces []
  (let [root (.toPath (io/file "test"))]
    (->> (file-seq (io/file "test"))
         (map #(str (.relativize root (.toPath %))))
         (filter #(str/ends-with? % "_test.clj"))
         (map #(-> (subs % 0 (- (count %) (count ".clj")))
                   (str/replace java.io.File/separator ".")
                   (str/replace "_" "-")
                   symbol))
         sort)))

(defn -main [& _]
  (let [nss (test-namespaces)]
    (apply require nss)
    (let [summary (apply test/run-tests nss)]
      (shutdown-agents)
      (System/exit (if (test/successful? summary) 0 1)))))
