(ns metav.spit
  (:require [metav.display :refer [version module-name tag]]
            [metav.git :as git]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojure.java.io :refer [file]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.data.json :as json]
            [me.raynes.fs :as fs])
  (:import [java.util Date TimeZone]
           [java.text DateFormat SimpleDateFormat]))

(defn iso-now []
  (let [tz (TimeZone/getTimeZone "UTC")
        df (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'")]
    (.setTimeZone df tz)
    (.format df (Date.))))

(defn metadata-as-edn
  "return a map of the repo metadata: version, name, path, etc."
  [working-dir version]
  (let [tag (tag working-dir version)]
    {:module-name (module-name working-dir)
     :tag tag
     :version (str version)
     :generated-at (iso-now);(git/tag-timestamp working-dir (git/last-sha working-dir))
     :path (if-let [prefix (git/prefix working-dir)] prefix ".")}))

(defn metadata-as-code
  [working-dir ns version]
  (let [{:keys [sha module-name path version tag generated-at]} (metadata-as-edn working-dir)]
    (string/join "\n" [";; This code was automatically generated by the 'metav' library."
                       (str "(ns " ns ")") ""
                                        ;                       (format "(def sha \"%s\")" sha)
                       (format "(def module-name \"%s\")" module-name)
                       (format "(def path \"%s\")" path)
                       (format "(def version \"%s\")" version)
                       (format "(def tag \"%s\")" tag)
                       (format "(def generated-at \"%s\")" generated-at)
                       ""])))

(def accepted-formats #{"clj" "cljs" "cljc" "edn" "json"})

(defn parse-formats
  "parse a string of comma-separated formats a return a set of formats"
  [s]
  (when s (set (string/split s #","))))

(def default-options {:output-dir "resources" :namespace "meta" :formats "edn" :verbose false})

(def cli-options
  [["-o" "--output-dir DIR_PATH" "Output Directory"
    :default (:output-dir default-options)
    :default-desc "resources"
    :parse-fn str]
   ["-n" "--namespace NS" "Namespace used in code output"
    :default (:namespace default-options)]
   ["-f" "--formats FORMATS" "Comma-separated list of output formats (clj, cljc, cljs, edn, json)"
    :default (:formats default-options) 
    :validate [#(empty? (set/difference (parse-formats %) accepted-formats)) "Formats must be in the following list: clj, cljc, cljs, edn, json"]]
   ["-v" "--verbose" "Verbose, output the metadata as json in stdout if the option is present"
    :default (:verbose default-options)]
   ["-h" "--help" "Help"]])

(defn usage [summary]
  (->> ["The spit function of Metav output module's metadata in different files: clj, cljc, cljs, edn or json."
        "The metadata is composed of: module-name, tag, version, path, timestamp "
        ""
        "Usage: metav.spit [options]"
        ""
        "Options:"
        summary
        ""]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary] :as opts} (parse-opts args cli-options)]
    ;(prn opts)
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; custom validation on arguments
      (> (count options) 1)
      {:options options}

      (and (= 0 (count options)) (= 0 (count arguments)))
      {:exit-message (usage summary) :ok? false}

      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary) :ok? false})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn metafile [output-dir namespace format]
  (fs/with-cwd output-dir
    (let [ns-file (fs/ns-path namespace)
          parent (fs/parent ns-file)
          name (fs/name ns-file)
          _ (fs/extension ns-file)]
      (fs/mkdirs parent)
      (file parent (str name "." format)))))

(defmulti spit-file :format)

(defmethod spit-file "edn" [version {:keys [working-dir output-dir namespace format]}]
  (spit (metafile (str working-dir "/" output-dir) namespace format)
        (pr-str (metadata-as-edn working-dir version))))

(defmethod spit-file "json" [version {:keys [working-dir output-dir namespace format]}]
  (spit (metafile (str working-dir "/" output-dir) namespace format)
        (json/write-str (metadata-as-edn working-dir version))))

(defmethod spit-file :default [version {:keys [working-dir output-dir namespace format]}];default are cljs,clj and cljc
  (spit (metafile (str working-dir "/" output-dir) namespace format)
        (metadata-as-code working-dir namespace version)))

(defn spit-files
  ([working-dir options] (spit-files working-dir (version working-dir) options));CLI invocation
  ([working-dir version {:keys [namespace formats] :as options}];invocation from release, the next version is given as arguments
   (doseq [format (parse-formats formats)]
     (spit-file version (merge options {:format format :working-dir working-dir})))))

(defn -main
  ""
  [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (when exit-message
      (exit (if ok? 0 1) exit-message))
    (spit-files (str (git/pwd)) options);spit files invoked from CLI deduce the current version from git state
    (shutdown-agents)))
