(ns leiningen.modules
  (:require [leiningen.core.project :as prj]
            [leiningen.core.main :as main]
            [leiningen.core.eval :as eval]
            [leiningen.core.utils :as utils]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:use [lein-modules.compression :as compression]
        [lein-modules.dependencies :as dependencies]))

(defn create-checkouts
  "Create checkout symlinks for interdependent projects"
  [projects]
  (doseq [[project deps] projects]
    (when-not (empty? deps)
      (let [dir (io/file (:root project) "checkouts")]
        (when-not (.exists dir)
          (.mkdir dir))
        (println "Checkouts for" (:name project))
        (binding [eval/*dir* dir]
          (doseq [dep deps]
            (eval/sh "rm" "-f" (:name dep))
            (eval/sh "ln" "-sv" (:root dep) (:name dep))))))))

(def checkout-dependencies
  "Setup checkouts/ for a project and its interdependent children"
  (comp create-checkouts dependencies/interdependence dependencies/progeny))

(defn cli-with-profiles
  "Set the profiles in the args unless some are already there"
  [profiles args]
  (if (some #{"with-profile" "with-profiles"} args)
    args
    (with-meta (concat
                 ["with-profile" (->> profiles
                                      (map name)
                                      (interpose ",")
                                      (apply str))]
                 args)
      {:profiles-added true})))

(defn dump-profiles
  [args]
  (if (-> args meta :profiles-added)
    (str "(" (second args) ")")
    ""))

(defn print-modules
  "If running in 'quiet' mode, only prints the located modules.

  Otherwise prints a more human-formatted modules list."

  [{:keys [quiet?]} modules]
  (if (empty? modules)
    (if-not quiet?
      (println "No modules found"))

    ;; There are modules
    (do (if-not quiet?
          (println " Module build order:"))
        (doseq [p modules]
          (if-not quiet?
            (println "  " (:name p) "/" (:version p))
            (println (:name p))))

        ;; For the test suite, return all children.
        (map dependencies/id modules))))

(defn modules
  "Run a task for all related projects in dependency order.

Any task (along with any arguments) will be run in this project and
then each of this project's child modules. For example:

  $ lein modules install
  $ lein modules deps :tree
  $ lein modules do clean, test
  $ lein modules analias

You can create 'checkout dependencies' for all interdependent modules
by including the :checkouts flag:

  $ lein modules :checkouts

And you can limit which modules run the task with the :dirs option:

  $ lein modules :dirs core,web install

Delimited by either comma or colon, this list of relative paths
will override the [:modules :dirs] config in project.clj

Accepts '-q', '--quiet' and ':quiet' to suppress non-subprocess output."
  [project & args]
  (let [[quiet? args] ((juxt some remove) #{"-q" "--quiet" ":quiet"} args)
        quiet? (or quiet? (-> project :modules :quiet))
        {:keys [quiet?] :as opts} {:quiet? (boolean quiet?)}]
    (condp = (first args)
    ":checkouts" (do
                   (checkout-dependencies project)
                   (apply modules project (remove #{":checkouts"} args)))
    ":dirs" (let [dirs (s/split (second args) #"[:,]")]
              (ensure-project-versions project)
              (apply modules
                     (-> project
                         (assoc-in [:modules :dirs] dirs)
                         (assoc-in [:modules :quiet] quiet?)
                         (vary-meta assoc-in [:without-profiles :modules :dirs] dirs))
                     (drop 2 args)))
    nil (print-modules opts (ordered-builds project))
    (let [modules (dependencies/ordered-builds project)
          profiles (compression/compressed-profiles project)
          args (cli-with-profiles profiles args)
          subprocess (get-in project [:modules :subprocess]
                       (or (System/getenv "LEIN_CMD")
                         (if (= :windows (utils/get-os)) "lein.bat" "lein")))]
      (when-not quiet?
        (print-modules opts modules))
      (doseq [project modules]
        (when-not quiet?
          (println "------------------------------------------------------------------------")
          (println " Building" (:name project) (:version project) (dump-profiles args))
          (println "------------------------------------------------------------------------"))
        (if-let [cmd (get-in project [:modules :subprocess] subprocess)]
          (binding [eval/*dir* (:root project)]
            (let [exit-code (apply eval/sh (cons cmd args))]
              (when (pos? exit-code)
                (throw (ex-info "Subprocess failed" {:exit-code exit-code})))))
          (let [project (prj/init-project project)
                task (main/lookup-alias (first args) project)]
            (main/apply-task task project (rest args)))))))))
