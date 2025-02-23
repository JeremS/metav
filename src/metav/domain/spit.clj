(ns metav.domain.spit
  (:require
   [clojure.spec.alpha :as s]
   [clojure.data.json :as json]
   [me.raynes.fs :as fs]
   [cljstache.core :as cs]
   [metav.utils :as utils]
   [metav.domain.metadata :as metadata]
   [metav.domain.git :as git]))


;;----------------------------------------------------------------------------------------------------------------------
;; Spit conf
;;----------------------------------------------------------------------------------------------------------------------
(def defaults-options
  #:metav.spit{:output-dir "resources"
               :namespace  "meta"
               :formats    #{:edn}})

(s/def :metav.spit/output-dir ::utils/non-empty-str)
(s/def :metav.spit/namespace string?)
(s/def :metav.spit/formats (s/coll-of #{:clj :cljc :cljs :edn :json} :kind set?))
(s/def :metav.spit/template ::utils/resource-path)
(s/def :metav.spit/rendering-output ::utils/non-empty-str)

(s/def :metav.spit/options
  (s/keys :opt [:metav.spit/output-dir
                :metav.spit/namespace
                :metav.spit/formats
                :metav.spit/template
                :metav.spit/rendering-output]))

;;----------------------------------------------------------------------------------------------------------------------
;; Spit functionality
;;----------------------------------------------------------------------------------------------------------------------
(defn ensure-dir! [path]
  (let [parent (-> path fs/normalized fs/parent)]
    (fs/mkdirs parent)
    path))


(defn ensure-dest! [context]
  (ensure-dir! (::dest context)))


(defn side-effect-from-ctxt [f!]
  (fn [c]
    (f! c)
    c))


(defn add-extension [path ext]
  (let [path (fs/normalized path)]
    (fs/file (fs/parent path)
             (str (fs/name path) "." ext))))


(defn metafile! [output-dir namespace format]
  (fs/with-cwd output-dir
    (-> namespace
        (fs/ns-path)
        (fs/normalized)
        (add-extension (name format)))))


(defmulti spit-file! (fn [context] (::format context)))


(defmethod spit-file! :edn [context]
  (spit (::dest context)
        (pr-str (metadata/metadata-as-edn context))))


(defmethod spit-file! :json [context]
  (spit (::dest context)
        (json/write-str (metadata/metadata-as-edn context))))


(defmethod spit-file! :template [context]
  (spit (::dest context)
        (cs/render-resource (:metav.spit/template context)
                            (metadata/metadata-as-edn context))))


(defmethod spit-file! :default [context];default are cljs,clj and cljc
  (spit (::dest context)
        (metadata/metadata-as-code context)))


(defn standard-spits [context]
  (let [{:metav/keys [working-dir]
         :metav.spit/keys [formats output-dir namespace]} context
        output-dir (fs/file working-dir output-dir)]

    (utils/check (utils/ancestor? working-dir output-dir)
                 "Spitted files must be inside the repo.")

    (mapv (fn [format]
            (assoc context
              ::dest (metafile! output-dir namespace format)
              ::format format))
          formats)))

(defn add-template-spit [spits context]
  (let [{:metav/keys [working-dir]
         :metav.spit/keys [template rendering-output]} context]

    (if-not (and template rendering-output)
      spits
      (let [rendering-output (fs/with-cwd working-dir
                               (fs/normalized rendering-output))]

        (utils/check (utils/ancestor? working-dir rendering-output)
                     "Rendered file must be inside the repo.")

        (conj spits (assoc context
                      ::dest rendering-output
                      ::format :template))))))

(defn spit-files! [ctxts]
  (into []
        (comp
          (map (side-effect-from-ctxt ensure-dest!))
          (map (side-effect-from-ctxt spit-file!))
          (map ::dest)
          (map str))
        ctxts))


(s/def ::spit!param (s/merge :metav/context
                             :metav.spit/options))

(defn spit! [context]
  (let [context (utils/merge&validate context
                                      defaults-options
                                      ::spit!param)
        spits (-> context standard-spits (add-template-spit context))]
    (assoc context
           :metav.spit/spitted (spit-files! spits))))


(s/def ::git-add-spitted!-param (s/keys :req [:metav/working-dir
                                              :metav.spit/spitted]))


(defn git-add-spitted! [context]
  (let [{working-dir :metav/working-dir
         spitted :metav.spit/spitted} context]
    (-> context
        (->> (utils/check-spec ::git-add-spitted!-param))
        (assoc :metav.spit/add-spitted-result
               (apply git/add! working-dir spitted)))))