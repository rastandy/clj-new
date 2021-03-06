(ns clj-new.helpers
  "The top-level logic for the clj-new create/generate entry points."
  (:require [clojure.stacktrace :as stack]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :refer [clojure-env
                                                     read-deps]]
            ;; support boot-template projects:
            [boot.new.templates :as bnt]
            ;; needed for dynamic classloader/add-classpath stuff:
            [cemerick.pomegranate :as pom]
            ;; support clj-template projects:
            [clj.new.templates :as cnt]
            ;; support lein-template projects:
            [leiningen.new.templates :as lnt])
  (:import java.io.FileNotFoundException))

(def ^:dynamic *debug* nil)
(def ^:dynamic *use-snapshots?* false)
(def ^:dynamic *template-version* nil)

(defn resolve-and-load
  "Given a deps map and an extra-deps map, resolve the dependencies, figure
  out the classpath, and load everything into our (now dynamic) classloader."
  [deps resolve-args]
  (-> (deps/resolve-deps deps resolve-args)
      (deps/make-classpath (:paths deps) {})
      (str/split (re-pattern java.io.File/pathSeparator))
      (->> (run! pom/add-classpath))))

(def ^:private git-url-sha #"(https?://.*/([^/]+))@([a-fA-Z0-9]+)")

(def ^:private local-root  #"(.+)::(.+)")

(defn resolve-remote-template
  "Given a template name, attempt to resolve it as a clj template first, then
  as a Boot template, then as a Leiningen template. Return the type of template
  we found and the final, derived template-name."
  [template-name]
  (let [selected      (atom nil)
        failure       (atom nil)
        tmp-version   (cond *template-version* *template-version*
                            *use-snapshots?*   "(0.0.0,)"
                            :else              "RELEASE")
        [_ git-url git-tmp-name sha]  (re-find git-url-sha template-name)
        [_ local-root local-tmp-name] (re-find local-root  template-name)
        clj-only?     (or (and git-url git-tmp-name sha)
                          (and local-root local-tmp-name))
        template-name (cond (and git-url git-tmp-name sha)
                            git-tmp-name
                            (and local-root local-tmp-name)
                            local-tmp-name
                            :else
                            template-name)
        clj-tmp-name  (str template-name "/clj-template")
        clj-version   (cond (and git-url git-tmp-name sha)
                            {:git/url git-url :sha sha}
                            (and local-root local-tmp-name)
                            {:local/root local-root}
                            :else
                            {:mvn/version tmp-version})
        boot-tmp-name (str template-name "/boot-template")
        lein-tmp-name (str template-name "/lein-template")
        environment   (clojure-env)
        all-deps      (read-deps (:config-files environment))
        output
        (with-out-str
          (binding [*err* *out*]
            ;; need a modifiable classloader to load runtime dependencies:
            (.setContextClassLoader (Thread/currentThread)
                                    (clojure.lang.RT/makeClassLoader))
            (try
              (resolve-and-load
               all-deps
               {:verbose (and *debug* (> *debug* 1))
                :extra-deps
                {(symbol clj-tmp-name) clj-version}})

              (reset! selected [:clj template-name])
              (catch Exception e
                (when (and *debug* (> *debug* 2))
                  (println "Unable to find clj template:")
                  (stack/print-stack-trace e))
                (reset! failure e)
                (when-not clj-only?
                  (try
                    (resolve-and-load
                     all-deps
                     {:verbose (and *debug* (> *debug* 1))
                      :extra-deps
                      {(symbol boot-tmp-name) {:mvn/version tmp-version}}})

                    (reset! selected [:boot template-name])
                    (catch Exception e
                      (when (and *debug* (> *debug* 2))
                        (println "Unable to find Boot template:")
                        (stack/print-stack-trace e))
                      (reset! failure e)
                      (try
                        (resolve-and-load
                         all-deps
                         {:verbose (and *debug* (> *debug* 1))
                          :extra-deps
                          {(symbol lein-tmp-name) {:mvn/version tmp-version}
                           'leiningen-core {:mvn/version "2.7.1"}
                           'org.sonatype.aether/aether-api {:mvn/version "1.13.1"}
                           'org.sonatype.aether/aether-impl {:mvn/version "1.13.1"}
                           'slingshot {:mvn/version "0.10.3"}}})

                        (reset! selected [:leiningen template-name])
                        (catch Exception e
                          (when (and *debug* (> *debug* 1))
                            (println "Unable to find Leiningen template:")
                            (stack/print-stack-trace e))
                          (reset! failure e))))))))))]
    (when *debug*
      (println "Output from locating template:")
      (println output))
    (if @selected
      (let [sym-name (str (name (first @selected)) ".new." (second @selected))]
        (try
          (require (symbol sym-name))
          @selected
          (catch Exception e
            (when *debug*
              (println "Unable to require the template symbol:" sym-name)
              (stack/print-stack-trace e)
              (when (> *debug* 1)
                (stack/print-cause-trace e)))
            (throw (ex-info (format "Could not load template, require of %s failed with: %s"
                                    sym-name
                                    (.getMessage e)) {})))))
      (do
        (println output)
        (println "Failed with:" (.getMessage @failure))
        (throw (ex-info
                (format (str "Could not load artifact for template: %s\n"
                             "\tTried coordinates:\n"
                             "\t\t[%s \"%s\"]\n"
                             "\t\t[%s \"%s\"]")
                        template-name
                        boot-tmp-name tmp-version
                        lein-tmp-name tmp-version) {}))))))

(defn resolve-template
  "Given a template name, resolve it to a symbol (or exit if not possible)."
  [template-name]
  (if-let [[type template-name]
           (try (require (symbol (str "clj.new." template-name)))
                [:clj template-name]
                (catch FileNotFoundException _
                  (resolve-remote-template template-name)))]
    (let [the-ns (str (name type) ".new." template-name)]
      (if-let [sym (resolve (symbol the-ns template-name))]
        sym
        (throw (ex-info (format (str "Found template %s but could not "
                                     "resolve %s/%s within it.")
                                template-name
                                the-ns
                                template-name) {}))))
    (throw (ex-info (format "Could not find template %s on the classpath."
                            template-name) {}))))

(defn create*
  "Given a template name, a project name and list of template arguments,
  perform sanity checking on the project name and, if it's sane, then
  generate the project from the template."
  [template-name project-name args]
  (let [project-sym (try (read-string project-name) (catch Exception _))]
    (if (or (qualified-symbol? project-sym)
            (and (symbol? project-sym) (re-find #"\." (name project-sym))))
      (apply (resolve-template template-name) project-name args)
      (throw (ex-info "Project names must be valid qualified or multi-segment Clojure symbols."
                      {:project-name project-name})))))

(def ^:private create-cli
  "Command line argument spec for create command."
  [["-f" "--force"           "Force overwrite"]
   ["-h" "--help"            "Provide this help"]
   ["-o" "--output DIR"      "Directory prefix for project creation"]
   ["-S" "--snapshot"        "Look for -SNAPSHOT version of the template"]
   ["-v" "--verbose"         "Be verbose"]
   ["-V" "--version VERSION" "Use this version of the template"]])

(defn create
  "Exposed to clj-new command-line with simpler signature."
  [{:keys [args name template]}]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args create-cli)]
    (if (or (:help options) errors)
      (do
        (println "Usage:")
        (println summary)
        (doseq [err errors]
          (println err)))
      (let [{:keys [force snapshot version output verbose]} options]
        (binding [*debug*            verbose
                  *use-snapshots?*   snapshot
                  *template-version* version
                  bnt/*dir*          output
                  bnt/*force?*       force
                  cnt/*dir*          output
                  cnt/*force?*       force
                  lnt/*dir*          output
                  lnt/*force?*       force]
          (create* template name arguments))))))

(defn generate-code*
  "Given an optional template name, an optional path prefix, a list of
  things to generate (type, type=name), and an optional set of arguments
  for the generator, resolve the template (if provided), and then resolve
  and apply each specified generator."
  [template-name prefix generations args]
  (when template-name (resolve-template template-name))
  (doseq [thing generations]
    (let [[gen-type gen-arg] (str/split thing #"=")
          _ (try (require (symbol (str "clj.generate." gen-type))) (catch Exception _ (println _)))
          generator (resolve (symbol (str "clj.generate." gen-type) "generate"))]
      (if generator
        (apply generator prefix gen-arg args)
        (println (str "Unable to resolve clj.generate."
                      gen-type
                      "/generate -- ignoring: "
                      gen-type
                      (when gen-arg (str "=\"" gen-arg "\""))))))))

(def ^:private generate-cli
  "Command line argument spec for generate command."
  [["-f" "--force"           "Force overwrite"]
   ["-h" "--help"            "Provide this help"]
   ["-p" "--prefix DIR"      "Directory prefix for generation"]
   ["-t" "--template NAME"   "Override the template name"]
   ["-S" "--snapshot"        "Look for -SNAPSHOT version of the template"]
   ["-V" "--version VERSION" "Use this version of the template"]])

(defn generate-code
  "Exposed to clj new task with simpler signature."
  [{:keys [args generate]}]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args generate-cli)]
    (if (or (:help options) errors)
      (do
        (println "Usage:")
        (println summary)
        (doseq [err errors]
          (println err)))
      (let [{:keys [force prefix snapshot template version]} options]
        (binding [cnt/*dir*          "."
                  cnt/*force?*       force
                  *use-snapshots?*   snapshot
                  *template-version* version
                  cnt/*overwrite?*   false]
          (generate-code* template (or prefix "src") generate arguments))))))
