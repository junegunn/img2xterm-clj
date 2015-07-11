(defproject img2xterm "0.1.0-SNAPSHOT"
  :description "img2xterm rewritten in clojure"
  :url "https://github.com/junegunn/img2xterm-clj"
  :license {:name "MIT"}
  :repositories [["apache-dev"
                  {:url
                   "https://repository.apache.org/content/repositories/snapshots/"
                   :snapshots true
                   :update :daily}]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [jline/jline "2.12"]
                 [org.apache.commons/commons-imaging "1.0-SNAPSHOT"]]
  :plugins [[lein-bin "0.3.5"]]
  :bin {:name "img2xterm" :bin-path "releases"}
  :main ^:skip-aot img2xterm.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
