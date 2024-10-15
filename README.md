# Datomic Query Slices

This is a library to represent partial Datomic datalog queries with their 
bindings and combine them into complete queries dynamically, repeatably and 
safely.

## Installation

deps.edn jar

```
net.clojars.favila/qslice {:mvn/version "1.0.10"}
```

project.clj

```
[net.clojars.favila/qslice "1.0.10"]
```

## Motivation

`qslice` (or "query slice") solves ergonomic problems that arise
when complex runtime-created, domain-specific query structures
must be translated into Datomic-flavored datalog dynamically.
Problems addressed include:

* Accidental reuse of unrelated var symbols, causing undesired unification.
  Solution: automatic var munging of `:where` contents.
* Accidentally free variables that were expected from elsewhere in the query.
  Solution: The `require` and `provide` qslice parameters.
* Correctly associating`:in` parameters with the `:where` clauses that 
  require them.
  Solution: The `let` qslice parameter, and the qslice map itself.
* Combining multiple clauses into a single `or` clause.
  Solution: the `or-qslice` combinator.
* Poor clause ordering when the clauses are not statically known.
  Solution: the `selectivity` qslice parameter.
* Poor query plan cache-ability because the output is nondeterministic.
  Solution: Given the same inputs, the result of `compile-query` is 
  always value-equals.
* Poor visibility into query similarity when trying to identify patterns
  among slow queries.
  Solution: the `name` parameter of qslices, the fact that qslices are 
  ordinary maps, and the `compiled-slice-order-names` convenience function.
* Difficult datalog refactors when needing to do something unusual for 
  performance, such as memoize identifier lookup outside the query,
  split work within a query into widely separated clauses,
  or special case certain patterns like negation or filtering vs enumeration.
  Solution: target qslice constructors as an intermediate interface
  and assemble a full query from returned qslices.

All of these are trivial problems when the datalog query is static or 
mostly-static because they are evident and fixable without considering 
runtime states too deeply.

You *should not use this library* for static or even simple dynamic query 
construction.
You should first try using plain [Datomic rules] 
or `cond->` to build your queries.
These can handle even complex requirements.
Only reach for this library if these techniques are insufficient.

[Datomic rules]: https://docs.datomic.com/query/query-data-reference.html#rules

## Overview

The central abstraction of this library is that a query is composed of  
a sortable list of datalog "query slices" or "qslices"
which collect `:where` clauses, metadata and parameterization.
A list of qslices has a deterministic transformation to a datalog query map
that can be passed directly to a Datomic query execution function such
as `datomic.api/query` or `datomic.client.api/query`.

Adopting this library means that you write your own qslices
and a transformation from your application's query representation
to a list of those qslices with their arguments.
You then let the library handle the qslice list
"compilation" into a datalog query.

This library has no dependencies (even on Datomic!) because
its transformations are purely structural.
It was designed to support Datomic's syntax in particular
and (to my knowledge) has only ever been used with Datomic, 
but it should work with Datomic-like datalog syntaxes
such as those used by [DataScript], [Datalevin], or [XTDB v1].

[DataScript]: https://github.com/tonsky/datascript
[Datalevin]: https://github.com/juji-io/datalevin
[XTDB v1]: https://v1-docs.xtdb.com/language-reference/datalog-queries/

## Tutorial

There is a narrative [tutorial] is which walks through
a realistic example of a problem qslice is designed for
and how one would use qslice to solve it.

[tutorial]: tutorial.md

## API Reference

Please refer to the [`qslice.core` namespace doc][qslice.core]
for a conceptual and API reference.

[qslice.core]: https://github.com/favila/qslice/blob/main/src/qslice/core.clj

```clojure
(clojure.repl/doc qslice.core)
```

Every public function also has a docstring, e.g.:

```clojure
(clojure.repl/doc qslice.core/qslice)
```

## Change Log

### [v1.0.10] - 2024-10-15
[v1.0.10]: https://github.com/favila/qslice/compare/607d97899ca8d75946651f4181caafe0ad02103a...v1.0.10

Initial release forked from [shortcut-qslice].

* Adds this README
* Adds a tutorial
* Removes `ArraySeq` specialization from `qslice.walk/walk`.
* Adds `build.clj` and `:build` deps.edn alias.

## History and Status

Francis Avila wrote `qslice.core` and `qslice.core-test` namespaces 
in March 2023 while an employee of [Shortcut].
It has been running in user-facing production code at Shortcut since June 2023
without significant modification
except for the `or-qslice` feature added in October 2023.

In late 2023 or early 2024 Shortcut decided to open-source qslice as a library,
but it could not allocate the resources to package and maintain it.
On August 12th, Shortcut [released the source code][shortcut-qslice]
with the MIT license and its copyright,
but without any further commitment to its development.

[This fork][favila-qslice] is substantially identical
to the source code Shortcut released
and maintains its copyright.
The only difference is that Francis Avila (no longer an employee of Shortcut)
maintains it as a private person.
He added this README.md, a tutorial, a [clojars artifact], and code to build 
that artifact.

[Shortcut]: https://shortcut.com
[shortcut-qslice]: https://github.com/useshortcut/qslice
[favila-qslice]: https://github.com
[clojars-artifact]: https://clojars.org/net.clojars.favila/qslice

## TODO list

These are things that I think could be addressed in rough order of priority.
I make no commitment to delivering any of these.

 - [ ] Lift the "one qslice per branch" limitation of or-qslice
 - [ ] [Pull expression pattern-name support](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/src/qslice/core.clj#L567)
 - [ ] [Topo sort by require/provide](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/src/qslice/core.clj#L486)
 - [ ] [Shadeable datasources](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/src/qslice/core.clj#L162)
 - [ ] [Don't assume or-join/not-join requires a datasource](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/src/qslice/core.clj#L111)
 - [ ] [Ensure rules and qualified symbols are available](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/src/qslice/core.clj#L149)
 - [ ] [or-qslice can require datasources other than $](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/src/qslice/core.clj#L716)
 - [ ] [Smarter renaming of rule vars](https://github.com/favila/qslice/blob/607d97899ca8d75946651f4181caafe0ad02103a/test/qslice/core_test.clj#L395)
 
## Testing and Building

(This is just to remind myself.)

```shell
clojure -M:test # runs tests with kaocha
clojure -T:build clean
clojure -T:build jar # cleans first

# prints version, date, github compare link for changelog
# Remember to change the compare link to the last release.
clojure -T:build changelog-header

# Go get a deploy token from https://clojars.org/tokens
# deploy also cleans and builds jar
export CLOJARS_USERNAME=username
export CLOJARS_PASSWORD=token
clojure -T:build deploy

# If above succeeds, it will print a git tag command of the deployed version.
# Run it and push.
```

## License

This README.md file is released under the MIT License
and is copyright © 2024 Francis Avila.

The remainder of this software is released under the MIT License
and has the following copyrights:

Copyright © 2024 Shortcut Software Company
Copyright © 2024 Francis Avila
