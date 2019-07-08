(ns lein-modules.common
  (:require [leiningen.core.project :as prj]
            [lein-modules.dependencies :as dependencies]))

(defn config
  "Traverse all parents to accumulate a list of :modules config,
  ordered by least to most immediate ancestors"
  [project]
  (loop [p project, acc '()]
    (if (nil? p)
      (remove nil? acc)
      (recur (dependencies/parent p) (conj acc (-> p :modules))))))
