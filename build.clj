(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deps-deploy]))

(def lib 'jhancock/aimee)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(defn- clean
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Build the library jar. Excludes dev/simulator namespaces from the artifact."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir
               ;; Keep the library lean; we can expand this list as the repo evolves.
               :exclude [#"aimee/example.*"]})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :pom-data [[:description "Core.async-first streaming client for LLM APIs"]
                           [:url "https://github.com/jhancock/aimee"]
                           [:licenses [:license
                                       [:name "MIT"]
                                       [:url "https://opensource.org/licenses/MIT"]]]
                           [:scm
                            [:url "https://github.com/jhancock/aimee"]
                            [:connection "scm:git:git://github.com/jhancock/aimee.git"]
                            [:developerConnection "scm:git:ssh://git@github.com/jhancock/aimee.git"]]]})
  (b/jar {:class-dir class-dir
          :jar-file (format "target/%s-%s.jar" (name lib) version)}))

(defn deploy
  "Build and deploy the jar to Clojars. Requires credentials in ~/.clojars or env vars."
  [_]
  (jar nil)
  (deps-deploy/deploy {:installer :remote
                       :artifact (b/resolve-path (format "target/%s-%s.jar" (name lib) version))
                       :pom-file (b/resolve-path (str class-dir "/META-INF/maven/"
                                                      (namespace lib) "/" (name lib) "/pom.xml"))}))
