(ns qslice.walk
  (:import
   (clojure.lang
    IMapEntry
    IObj
    IRecord
    MapEntry)))

;; Copied and modified from potemkin,
;; v0.4.5 (https://github.com/ztellman/potemkin), MIT licensed, Copyright
;; Zachary Tellman

;; adapted from clojure.walk, but preserves metadata

(defn walk
  "Like `clojure.walk/walk`, but preserves metadata.
  Not needed for clojure >= 1.12, which fixes this bug."
  [inner outer form]
  (let [x (cond
            (list? form) (outer (apply list (map inner form)))
            (instance? IMapEntry form)
            (outer (MapEntry/create (inner (key form)) (inner (val form))))
            (seq? form) (outer (doall (map inner form)))
            (instance? IRecord form)
            (outer (reduce (fn [r x] (conj r (inner x))) form form))
            (coll? form) (outer (into (empty form) (map inner) form))
            :else (outer form))]
    (if (instance? IObj x)
      (with-meta x (merge (meta form) (meta x)))
      x)))

(defn postwalk
  "Like `clojure.walk/postwalk`, but preserves metadata."
  [f form]
  (walk (partial postwalk f) f form))

(defn postwalk-replace [smap form]
  (postwalk (fn [x] (if-some [[_ r] (find smap x)] r x)) form))
