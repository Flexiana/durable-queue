(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b] 
            [clojure.edn :as edn]))

(def project (-> (edn/read-string (slurp "deps.edn"))
                 :aliases :neil :project))
(def lib 'flexiana.durable-queue)
(def version (or (:version project) "0.1.0-SNAPSHOT"))


(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/pod-%s.jar" (name lib)))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar {})
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :main 'pod.flexiana.durable-queue
           :basis basis}))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
    (merge {:installer :local
            :artifact jar-file
            :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
           opts))
  opts)
