(ns qslice.core
  "A namespace for constructing \"query slices\" or \"qslices\".

   A qslice is a group of datomic query :where clauses with additional metadata
   related to vars. The point of this metadata is to track where a var's binding
   is expected to come from and whether any other slice can use the var.

   * require: Some other query slice must bind this var first.
   * provide: The clauses bind this var for later query slices later to :require.
   * let: Binding forms (as in datomic query :in, not clojure destructuring `let`)
     with their values (as in datomic query :args).
     The vars in the binding forms must appear in :where or :provide
     and may not appear in :require.

   Once a qslice is created via `qslice`, you may :let-bind any vars in the :where
   clause to values via `with-let`. Those bindings and values will be
   added to the :in and :args of the final compiled query.

   (As a convenience, you may also create and bind at the same time by adding
   a `:let` argument to `qslice`)

   (Additionally, you can `:must-let` vars for safety. This makes it an error
   to fail to :let these vars.)

   You can also add a cost score to qslices using `with-selectivity`
   to influence clause order.
   Lower values will appear earlier in the query.

   Once you have a collection of bound qslices,
   you can \"compile\" them to a datomic query using `compiled-query`.
   This function takes a collection of qslices and \"output\" forms
   (like :find, :with, :keys, etc) and returns a query+args map you can give
   to d/query.

   The real magic is that *vars that are not required or provided in a qslice
   will be automatically munged* in the where clauses that appear in the query
   so that the same var name will never appear for any other qslice in the set.
   This means you can write a bunch of qslices independently, with
   natural-looking :where clauses and possibly the same var names across slices,
   and combine them into a single query safely without unexpected unification.

   You still need to coordinate :provide and :require var contracts among
   qslices, but the qslice machinery will enforce those contracts come query
   time.

   The public functions of this namespace and their expected call sequence:

   `qslice`
   `with-name`
   `name`
   `with-selectivity`
   `with-let`
   `with-let*`
   `with-let-locked`
   `let-locked?`
   `compiled-query`
   `compiled-slice-order-names`

   These are convenience qslice constructors:

   `db-qslice`
   `rule-qslice`

   These are qslice \"combinators\": they accept qslices and return a different one.

   `or-qslice`"
  (:refer-clojure :rename {name clj-name})
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [qslice.walk :as walk]))

(defn- datalog-variable? [x]
  (and (simple-symbol? x)
       (= \? (first (clj-name x)))))

(defn- datalog-src-var? [x]
  (and (simple-symbol? x)
       (= \$ (first (clj-name x)))))

(defn- datalog-where-var? [x]
  (and (simple-symbol? x)
       (let [c (first (clj-name x))]
         (or (= \$ c) (= \? c)))))

(defn- datalog-rules-var? [x]
  (= '% x))

(def ^:private datalog-var-ish?
  (some-fn datalog-where-var? datalog-rules-var?))

(defn- clause-exposed-datalog-variables [top-level? clause]
  (if (seqable? (first clause))
    ;; [(myfn args...) binding...] or [(mypred args...)]
    (concat
     ;; function args
     (->> clause first rest (filter datalog-where-var?))
     ;; bindings
     (filter datalog-variable? (tree-seq seqable? seq (second clause))))
    (let [[ds clause] (if (datalog-src-var? (first clause))
                        ;; NOTE: if we're not top-level it's actually illegal
                        ;; for (first clause) to be anything other than $.
                        ;; But we'll let datomic enforce that...
                        [(if top-level?
                           (first clause)
                           '$)
                         (rest clause)]
                        ['$ clause])
          [c1 & cx] clause]
      (cond
        (= 'not-join c1)
        ;; TODO: this doesn't check if a datasource is actually needed
        ;; e.g. (not-join [?a] [(myfn) ?a] [(= ?a 1)])
        (cons ds (filter datalog-variable? (first cx)))

        (= 'or-join c1)
        ;; variables only in the second slot.
        ;; tree-seq because or-join apparently can be
        ;; (or-join [[?required-binding] ?other-binding] ...)
        ;; like rules.
        ;; TODO: this doesn't check if a datasource is actually needed
        (cons ds (filter datalog-variable? (tree-seq seqable? seq (first cx))))

        (= 'or c1)
        (cond->>
         (mapcat
          (fn [xs]
            (if (= 'and (first xs))
              (mapcat (partial clause-exposed-datalog-variables false) (rest xs))
              (clause-exposed-datalog-variables false xs)))
          cx)
          top-level? (replace {'$ ds}))

        (= 'not c1)
        (cond->>
         (mapcat (partial clause-exposed-datalog-variables false) cx)
          top-level? (replace {'$ ds}))

        ;; rule
        ;; Actually surprisingly ambiguous whether a form is a rule or
        ;; a data expression? Does datomic check `vector?`?
        ;; Note: we can't know if a rule needs a datasource. Sorry!
        (and (symbol? c1) (not (= '_ c1)) (not (datalog-where-var? c1)))
        (cons ds (cons '% (filter datalog-variable? clause)))

        :else                                               ;; pattern
        (cons ds (filter datalog-variable? clause))))))

(defn- where-form-exposed-datalog-variables [where-form]
  ;; TODO: find rule-name and qualified-symbol use,
  ;; and use to ensure rules or namespaces (on-prem-only) are required.
  (into []
        (comp
         (mapcat (partial clause-exposed-datalog-variables true))
         (distinct))
        where-form))

(defn- nth0 [xs] (nth xs 0))

(defn- qslice* [require-vars provide-vars must-let-vars where-forms]
  (let [where-vars-vec (where-form-exposed-datalog-variables where-forms)
        ;; Automatically :require any rule or datasource found in :where.
        ;; TODO: This decision is convenient for users and implementers,
        ;; but makes it impossible to shade a datasource, which would be nice.
        ;; Note that any relaxing of this requirement must address how shaded
        ;; vars are rewritten. Right now it does symbol replacement ignoring
        ;; or/not scopes. This is safe for vars, but not for datasources.
        ;; e.g. `($shaded not [($ rule ?a ?b])` would be scrambled.
        require-vars (into require-vars
                           (comp
                            (filter (some-fn datalog-src-var?
                                             datalog-rules-var?))
                            (remove provide-vars))
                           where-vars-vec)
        shaded-vars (into []
                          (comp
                           ;; This should already be true (see comment above);
                           ;; this is just extra paranoia to prevent datasource
                           ;; names from being shaded.
                           (filter datalog-variable?)
                           (remove require-vars)
                           (remove provide-vars))
                          where-vars-vec)
        letable-vars (set/difference
                      (-> #{}
                          (into provide-vars)
                          (into where-vars-vec))
                      require-vars)
        provide-variables (into #{} (filter datalog-variable?) provide-vars)
        ;; provided datasources and rules
        provide-specials (into #{} (remove provide-variables) provide-vars)
        require-variables (into #{} (filter datalog-variable?) require-vars)
        ;; required datasources and rules
        require-specials (into #{} (remove require-variables) require-vars)
        ;; datasources in :where that are not :require
        where-unrequired-ds (into #{}
                                  (comp
                                   (filter (some-fn datalog-src-var?
                                                    datalog-rules-var?))
                                   (remove require-specials))
                                  where-vars-vec)
        ;; A datasource or rule,
        ;; if :provide-ed or mentioned in :where but not :require-ed,
        ;; is automatically :must-let,
        ;; because the :where cannot possibly provide it.
        must-let-vars' (-> must-let-vars
                           (into provide-specials)
                           (into where-unrequired-ds))
        owned-vars (set/intersection must-let-vars' provide-vars)]
    {;; Variables that a *prior* slice must :provide.
     ::require-variables      require-variables
     ;; Datasources or rules that any other slice must :provide.
     ::require-specials       require-specials
     ;; Variables that this slice will bind via :where or :let
     ;; and other slices can :require.
     ::provide-variables      provide-variables
     ;; Datasources or rules that this slice will bind via :let,
     ;; and other slices can :require.
     ::provide-specials       provide-specials
     ;; Vars found in the where-forms
     ::where-vars             (set where-vars-vec)
     ;; Vars that *can* be bound by :let.
     ;; Is any where-vars or provide-vars, but not any require-vars
     ::letable-vars           letable-vars
     ;; Vars that *must* be bound by :let before compiling to a query.
     ;; These must be a subset of ::letable-vars
     ::must-let-vars          must-let-vars'
     ;; Vars which the current slice provides *and* must-let.
     ;; No other slice can bind these vars, because that would lead to the
     ;; same unmunged symbol appearing in :in multiple times as multiple args.
     ;; This isn't *impossible* per-se (datomic will unify (but not datasources))
     ;; but is usually a sign of a bug in your query slice composition.
     ::owned-vars             owned-vars
     ;; Vars which will be "shaded" (munged to be unique) in a compiled query.
     ;; This is any where-form-vars that aren't in require-vars or provide-vars.
     ;; This is a *vector* of distinct items
     ;; and the order is the order the var was first seen in the
     ;; ::where-forms via pre-order traversal.
     ;; The vector indexes are used as stable unique ids for munging.
     ::shaded-vars            shaded-vars
     ;; The unchanged input where clauses (a vector).
     ;; In a compiled query, ::shaded-vars in these forms will be rewritten
     ;; so they don't conflict (like gensym-ing in a macro).
     ::where-forms            where-forms
     ;; A vector of pairs of binding forms (:in clause) to values (:args)
     ;; Starts empty; add bindings via `with-let`.
     ;; Is not a map because binding the same form multiple times is legal.
     ::let-pairs              []
     ;; The vars (not the forms!) found in the binding forms in ::let-map
     ::let-vars               #{}
     ;; This is flag to avoid checking ::must-let-vars twice: once at when-let,
     ;; and again at compilation time.
     ;; If we have no ::must-let vars, we can start true.
     ::must-let-vars-checked? (= #{} must-let-vars')
     ;; Used to influence where-clause sort order.
     ;; Slices in the final compiled query are sorted by selectivity.
     ;; Smaller selectivity values sort before higher ones.
     ::selectivity            0
     ;; Optional slice name.
     ;; Set with `with-name`; retrieve with `name`.
     ::name                   ""}))

(defmacro ^:private throw-error [errk msg m]
  (with-meta
    `(let [msg# ~msg]
       (throw
        (ex-info
         msg#
         (assoc ~m
                :cognitect.anomalies/category :cognitect.anomalies/incorrect
                :cognitect.anomalies/message msg#
                ::error ~errk))))
    (meta &form)))

(defn- assert-must-let-satisfied* [{::keys [must-let-vars let-vars] :as qs}]
  (if-some [unsatisfied-vars (not-empty (set/difference must-let-vars let-vars))]
    (throw-error
     :unsatisfied-must-let
     "qslice required a var via :must-let but didn't get it"
     {:unsatisfied-vars             unsatisfied-vars
      :must-let-vars                must-let-vars
      :let-vars                     let-vars
      :binding-forms                (mapv nth0 (::let-pairs qs))
      :slice                        qs})
    (assoc qs ::must-let-vars-checked? true)))

(defn- assert-must-let-satisfied [qs]
  (cond-> qs
    (not (::must-let-vars-checked? qs)) (assert-must-let-satisfied*)))

(defn- binding-syms [binding-form]
  (filter datalog-var-ish? (tree-seq seqable? seq binding-form)))

(defn- find-syms [find-form]
  ;; Note: find forms *allow* datasources, but we don't shade them right now
  ;; nor can you *only* have datasources, hence this filter.
  (filter datalog-variable? (tree-seq seqable? seq find-form)))

(defn with-let-locked
  "Asserts that all must-lets are satisfied and returns a qslice which cannot be
  with-let again.

  This is a utility function for combinators whose behavior would be undefined
  if the qslice was rebound."
  [qs]
  (-> (assert-must-let-satisfied qs)
      (assoc ::let-locked? true)))

(defn let-locked?
  "Returns whether the current qslice was locked via `with-let-locked`."
  [qs]
  (::let-locked? qs false))

(defn with-let*
  "Binds values to the qslice. Accepts a seqable of pairs of argument and value.

  `with-let*` calls do not accumulate: each call completely replaces the
  previous :let state of the qslice."
  [qs binding-form->value]
  (when (let-locked? qs)
    (throw-error
     :qslice-let-locked
     "qslice is locked: it cannot be with-let again."
     {}))
  (let [{::keys [letable-vars]} qs
        let-pairs (into [] (map vec) binding-form->value)
        let-vars (into #{}
                       (comp (map nth0) (mapcat binding-syms))
                       let-pairs)]
    (when-some [unknown-vars (not-empty (set/difference let-vars letable-vars))]
      (throw-error
       :unknown-let-binding-var
       "Unknown var in let binding form"
       {:unknown-vars                 unknown-vars
        :letable-vars                 letable-vars
        :let-pairs                    let-pairs}))
    (-> (assoc qs ::let-pairs let-pairs
               ::let-vars let-vars)
        (assert-must-let-satisfied*))))

(defn with-let
  "Bind or re-bind values to the qslice.

  Arguments after the qslice are alternating binding-forms and values, e.g.:

  (with-let the-qslice '?a 1 '[?b ...] 2)

  Or a single argument of pairs of binding-forms and value, which will be
  delegated to `with-let*`. e.g.:

  (with-let the-qslice {'?a 1 '[?b ...] 2})
  (with-let the-qslice [['?a 1] ['[?b ...] 2]])

  `with-let` calls do not accumulate: each call completely replaces the
  previous :let state of the qslice."
  [qs & binding-form->value]
  (let [c (count binding-form->value)
        let-pairs
        (cond
          (and (== 1 c) (seqable? (first binding-form->value))) (first binding-form->value)
          (even? c) (sequence (partition-all 2) binding-form->value)
          :else (throw-error
                 :invalid-with-let-arg
                 (str "with-let binding-form->value must be a single argument of pairs"
                      " (e.g. seq of entries) or an even number of arguments.")
                 {:binding-form->value binding-form->value}))]
    (with-let* qs let-pairs)))

(defn with-name
  "Set a name for this qslice.
  This is for informational purposes only and does not affect compiled queries."
  [qs name]
  (assoc qs ::name name))

(def name
  "Retrieve the name of a qslice previously set via with-name.
  The default name of a qslice is the empty string."
  ::name)

(defn with-selectivity [qs selectivity]
  {:pre [(int? selectivity)]}
  (assoc qs ::selectivity selectivity))

(defn qslice
  "Construct and return a query slice.

  Accepts the following arguments as a single map or variadic arguments:

  * `:where` A collection of datomic `:where` clauses, as in a datomic query.
  * `:let` A collection of pairs of :in binding-forms with their :args value.
    You can also provide this after construction using `when-let` or `when-let*`.
  * `:name` An optional name for this slice, default \"\".
    You can also provide this after construction using `with-name`.
  * `:provide` A collection of datomic vars ($datasources, %, or ?variables)
    that this slice will provide via :let, `with-let`, or in its :where.
    These variables can be used by other slices.
  * `:require` A collection of datomic vars that this slice uses but another
    slice must :provide.
    It automatically includes any datasources or % (if a rule invocation is seen)
    found in :where that is not in :provide.
  * `:must-let` A collection of datomic vars which *must* be `:let` or
    `with-let` by *this* qslice before constructing a datomic query.
    Note that datasources or rules in :provide and datasources in :where
    but not in :require are automatically added to :must-let because there is
    no other way to provide them.
  * :selectivity An optional long, default 0, which influences the order
    of this qslice's :where clauses among other qslices in a compiled query.
    Lower values appear earlier in the query.
    This is useful to encourage faster or more selective clauses to appear
    earlier or slower clauses to appear later in the query.

  Note that this constructor is moderately costly because it performs
  form-walking and invariant checks.
  Therefore, if the qslice will be reused only with different :name, :let, or
  :selectivity values, it is better to construct the base qslice once and modify
  it with `with-name`, `with-let`, `with-let*`, or `with-selectivity`
  repeatedly."
  [& {:keys [require provide must-let where name selectivity] let-param :let}]
  {:pre [(every? datalog-var-ish? require)
         (every? datalog-var-ish? must-let)
         (every? datalog-var-ish? provide)]}
  (let [{:as base ::keys [require-variables require-specials
                          provide-variables provide-specials
                          letable-vars must-let-vars]}
        (qslice* (set require) (set provide) (set must-let) (vec where))]
    (let [require-vars (into require-variables require-specials)
          provide-vars (into provide-variables provide-specials)]
      (when-some [conflicting-vars (not-empty
                                    (set/intersection require-vars provide-vars))]
        (throw-error
         :required-and-provided-var
         "Cannot require and provide the same var"
         {:require-vars         require-vars
          :provide-vars         provide-vars
          :require+provide-vars conflicting-vars}))
      (when-some [unreqirable-vars (not-empty (set/intersection
                                               must-let-vars require-vars))]
        (throw-error
         :required-and-must-let-var
         "Cannot :require vars that are :must-let. (let-able vars are those in :provide or :where.)"
         {:unreqirable-vars unreqirable-vars
          :require-vars require-vars
          :must-let-vars must-let-vars})))
    (when-some [unlet-able-vars (not-empty (set/difference
                                            must-let-vars letable-vars))]
      (throw-error
       :unlet-able-vars
       "Cannot must-let variables that are not let-able. (let-able vars are those in :provide or :where.)"
       {:unlet-able-vars unlet-able-vars
        :must-let-vars   must-let-vars
        :letable-vars    letable-vars}))
    (cond-> base
      name (with-name name)
      selectivity (with-selectivity selectivity)
      let-param (with-let* let-param))))

(defn- compiled-sym [slice-idx idx sym]
  (symbol nil (str (clj-name sym) ":" idx ":" slice-idx)))

(defn- shaded-var-replacements [qs slice-idx]
  (persistent!
   (reduce-kv
    (fn [m idx sym]
      (assoc! m sym (compiled-sym slice-idx idx sym)))
    (transient {})
    (::shaded-vars qs))))

(defn- shaded-slice [qs]
  (let [{::keys [let-pairs where-forms name selectivity slice-idx]} qs
        replaces (shaded-var-replacements qs slice-idx)
        shaded-let-pairs  (into []
                                (map (fn [[form value]]
                                       [(walk/postwalk-replace replaces form)
                                        value]))
                                let-pairs)
        where-forms-shaded (walk/postwalk-replace replaces where-forms)]
    {::shaded-replaces   replaces
     ::shaded-where      where-forms-shaded
     ::shaded-let-pairs  shaded-let-pairs
     ::slice-order-entry {:slice-idx   slice-idx
                          :name        name
                          :selectivity selectivity}}))

(def ^:private slice-sort (juxt ::selectivity ::slice-idx))

(defn- sorted-qslices [bound-qslices]
  ;; TODO: topo sorting by require/provide, then by selectivity, then by slice-idx
  (sort-by slice-sort bound-qslices))

(defn- binding-form-sort-key-fn [x]
  ;; collation order:
  ;; $* lexically
  ;; %* lexically
  ;; pattern-names lexically (i.e. plain symbols for pull expression values)
  ;; ?* lexically (scalars)
  ;; [?x ...] (collections) lexically by first symbol
  ;; [[?x]] (relations) as by `compare`
  ;; [?x] (tuples) as by `compare`
  ;; This tier-ing is needed because:
  ;; 1. compare can't compare different types safely (i.e. vector and symbol)
  ;; 2. The desired sort order of symbols departs from lexical order.
  (let [tier (cond
               (simple-symbol? x)
               (let [c1 (.charAt (clj-name x) 0)]
                 (cond
                   (.equals \$ c1) 0
                   (.equals \% c1) 1
                   (.equals \? c1) 3
                   :else 2))                                ;; pattern-name

               (vector? x)
               (let [lastitem (peek x)]
                 (cond
                   ;; nil is [], which is GIGO
                   (.equals '... lastitem) 4                ;; [?x ...]
                   (vector? lastitem) 6                     ;; [[?x]]
                   :else 5))                                ;; [?x]
               :else -1)]                                   ;; garbage
    [tier x]))

(defn- compiled-query* [sqs output-clauses]
  (let [shaded-slices (mapv shaded-slice sqs)
        shaded-let-pairs (sort-by (comp binding-form-sort-key-fn nth0)
                                  (mapcat ::shaded-let-pairs shaded-slices))
        in (into [] (map nth0) shaded-let-pairs)
        args (into [] (map peek) shaded-let-pairs)
        where (into [] (mapcat ::shaded-where) shaded-slices)
        slice-order (mapv ::slice-order-entry shaded-slices)]
    {:query (assoc output-clauses :in in :where where)
     :args args
     :slice-order slice-order}))

(defn- with-slice-idx [i qs] (assoc qs ::slice-idx i))

(defn compiled-query
  "Compile a collection of qslices into a datomic query-map suitable for
  d/qseq or d/query (not datomic.api/q).

  At least one qslice and a find-clause must be provided.
  The qslices must all have :let bindings that satisfy their own :must-let,
  and must satisfy each-other's :provide and :require.

  find-clause can be a simple :find vector, or it can be a map with keys
  :find :with :keys :syms :strs with datomic's meaning.
  Vars in :find and :with must be :provide-ed by the qslices.
  (Note that :in and :where are ignored.)

  Returns a query map with :query, :args, and :slice-order set.

  :slice-order is a vector of maps, one per slice, in the order that the
  slices were compiled into the query. Each map has:

  * :slice-idx   The index of the slice in the input `qslices` collection.
  * :name        The slice's name.
  * :selectivity The slice's selectivity value."
  [qslices find-clause]
  {:pre [(< 0 (count qslices))]}
  (let [sqs (sorted-qslices
             (into []
                   (comp
                    (map-indexed with-slice-idx)
                    ;; INVARIANT: ::must-lets are satisfied.
                    (map assert-must-let-satisfied))
                   qslices))
        output-clauses (if (map? find-clause)
                         (select-keys find-clause [:find :with :keys :syms :strs])
                         {:find find-clause})
        ;; TODO: pull expression pattern-name support
        find-vars (set (find-syms (:find output-clauses)))
        with-vars (into #{} (filter datalog-variable?) (:with output-clauses))
        find+with-vars (into find-vars with-vars)]
    ;; INVARIANT: need to find *something*
    (when (== 0 (count find-vars))
      (throw-error
       :missing-find
       "find clause must have at least one variable and no rule symbols (%)"
       {:find-clause (:find output-clauses)}))
    (let [{:keys [unsatisfied-require-variables
                  unsatisfied-slices]}
          (reduce
           (fn [{:as acc :keys [prior-provide-variables]}
                {::keys [provide-variables require-variables] :as slice}]
             (let [acc (if-some [unsatisfied
                                 (not-empty
                                  (set/difference
                                   require-variables prior-provide-variables))]
                         (-> acc
                             (update :unsatisfied-require-variables into unsatisfied)
                             (update :unsatisfied-slices conj slice))
                         acc)]
               (update acc :prior-provide-variables into provide-variables)))
           {:prior-provide-variables       #{}
            :unsatisfied-require-variables #{}
            :unsatisfied-slices            []}
           sqs)]
      ;; INVARIANT: all required variables are provided by a prior slice
      ;; Note: specifically *variables* (e.g. ?x), not datasources or rules,
      ;; which cannot be provided by :where and so are order-independent.
      (when (< 0 (count unsatisfied-require-variables))
        (throw-error
         :unsatisfied-require-variables
         "Some required variables are not satisfied by the qslice selectivity order"
         {:unsatisfied-require-variables unsatisfied-require-variables
          :slices                        sqs
          :unsatisfied-slices            unsatisfied-slices})))

    ;; INVARIANT: all required datasource or rule "special" vars are provided
    ;; by any slice.
    (let [all-require-specials (into #{} (mapcat ::require-specials) sqs)
          all-provide-specials (into #{} (mapcat ::provide-specials) sqs)]
      (when-some [unprovided-special-vars (not-empty
                                           (set/difference
                                            all-require-specials
                                            all-provide-specials))]
        (throw-error
         :unprovided-special-vars
         "Some slices require a datasource or % that is not provided by another slice."
         {:unprovided-special-vars unprovided-special-vars
          :slices                  sqs}))

      ;; INVARIANT: all vars in find-clause are provided by a slice
      (when-some [unprovided-find+with-vars
                  (not-empty
                   (set/difference find+with-vars
                                   (into all-provide-specials
                                         (mapcat ::provide-variables)
                                         sqs)))]
        (throw-error
         :unprovided-find+with-var
         "Some :find or :with vars are not provided"
         {:unprovided-find+with-vars unprovided-find+with-vars
          :find-vars                 find-vars
          :find-clause               (:find output-clauses)
          :with-vars                 with-vars
          :with-clause               (:with output-clauses)})))
    ;; INVARIANT: qslices provides at least one var
    ;; If the previous invariants hold, then this invariant follows,
    ;; so we don't need to check it separately.

    ;; INVARIANT: all owned vars are only owned by one slice
    (let [disputed-vars (->> (transduce
                              (mapcat #(map vector (::owned-vars %) (repeat %)))
                              (completing
                               (fn [r [owned-var slice]]
                                 (update r owned-var (fnil conj []) slice)))
                              {}
                              sqs)
                             (filter (fn [[_owned-var slice]]
                                       (> (count slice) 1))))]
      (when (seq disputed-vars)
        (throw-error
         :multiply-owned-var
         "Some vars are owned by multiple slices"
         {:multiply-owned-vars (vec (keys disputed-vars))
          :var->slices         (into {} disputed-vars)})))
    (compiled-query* sqs output-clauses)))

;; Convenience qslices

(def ^:private db-qslice*
  (qslice :name '$
          :require []
          :provide '[$]
          :must-let '[$]))

(defn db-qslice
  "Return a qslice that provides a datasource (e.g. $).
  If called with one argument, $ will be bound to that value.
  If called with two arguments, the first argument is the datasource binding
  symbol and the second the value (e.g. `(db-qslice $h (d/history db))`)."
  ([] db-qslice*)
  ([db] (with-let* db-qslice* [['$ db]]))
  ([db-sym db]
   {:pre [(datalog-src-var? db-sym)]}
   (qslice :name db-sym
           :provide [db-sym]
           :must-let [db-sym]
           :let [[db-sym db]])))


(def ^:private rule-qslice* (qslice :name '% :provide '[%] :must-let '[%]))

(defn rule-qslice
  "Return a qslice that provides rules (%).
  If called with an argument, % will be bound to that value."
  ([] rule-qslice*)
  ([rules] (with-let* rule-qslice* [['% rules]])))

(defn compiled-slice-order-names
  "Convenience functions which prints the slice names in :slice-order
  of a compiled query. Omits names $, %, nil, and empty-string.
  This is for producing a small, loggable summary of the order of slices
  in the final query."
  [{:as _compiled-query :keys [slice-order]}]
  (into []
        (comp
         (map :name)
         (remove (some-fn nil? #{"" '$ '%})))
        slice-order))

(defn or-qslice
  "Returns a qslice which behaves as if all the supplied qslices were or-joined
  together.

  Let bindings will be combined, possibly with shading to avoid collisions.
  The returned qslice will be let-locked.
  Name will be combined as \"or(qslice1 qslice2 ...)\".
  Selectivity will be the minimum found among all input qslices.

  There are limitations to what qslices can be combined. All the following
  conditions must hold or this function will throw:

  * All qslices must provide the exact same vars.
  * At most one datasource may be required by each qslice.
    This is a datomic restriction: rules may only access one datasource.
  * All required datasources must be named $.
    This is for implementation convenience and could be lifted in the future.
  * All qslices must-let bindings must be satisfied.
    This is because you cannot know what the name of the var will be after shading.
  * No qslices may share owned vars (i.e. vars both provided and must-let).
    This is for ease of implementation and because the correct semantics for
    combining their let-bindings is not yet clear."
  [qslices]
  (when-not (apply = (map ::provide-variables qslices))
    (throw-error
     :same-vars-not-provided
     "Cannot OR together qslices which provide different vars."
     {:unshared-vars (set/difference
                      (apply set/intersection
                             (map ::provide-variables qslices))
                      (apply set/union
                             (map ::provide-variables qslices)))}))
  (let [required-datasources (into #{}
                                   (comp
                                    (mapcat ::require-specials)
                                    (filter datalog-src-var?))
                                   qslices)]
    (when-not (or (= required-datasources #{})
                  (= required-datasources #{'$}))
      (throw-error
       :not-allowed-datasource
       "Cannot OR together qslices which require datasources other than $"
       {:found-required-datasources required-datasources})))

  (let [selectivity (apply min (map ::selectivity qslices))
        combined-name (str "or(" (str/join " " (map ::name qslices)) ")")
        shaded (into []
                     (comp
                      (map-indexed with-slice-idx)
                      ;; INVARIANT: ::must-lets are satisfied.
                      (map assert-must-let-satisfied)
                      (map #(into % (shaded-slice %))))
                     qslices)
        require-vars (into #{} (mapcat ::require-variables) shaded)
        require (into require-vars (mapcat ::require-specials) shaded)
        provide-vars (into #{} (mapcat ::provide-variables) shaded)
        provide (into provide-vars (mapcat ::provide-specials) shaded)
        must-let (into #{}
                       (mapcat (fn [{::keys [must-let-vars shaded-replaces]}]
                                 (eduction
                                  (replace shaded-replaces must-let-vars))))
                       shaded)
        actually-let (into #{}
                           (mapcat (fn [{::keys [let-vars shaded-replaces]}]
                                     (eduction
                                      (replace shaded-replaces let-vars))))
                           shaded)
        let-param (into []
                        (mapcat ::shaded-let-pairs)
                        shaded)
        require-bindings (sort-by binding-form-sort-key-fn
                                  (into require-vars actually-let))
        provide-bindings (sort-by binding-form-sort-key-fn provide-vars)
        or-join-bindings (if (== 0 (count require-bindings))
                           (vec provide-bindings)
                           (into [(vec require-bindings)] provide-bindings))
        where [`(~'or-join ~or-join-bindings
                           ~@(into []
                                   (keep
                                    (fn [{::keys [shaded-where]}]
                                      (case (count shaded-where)
                                        0 nil
                                        1 (first shaded-where)
                                        `(~'and ~@shaded-where))))
                                   shaded))]]
    (with-let-locked
      (qslice
       :require require
       :provide provide
       :must-let must-let
       :where where
       :name combined-name
       :selectivity selectivity
       :let let-param))))
