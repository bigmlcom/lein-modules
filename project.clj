(defproject bigml/lein-modules "0.4.1"
  :description "Extension to lein-modules to propagate project versions"
  :url "https://github.com/bigmlcom/lein-modules"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true
  :aliases {"all" ["do" "clean," "test," "install"]}
  :deploy-repositories {"releases" :clojars})
