# A Qslice Tutorial

This is a narrative introduction to using the qslice library on a realistic 
problem where you have to translate a simple application-defined query 
language data structure to a Datomic-flavored datalog query.

This is meant as a companion to, but not a replacement for,
the API Reference found in the `qslice.core` namespace docstring
and the public function docstrings.

If you are reading this in GitHub, please avail yourself of the
[automatic table of contents](https://github.blog/changelog/2021-04-13-table-of-contents-support-in-markdown-files/)
to ease navigation through this tutorial.

## A Simple Application-Defined Query Language

Suppose you have a simple application
which searches a music database for tracks (songs).
(Perhaps you are searching the sample Datomic [MusicBrainz database].)

[MusicBrainz database]: https://github.com/Datomic/mbrainz-sample

You don't want your users to need to write datalog,
so you create an application-specific query language.

At first your query language's capabilities are simple:
you can search by fulltext track name or artist;
if you include both, the track must match both.

### Translating to Datalog

You implement this the typical way
one would construct a Datomic query dynamically.
You target the Datomic query map form
and use `cond->` to add `:in`, `:where` and `:args` clauses
to the query conditionally when a query term is present.
Your clauses have a fixed order
you think will have good performance most of the time.
You even extract your query operators into rules
so that they are nearly one-to-one with your query components.

It looks something like this:

```clojure
;; user query
{:track "broadway" :artist "genesis"}

;; code to generate datomic query
{:query
 {:find '[?t]
  :in   (cond-> '[$ %]
                track (conj '?track-name)
                artist (conj ?artist))
  :where (cond-> '[]
                 track (conj '[(track-name ?t ?track-name)])
                 artist (conj '[(track-artist-name ?t ?artist-name)]))}
 :args
 (cond-> [db rules]
         track (conj track)
         artist (conj artist))}
```

Over time your query language evolves and grows more search parameters.
Your `cond->` forms are getting long, but it's still manageable.
You refactor it to keep `:in`,`:where`, and `:args` terms together:

```clojure
(let [terms (cond-> []
                    track (conj {:in    ['?track-name]
                                 :where '[(track-name ?t ?track-name)]
                                 :args  [track]})
                    artist (conj {:in    ['?artist-name]
                                  :where '[(artist-name ?t ?artist-name)]
                                  :args  [artist]})
                    ;; many more things ...
                    )]
  {:query
   {:find  '[?t]
    :in    (into '[$ %] (mapcat :in) terms)
    :where (into [] (mapcat :where) terms)}
   :args
   (into [db rules] (mapcat :args) terms)})
```

### Adding "Every" Matches

One day someone asks for an "every" term,
so they can match tracks which have each one of a list of artists.
There are [ways to express this in datalog with rules][dynamic-conjuction],
but it's clearer (and usually faster) to expand every input to a
separate clause, so you do that:

[dynamic-conjuction]: https://stackoverflow.com/a/43808266/1002469

```clojure
;; We have two artist name terms, and we want each one to match.
(let [every-artist ["genesis" "phil collins"]
      ;; Make one var per artist value like ?art0, ?art1, etc.
      syms (mapv #(symbol nil (str "?art" %)) (range (count every-artist)))]
  {:in syms
   ;; Then make one where clause per var name...
   :where (mapv %(list 'artist-name '?t %) syms)
   ;; ...and bind every artist value
   :args  every-artist})
```

### Refactoring for Performance

Later you realize that the `artist-name` rule is repeating work for every
"invocation" of a matching track.
You do some testing at a repl and find out that in this query
it's faster to determine the possible artist entity ids first
then filter matching track names by the artist entity ids to avoid a join.

You refactor artist matching to produce two sets of `:where` clauses
sandwiching the track-name matches (and possibly other matches).

```clojure
(cond-> []
        artist (conj (let [name-syms (mapv #(symbol nil (str
                                                         "?art-name" %))
                                           (range (count every-artist)))
                           id-syms (mapv #(symbol nil (str "?art" %))
                                         (range (count every-artist)))]
                       {:in name-syms
                        :where (mapv %(list 'artist-by-name %1 %2)
                                     name-syms
                                     id-syms)
                        :args  every-artist}))

        ;; other stuff that finds tracks and binds `?t`

        artist (conj (let [id-syms (mapv #(symbol nil (str "?art" %))
                                         (range (count every-artist)))]
                       {:in []
                        :where (mapv %(list 'track-artist '?t %)
                                     id-syms)
                        :args  []}))
        ;; ...
        )
```

Then a coworker, in a completely unrelated change
having to do with album art search terms,
accidentally uses a var named `?art0`,
and now some queries just silently return no results.
They accidentally collided with the dynamically-generated ids in `id-syms`.
You switch to gensyms instead, but you are annoyed that your generated queries
are no longer deterministic.
It busts the query plan cache and makes the assertions in your query generator
tests harder to write.

Then there's another feature request: a composite "or" operator,
so people can express queries like

```clojure
{:or {:track "rocket"
      :artist ["paul" "george"]}}
```

It's at about this point that you consider using qslice.

## Qslice Basics

Qslices is not _that_ different from the technique you were using.
Each qslice is essentially a bundle of `:in`, `:where`, and `:args` data
that is combined in the right order to form a datalog query.
What's different is that it adds extra metadata
read by the "combiner" to provide more flexibility and safety
in the final query.
The query "bundles" are instead made with `qslice`
and combined by `compile-query`.

### Making a Qslice

Let's start with a simple example that doesn't have any parameterization.
This is how you make a qslice that gives you all tracks in the database
and makes those tracks available to other qslices via the name `?t`:

```clojure
(require '[qslice.core :as qslice])

(def q-all-tracks
  (qslice/qslice
   ;; :name is just for logging and printing
   :name "all-tracks"
   ;; This is your :where query fragment
   :where '[[?t :track/id]]
   ;; :provide means that `?t` will be made available to *other* qslices
   ;; that :require it.
   ;; You have to keep track of your "common qslice vars" yourself,
   ;; e.g. through documentation.
   :provide '[?t]

   ;; In clojure >= 1.11, you can provide a map argument
   ;; instead of keyword-value pairs.
   ))
```

The result of `qslice` is a big map with a bunch of datalog var data:

```clojure
#:qslice.core{:let-pairs [],
              :let-vars #{},
              :letable-vars #{?t},
              :must-let-vars #{},
              :must-let-vars-checked? true,
              :name "all-tracks",
              :owned-vars #{},
              :provide-specials #{},
              :provide-variables #{?t},
              :require-specials #{$},
              :require-variables #{},
              :selectivity 0,
              :shaded-vars [],
              :where-forms [[?t :track/id]],
              :where-vars #{$ ?t}}
```

Note the `:require-specials #{$}`: qslice inferred that this slice needed
a database because the `:where` contains a [data pattern].
`qslice` will infer database names via implicit requirements of data patterns
or explicit variables starting with `$`.
It will also infer rule requirements via the presence of rule expressions.

[data pattern]: https://docs.datomic.com/query/query-data-reference.html#data-patterns

Every other var requirement to the qslice must be explicitly stated.
We'll cover requirements later.

### Making a Query from Qslices

Qslice is not a query by itself, just a piece of one.
You get a full query via `compiled-query`, which accepts a list of qslices
and a `:find` expression.

```clojure
(qslice/compiled-query [q-all-tracks] '[?t])

Execution error (ExceptionInfo) at qslice.core/compiled-query.
Some slices require a datasource or % that is not provided by another slice.
```

However, if you run this you will get the error above.
Remember how I said we needed a database?
`q-all-tracks` requires a database, but we didn't provide one to
`compiled-query`.

This illustrates a truth about qslices:
you cannot compile them unless _all their requirements are met_!

So let's provide a database qslice:

```clojure
(def q-db
  (qslice/qslice :name "q-db" :provide '[$]))
```
Notice this qslice has no `:where` clause because it doesn't need one.
What it provides will be provided via `:in`.

Let's try again:

```clojure
(qslice/compiled-query [q-db q-all-tracks] '[?t])

Execution error (ExceptionInfo) at qslice.core/assert-must-let-satisfied*
qslice required a var via :must-let but didn't get it
```

Another error!
Why won't this work?

#### Understanding Errors

Every error from `qslice` has a rich exception-info map that tries to explain
exactly what happened:

```clojure
(ex-data *e)
{:unsatisfied-vars #{$},
 :must-let-vars #{$},
 :let-vars #{},
 :binding-forms [],
 :slice #:qslice.core{:let-pairs [],
                      :let-vars #{},
                      :letable-vars #{$},
                      :must-let-vars #{$},
                      :must-let-vars-checked? false,
                      :name "q-db",
                      :owned-vars #{$},
                      :provide-specials #{$},
                      :provide-variables #{},
                      :require-specials #{},
                      :require-variables #{},
                      :selectivity 0,
                      :shaded-vars [],
                      :slice-idx 0,
                      :where-forms [],
                      :where-vars #{}},
 :cognitect.anomalies/category :cognitect.anomalies/incorrect,
 :cognitect.anomalies/message "qslice required a var via :must-let but didn't get it",
 :qslice.core/error :unsatisfied-must-let}
```

This map is a [cognitect anomaly]
that also includes a `:qslice.core/error` to categorize the problem
and other human-readable entries to help diagnose the error.

[cognitect anomaly]: https://github.com/cognitect-labs/anomalies

In this case, it's complaining about an `:unsatisfied-var $`
needed by a slice named `q-db`.
Although we made a qslice that provides the datasource `$`,
we didn't actually provide a datasource!

#### Binding Args with "Let"

You bind a datasource using some form of binding via "let"--but
not Clojure `let`!
There are three ways to bind, all equivalent:

```clojure
;; Pretend we have a Datomic db
(def db 'db)

;; You can bind when you make the qslice:
(qslice/qslice :name "q-db" :provide '[$] :let {'$ db})

;; or bind afterwards using `with-let` and pairs of binding+value
(qslice/with-let q-db '$ db)

;; Or bind afterwards using a map:
;; with-let _allows_ a map and delegates to with-let*
(qslice/with-let q-db {'$ db})
;; with-let* _requires_ a map.
(qslice/with-let* q-db {'$ db})
```

All of these produce a "bound" qslice.

Binding always adds `:in` and `:args` entries to the final compiled query.
Just like normal datalog, you may bind _any var_ mentioned by the `:where`--or
not, if you don't want to.
You must bind `:provides` variables if the `:where` doesn't provide it.
You may also use `:must-let` to _require_ a binding--normally
values for variables that are not in `:provides` are optional.
This is a safety feature to protect against inefficient queries.
It serves the same purpose as [required rule bindings].

[required rule bindings]: https://docs.datomic.com/query/query-data-reference.html#rule-required-bindings

Let's try one last time:

```clojure
(qslice/compiled-query
 [(qslice/with-let q-db '$ db) q-all-tracks]
 '[?t])
=>
{:query {:find [?t], :in [$], :where [[?t :track/id]]},
 :args [db],
 :slice-order [{:slice-idx 0, :name "q-db", :selectivity 0}
               {:slice-idx 1, :name "all-tracks", :selectivity 0}]}
```

Success!

The return value is something you can give directly to `d/query`
because its `:args` are set.
The values in `:args` are the values you bound to the qslices with "let".

#### Inspecting Compilation Results

`:slice-order` is some metadata
about how `compiled-query` ordered the qslices.
`compiled-slice-order-names` is a convenience function
that turns this into a more compact representation for logging:

```clojure
(qslice/compiled-slice-order-names *1)
=> ["q-db" "all-tracks"]
```

But `:slice-order` is a public contract, so you are free to write your own.

#### Database and Rule Qslice Constructors

Because needing a database or a rule is so common,
qslice provides two convenience qslice constructors
`db-qslice` and `rule-qslice`
to make them for you.

```clojure
(qslice/compiled-query
 [(qslice/db-qslice db) q-all-tracks]
 '[?t])
=>
{:query {:find [?t], :in [$], :where [[?t :track/id]]},
 :args [db],
 :slice-order [{:slice-idx 0, :name $, :selectivity 0}
               {:slice-idx 1, :name "all-tracks", :selectivity 0}]}
```

## Translating to Qslices

The previous example using `all-tracks` was a toy example that didn't have
any useful parameterization.
Let's try the first real example from our problem narrative--track and artist
name matching--but implement it using qslice instead.

Here is our example user query:

```clojure
(def user-query
  {:track "broadway" :artist "genesis"})
```

We need to make qslices for track and artist name queries,
then a function to convert the `user-query` to a list of bound qslices.

```clojure
;; First, we define some unbound qslices.
;; (There's some parsing work done by qslice/qslice
;; which you don't need to repeat if you define your qslice just once.)
(def q-track-name
  (qslice/qslice
   :name "track-name"
   :where '[(track-name ?t ?param)]
   ;; The `?t` can be seen by other qslices.
   :provide '[?t]
   ;; This qslice must have `?param` bound at compile-time.
   :must-let '[?param]))

(def q-artist-name
  (qslice/qslice
   :name "track-artist-name"
   :where '[(track-artist-name ?t ?param)]
   :provide '[?t]
   :must-let '[?param]))

;; Then we write a function that takes the application-defined query
;; and translates it to *bound* qslices.
(defn user-query->qslices [user-query]
  (mapv (fn [[term param]]
          (let [qs (case term
                     :track q-track-name
                     :artist q-artist-name)]
            ;; This part binds the value of `param` to the var `?param`.
            (qslice/with-let qs '?param param)))
        user-query))

;; Dummy db and rule values for later
(def qdb (qslice/db-qslice 'db))
(def qr (qslice/rule-qslice '%))
```

Now let's translate and compile the user-query:

```clojure
(def user-query-translated
  (user-query->qslices user-query))

(qslice/compiled-query
 (into [qdb qr] user-query-translated)
 ;; Notice you are not limited to a single `:find` spec.
 ;; You can provide a map of multiple find-related entries.
 ;; Here, we're using `:keys` so the query will return a set of maps. 
 {:find ['?t] :keys ['track-eid]})
=>
{:query {:find [?t],
         :keys [track-eid],
         :in [$ % ?param:0:2 ?param:0:3],
         :where [(track-name ?t ?param:0:2)
                 (track-artist-name ?t ?param:0:3)]},
 :args [db % "broadway" "genesis"],
 :slice-order [{:slice-idx 0, :name $, :selectivity 0}
               {:slice-idx 1, :name %, :selectivity 0}
               {:slice-idx 2, :name "track-name", :selectivity 0}
               {:slice-idx 3, :name "track-artist-name", :selectivity 0}]}
```

### Var munging

Notice in the compiled `:where` clause there are some unusual var names
that were not in your qslices:

```clojure 
[(track-name ?t ?param:0:2)
 (track-artist-name ?t ?param:0:3)]
```

The `?param:0:2` and `?param:0:3` are munged var names
that correspond to the `?param` in the qslices.
This munging is what allowed both qslices to use `?param` as a var
without them accidentally unifying together.

The `?t` was *not* munged because it was in the `:provide` of both qslices.
Vars in `:provide` or `:require` are not munged
because they are meant to be communicated between qslices
or pulled out of `:find`.

For your own sanity, I strongly recommend you document
the `:provide` and `:require` var names
that are meant to be shared among related qslices!

## Adding "Any" Values

Let's extend our user-query language.
Instead of a single value, let's allow multiple values per term.
If _any_ of them are satisfied by the track, the track is included.

```clojure
(def user-query:any-values
  ;; Include track names that contain either "broadway" or "lilith" 
  {:track ["broadway" "lilith"]
   :artist "genesis"})
```

### Let Bindings with Destructuring

Adding this feature changes our translation function,
but requires no changes to our qslices
because of how let-binding works.

This is the change our translation function needs to handle our "or"
extension:

```clojure
(defn user-query->qslices2 [user-query]
  (mapv (fn [[term param]]
          (let [qs (case term
                     :track q-track-name
                     :artist q-artist-name)]
            ;; When you "let", you can use destructuring!
            (qslice/with-let qs (if (coll? param)
                                  '[?param ...]
                                  '?param)
                             param)))
        user-query))
```

When we run this translation and compile a query:

```clojure
(qslice/compiled-query
 (into [qdb qr]
       (user-query->qslices2 user-query:any-values))
 {:find ['?t] :keys ['track-eid]})
=>
{:query {:find [?t],
         :keys [track-eid],
         ;; Notice the destructuring for ?param:0:2 made it to the :in clause.
         :in [$ % ?param:0:3 [?param:0:2 ...]],
         :where [(track-name ?t ?param:0:2)
                 (track-artist-name ?t ?param:0:3)]},
 :args [db % "genesis" ["broadway" "lilith"]],
 :slice-order [{:slice-idx 0, :name $, :selectivity 0}
               {:slice-idx 1, :name %, :selectivity 0}
               {:slice-idx 2, :name "track-name", :selectivity 0}
               {:slice-idx 3, :name "track-artist-name", :selectivity 0}]}
```

This illustrates an important fact about `with-let`:
it understands the same destructuring forms as `:in`
and can apply its var-munging logic to them.
Your `:where` clause variables are independent of
the `:in` destructuring used to bind those parameters,
just like in normal datalog.

Each call to `with-let` completely replaces all bindings the qslice had before
because there's no straightforward way to merge bindings when destructuring 
is possible.

### Controlling Qslice Order with Selectivity

When you added this change, you introduced the possibility that
someone may use "or" in *any* term and increase the number of tracks that 
term would match.
In Datomic, you want the most selective clauses first to reduce the result 
set size as soon as possible.
But this feature makes it difficult to predict which clauses will be the 
most restrictive.

Qslices have a value called "selectivity"
to control the order in which qslices appear in the compiled query.
Selectivity is just an integer:
smaller values (including negative values) will appear before larger values;
two qslices with the same selectivity will tie-break by their order in the 
qslice list.
The default selectivity is 0.

Note that "selectivity" is a confusing name
because *larger* selectivity values are actually *less* selective!
I apologise for the confusion.

We can use selectivity to implement an extremely basic
cost model for clause ordering.
Let's say that we decide clauses with more parameters are less selective.

```clojure
(defn user-query->qslices3 [user-query]
  (mapv (fn [[term param]]
          (let [qs (case term
                     :track q-track-name
                     :artist q-artist-name)]
            (-> qs
                (qslice/with-let
                 (if (coll? param) '[?param ...] '?param)
                 param)
                ;; Set selectivity to the number of parameters
                ;; on the assumption that more parameters will match more rows.
                (qslice/with-selectivity
                 (if (coll? param) (count param) 1)))))
        user-query))
```

If we use the same user-query with the new translator
the track-name search term appears second:

```clojure
(-> (into [qdb qr]
          (user-query->qslices3 user-query:any-values))
    (qslice/compiled-query '[?t]))
=>
{:query {:find [?t],
         :in [$ % ?param:0:3 [?param:0:2 ...]],
         :where [(track-artist-name ?t ?param:0:3)
                 (track-name ?t ?param:0:2)]},
 :args [db % "genesis" ["broadway" "lilith"]],
 :slice-order [{:slice-idx 0, :name $, :selectivity 0}
               {:slice-idx 1, :name %, :selectivity 0}
               ;; Note the nonzero selectivity values
               {:slice-idx 3, :name "track-artist-name", :selectivity 1}
               ;; Note the slice index is lower than the previous entry.
               ;; The selectivity sorting took precedence.
               {:slice-idx 2, :name "track-name", :selectivity 2}]}
```

You can do anything you want with this feature.
Here are some ideas:

* Assign each qslice type a different static selectivity
  to ensure a stable total order no matter how the translation function
  is implemented.
* Assign a base per-row cost to a qslice
  (e.g. by assoc-ing with your own key--remember qslices are just maps!),
  then multiply by expected row count based on the parameter.
* Use some external source of statistics (say from a nightly batch job)
  to assign a selectivity based on expected row count.

These are all exercises for the reader.

### Adding "Every" Matches

Now you have to implement the "every-artist" term which takes multiple
values and requires that the track is only included if *all* match.

When we weren't using qslice, we used a technique that involved creating
one dynamic `:where` clause per input term with a var-munged symbol.

However, qslices have the machinery for var munging built-in!
We can approach this by just emitting more qslices.

Let's change our user-query to use one of these "match everything" terms.

```clojure
(def user-query:every-value
  {:track "submarine" :every-artist ["paul" "george"]})
```

(Hey, I said it was a _realistic_ query syntax, not a _good_ one!)

Our query term count is increasing,
and the new term acts differently than our previous ones in important ways.
We'll take this opportunity to do a refactor.

First lets define some helper functions for selectivity scoring
and param binding.

```clojure
;; Define some helpers for calculating selectivity
;; This is the one we used before:
;; more params means "any", which means more rows.
(defn less-selective-by-param-count [param]
  (if (coll? param) (count param) 1))

;; This is the opposite:
;; "every" means more things must match, which means fewer rows.
(defn more-selective-by-param-count [param]
  (- (less-selective-by-param-count param)))

;; This is a helper to make the with-let forms.
;; It's designed for use with `qslice/with-let*`,
;; which is better for programmatic use because it only accepts a seq of
;; binding:value pairs instead of a flat seq.
(defn param-bindings [param]
  (if (coll? param)
    [['[?param ...] param]]
    [['?param param]]))
```

Now lets pull our `case` statement out into a map of functions,
including the new `:every-artist` term.

```clojure
;; Extracting the term to qslice mapping to a map
(def term->qslices
  {:every-artist
   ;; This is the new term.
   ;; Unlike our previous code where one term emitted one qslice,
   ;; the :every-artist term emits one qslice per parameter value.
   (fn [params]
     ;; We'll reuse the q-artist-name qslice, because it's the same
     ;; We'll just return multiple of them, one per param.
     (let [q-every-artist (-> q-artist-name
                              ;; For easier debugging, we'll change the name.
                              (qslice/with-name "every-artist")
                              ;; It's also scored differently:
                              ;; more params means _fewer_ rows match, not more
                              (qslice/with-selectivity
                               (more-selective-by-param-count params)))]
       ;; Now we make one bound qslice per param.
       ;; Each one has the same name as selectivity.
       (mapv (fn [param]
               (qslice/with-let q-every-artist '?param param))
             params)))

   ;; These are the terms we had before.
   ;; We need to return our qslice in a vector now because the contract is now
   ;; "return *multiple* qslices per query term."
   :track (fn [param]
            [(-> q-track-name
                 (qslice/with-selectivity
                  (less-selective-by-param-count param))
                 (qslice/with-let* (param-bindings param)))])
   :artist (fn [param]
             [(-> q-artist-name
                  (qslice/with-selectivity
                   (less-selective-by-param-count param))
                  (qslice/with-let* (param-bindings param)))])})
```

Our new query translator now just dispatches into the map to get a qslices
constructor and passes it the params:

```clojure
(defn user-query->qslices4 [user-query]
  (into []
        (mapcat (fn [[term param]] ((term->qslices term) param)))
        user-query))
```

Let's try it out:

```clojure
(qslice/compiled-query
 (into [qdb qr] (user-query->qslices4 user-query:every-value))
 '[?t])
=>
{:query {:find [?t],
         :in [$ % ?param:0:2 ?param:0:3 ?param:0:4],
         :where [(track-artist-name ?t ?param:0:3)
                 (track-artist-name ?t ?param:0:4)
                 (track-name ?t ?param:0:2)]},
 :args [db % "submarine" "paul" "george"],
 :slice-order [{:slice-idx 3, :name "every-artist", :selectivity -2}
               {:slice-idx 4, :name "every-artist", :selectivity -2}
               {:slice-idx 0, :name $, :selectivity 0}
               {:slice-idx 1, :name %, :selectivity 0}
               {:slice-idx 2, :name "track-name", :selectivity 1}]}
```

Notice how our `:slice-order` puts the qslices related to :every-artist
first, and we didn't have to construct any magic symbols manually.

## Refactoring for Performance 

Remember we determined that it was faster to match the artist names first,
separately rather than once per track.
We can implement that same refactor with qslices.
First let's write the qslice for the "get the artist names first" part:

```clojure
(def q-artist-by-name
  (qslice/qslice
   :where '[(artist-by-name ?param ?artist-id)]
   ;; Keeping with our "param" convention.
   :must-let '[?param]
   ;; We don't provide `?t`, but we do provide something we'll use later.
   :provide '[?artist-id]
   ;; We want this to execute early in the query.
   :selectivity -100000))
```

### Using :require for safety

Now let's implement the track-matches-artist-id half:

```clojure
(def q-track-with-artist-id
  (qslice/qslice
   :where '[(track-artist ?t ?artist-id)]
   ;; This actually provides the `?t`
   :provide '[?t]
   ;; But it requires an ?artist-id from the other query! 
   :require '[?artist-id]))
```

We've introduced a new concept: `:require`.
We did touch on this briefly when we talked about how `:require-specials`
keeps track of whether a qslice needs a database or rule.
This is a little different because it's about requiring a _variable_.

`:provide` is a variable the qslice makes available to other qslices
for unification, whether through let-binding or the `:where` clause.
`:require` is the other half of that.
It prevents vars in `:where` from getting munged,
but it also prevents you from let-binding the variable to the qslice
or from compiling a query where the `:require` of the qslice is not met.

`:require` mostly exists as a safety measure to prevent inadvertently
compiling queries where a critical variable is left free that should be bound.

Let's finish refactoring our translator:

```clojure
(def term->qslices
  {
   ;; ...
   ;; :every-artist and :track remain the same
   ;; ...
   :artist (fn [param]
             ;; We've just split this into two parts
             [(-> q-artist-by-name
                  (qslice/with-let* (param-bindings param)))
              q-track-with-artist-id])})
```

## Adding structural "OR"

Your previous implementation without qslice
stopped short of trying to implement an "or" operator
to accept queries like this:

```clojure
(def user-query:or
  {:or {:track "rocket"
        :every-artist ["paul" "george"]}})
```

This is interpreted as "include any track for which any of the term+value
pairs matches."

What you want to do here is dynamically emit an `or-join` clause
which has one `and` clause per subitem containing many clauses.
This is still tricky, even with qslice.
But qslice does include a function to help with this.

### `or-qslice`

`or-qslice` is a function which takes a list of bound qslices
and returns a single bound qslice where each qslice is a different clause
in an `or-join`

This is easier to illustrate with an example:

```clojure
(-> (qslice/or-qslice
     [(qslice/qslice
       :where '[[?t :track/name ?name]
                [?t :track/id ?id]]
       :require '[?t]
       :provide '[?id])
      (qslice/qslice
       :where '[[?t :track/artist ?art]
                [?art :artist/name ?name]
                [?t :track/id ?id]]
       :require '[?t]
       :provide '[?id]
       :let {'?name "paul"})])
    ::qslice/where-forms)
=>
[(or-join
  [[?name:1:1 ?t] ?id]
  (and [?t :track/name ?name:0:0]
       [?t :track/id ?id])
  (and [?t :track/artist ?art:0:1]
       [?art:0:1 :artist/name ?name:1:1]
       [?t :track/id ?id]))]
```

The `or-join` that is produced ensures that vars are munged separately
across all branches the same way `compiled-query` would.

The vars the qslice `:requires` or already bound
are declared as must-be-bound (surrounded by `[ ]` up front),
The `:provide` vars are declared
but left free to unify with other parts of the query.
All other vars (such as `?art:0:1`) are unmentioned
and only unify within their branch.

### `let-lock`

`or-join` needs to apply var-munging immediately
to disambiguate vars of the same name from different branches.
This makes it impossible to use `with-let` on the result of or-join.
In this example, how would you know which `?name` you meant:
the one for `:artist/name` or for `:track/name`?

As a safety feature, qslice introduces a notion of "let-locking".
Once a qslice is "let-locked", using any variant of `with-let` on it
will throw an error:

```clojure
(-> (qslice/or-qslice
     [(qslice/qslice
       :where '[[?t :track/name ?name]
                [?t :track/id ?id]]
       :require '[?t]
       :provide '[?id])
      (qslice/qslice
       :where '[[?t :track/artist ?art]
                [?art :artist/name ?name]
                [?t :track/id ?id]]
       :require '[?t]
       :provide '[?id]
       :let {'?name "paul"})])
    (qslice/with-let '?name "george"))

Execution error (ExceptionInfo) at qslice.core/with-let* (core.clj:321).
qslice is locked: it cannot be with-let again.
```

If you want, you can let-lock any query you want using
`qslice.core/with-let-locked`.

### Using `or-qslice`

Besides let-locking, `or-qslice` has a number of other limitations:
some are imposed by the semantics of `or-join` and rules themselves,
but some are just implementation shortcuts that could be improved.
Please read the docstring for the function carefully.

One big limitation important for this tutorial is that `or-qslice`
requires that every "branch" be a _single_ qslice.
(Not a single `:where` clause though: multiple are fine.)
There's no reason for this limitation other than
nothing else was needed at the time it was implemented.

Either a corresponding `and-qslice` combinator or an extension to 
`or-qslice` could lift this limitation.
However, we're going to keep it for this tutorial,
which means we can't support the way we implemented the `:every-artist` term
or our split artist-name approach with `:or`
because they both use multiple qslices.
We'll have to fall back to more primitive methods.

```clojure
;; Here's our special implementations for inside an :or clause
(def or-term->one-qslice
  ;; We have to return exactly one qslice per term again.
  {:every-artist
   (fn [params]
     ;; This is roughly what we did without qslice.
     ;; It's safer and a little easier with qslice,
     ;; but we still need to generate symbols ourselves.
     (let [syms (mapv #(symbol nil (str "?art" %)) (range (count params)))]
       (qslice/qslice
        :name "or:every-artist"
        :selectivity (more-selective-by-param-count params)
        :where (mapv #(list 'artist-name '?t %) syms)
        :provide ['?t]
        :let (mapv vector syms params))))
   ;; This is the artist-name approach without our refactor into two parts.
   :artist (fn [param]
             (-> q-artist-name
                 (qslice/with-selectivity
                  (less-selective-by-param-count param))
                 (qslice/with-let* (param-bindings param))))
   ;; This stays the same
   :track (fn [param]
            (-> q-track-name
                (qslice/with-selectivity
                 (less-selective-by-param-count param))
                (qslice/with-let* (param-bindings param))))})
```

Now let's write a new translation function that wraps our `:or` qslices
with `or-qslice`:

```clojure
(defn user-query->qslices5 [user-query]
  (into []
        (mapcat (fn [[term param]]
                  (if (= :or term)
                    (qslice/or-qslice
                     (map (fn [[term param]]
                            ((or-term->one-qslice term) param))
                          param))
                    ((term->qslices term) param))))
        user-query))
```

And finally compile our user query:

```clojure
(qslice/compiled-query
 (into [qdb qr]
       (user-query->qslices5
        {:or {:track "rocket"
              :every-artist ["paul" "george"]}}))
 '[?t])
=>
{:query {:find [?t],
         :in [$ % ?art0:0:1:0:2 ?art1:1:1:1:2 ?param:0:0:2:2],
         :where [(or-join [[?art0:0:1:0:2 ?art1:1:1:1:2 ?param:0:0:2:2] ?t]
                  (track-name ?t ?param:0:0:2:2)
                  (and (artist-name ?t ?art0:0:1:0:2)
                       (artist-name ?t ?art1:1:1:1:2)))]},
 :args [db % "paul" "george" "rocket"],
 :slice-order [{:slice-idx 0, :name $, :selectivity 0}
               {:slice-idx 1, :name %, :selectivity 0}
               {:slice-idx 2, :name "or(track-name or:every-artist)", :selectivity 1}]}
```

Now we see what `or-qslice` is doing.
It munged all vars that weren't provided or required--in
this case the `?t` is provided, so it was left alone.
It generated a list of vars to unify with outer clauses.
(This is the part immediately after the `or-join` in the query.)
It only mentions vars that are provided, required, or let-bound.
Vars that were already let-bound were declared as required-to-be-bound
(the double vector around the `?artX` and `?paramX` vars)
to let the datalog query parser know what to expect.
The shared provided var `?t` was also mentioned
to make sure the `?t` in each branch unifies with clauses
that are siblings to the `or-join`.

And finally, it created a composite qslice name for logging purposes
with the structure `or(name of each qslice inside it)`.

## Conclusion

That's it! You now know all the features of the qslice library.

Qslice isn't magic--it's just a safer and more organized abstraction
over the same kinds of dynamic query templating you were doing without it.

I hope qslice can help you maintain very dynamic datalog query translators
in your codebase with a little less hair loss.

## License

This file is released under the MIT License
and is copyright © 2024 Francis Avila.
