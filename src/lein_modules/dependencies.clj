;; Copyright (c) 2019 BigML, Inc
;; All rights reserved.

;; Author: jao <jao@bigml.com>
;; Start date: Sun Jul 07, 2019 23:31

(ns lein-modules.dependencies
  "Discovering dependencies among submodules"
  (:require [leiningen.core.utils :as utils]
            [leiningen.core.project :as prj]
            [leiningen.core.main :refer (version-satisfies? leiningen-version)]
            [lein-modules.compression :refer (compressed-profiles)]
            [clojure.java.io :as io]))

(defn with-profiles
  "Apply profiles to project"
  [project profiles]
  (when project
    (let [profiles (filter (set profiles) (-> project meta :profiles keys))]
      (prj/set-profiles project profiles))))

(def read-project (if (version-satisfies? (leiningen-version) "2.5")
                    (load-string "#(prj/init-profiles (prj/project-with-profiles (prj/read-raw %)) [:default])")
                    prj/read))

(defn parent
  "Return the project's parent project"
  ([project]
     (parent project (compressed-profiles project)))
  ([project profiles]
     (let [p (get-in project [:modules :parent] ::none)]
       (cond
         (map? p) p                        ; handy for testing
         (not p) nil                       ; don't search for parent
         :else (as-> (if (= p ::none) nil p) $
                 (or $ (-> project :parent prj/dependency-map :relative-path) "..")
                 (.getCanonicalFile (io/file (:root project) $))
                 (if (.isDirectory $) $ (.getParentFile $))
                 (io/file $ "project.clj")
                 (when (.exists $) (read-project (str $)))
                 (when $ (with-profiles $ profiles)))))))

(defn child?
  "Return true if child is an immediate descendant of project"
  [project child]
  (= (:root project) (:root (parent child))))

(defn file-seq-sans-symlinks
  "A tree seq on java.io.Files that aren't symlinks"
  [dir]
  (tree-seq
    (fn [^java.io.File f] (and (.isDirectory f) (not (utils/symlink? f))))
    (fn [^java.io.File d] (seq (.listFiles d)))
    dir))

(defn- try-read [f]
  (try (read-project f) (catch Exception e (println (.getMessage e)))))

(defn children
  "Return the child maps for a project according to its active profiles"
  [project & [no-prof?]]
  (if-let [dirs (-> project :modules :dirs)]
    (remove nil?
            (map (comp try-read
                       (memfn getCanonicalPath)
                       #(io/file (:root project) % "project.clj"))
                 dirs))
    (let [cs (->> (file-seq-sans-symlinks (io/file (:root project)))
                  (filter #(= "project.clj" (.getName %)))
                  (remove #(= (:root project) (.getParent %)))
                  (map (comp try-read str))
                  (remove nil?))]
      (if no-prof?
        cs
        (filter #(child? project (with-profiles % (compressed-profiles project)))
                cs)))))

(defn id
  "Returns fully-qualified symbol identifier for project"
  [project]
  (if project
    (symbol (:group project) (:name project))))

(defn progeny
  "Recursively return the project's children in a map keyed by id"
  ([project]
     (progeny project (compressed-profiles project)))
  ([project profiles]
     (let [kids (children (with-profiles project profiles))]
       (apply merge
         (into {} (map (juxt id identity) kids))
         (->> kids
           (remove #(= (:root project) (:root %))) ; in case "." in :dirs
           (map #(progeny % profiles)))))))

(defn interdependence
  "Turn a progeny map (symbols to projects) into a mapping of projects
  to their dependent projects"
  [pm]
  (let [deps (fn [p] (->> (:dependencies p)
                      (map first)
                      (map pm)
                      (remove nil?)))]
    (reduce (fn [acc [_ p]] (assoc acc p (deps p))) {} pm)))

(defn topological-sort [deps]
  "A topological sort of a mapping of graph nodes to their edges (credit Jon Harrop)"
  (loop [deps deps, resolved #{}, result []]
    (if (empty? deps)
      result
      (if-let [dep (some (fn [[k v]] (if (empty? (remove resolved v)) k)) deps)]
        (recur (dissoc deps dep) (conj resolved dep) (conj result dep))
        (throw (Exception. (apply str "Cyclic dependency: " (interpose ", " (map :name (keys deps))))))))))

(def ordered-builds
  "Sort a representation of interdependent projects topologically"
  (comp topological-sort interdependence progeny))

(def ^:private versions (atom {}))
(defn project-versions [] @versions)

(defn ensure-project-versions
  "Ordered builds, but retaining modules versioning in the process."
  [project]
  (when (and (empty? @versions) project (nil? (parent project)))
    (when-let [modules (children project true)]
      (reset! versions (reduce #(assoc % (id %2) (:version %2)) {} modules))))
  project)
