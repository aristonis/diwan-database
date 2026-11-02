# 0017. Relations

## Status

Accepted

## Context

Laravel developers expect `hasOne`, `hasMany` and `belongsTo`, lazy reads, eager loading with
`with`, existence filters such as `whereHas`, writes through a relation and relations in
`toArray`. Laravel finds a relation by calling a method of the same name, adds its constraints in
the constructor unless a static switch turns them off, numbers self-join aliases from a
process-wide counter and builds eager `in` lists without limits. None of that fits a library that
uses no reflection, runs on Android where R8 renames and removes methods, and lets several threads
share one `ModelQuery`. Laravel also has defects a port should not copy: a null parent key reaches
the eager `in` list and an integer `in` list turns it into `0`, so orphan rows are fetched, and the
alias counter makes the SQL text of the same query depend on what ran before it.

## Decision

1. A relation is a typed method on the model (`HasMany<Post> posts()`) and a name in the map
   `relations()` returns (`RelationMap.of(User.class).add("posts", User::posts)`), as `casts()`
   does for casts. Names are looked up in that map, never by reflection. The map is checked once
   per model class, on first use: each factory runs once against a fresh instance and must return
   a relation whose related class has a `ModelQuery` entry. The result is cached in the model's
   entry, and a failed check is cached too, so every later use throws the same
   `InvalidRelationException`. A subclass inherits its parent's map.
2. There is no static constraints switch. A relation is built without constraints. The lazy entry
   points (`getRelationValue`, `related`, `relatedMany`, the relation's own `get`, `getResults`
   and `getQuery`) add the parent-key constraint once before the first query; eager loading and
   existence queries never call that path. A relation instance is therefore single-use and not
   thread-safe, and nothing is shared between threads except immutable registries.
3. Eager keys: null keys are dropped for every relation type, duplicates are queried once. Integer
   keys are written inline in the `in` list and split at the grammar's inline size (SQLite 20,000,
   PostgreSQL 50,000); other keys are bound and split at the grammar's parameter size (SQLite 900,
   PostgreSQL 10,000), less the values the user constraint binds. The constraint is first applied
   to a probe query that never runs, to count those values, so it runs once per chunk plus once.
   A value that cannot be converted exactly to an integer is refused with
   `IllegalArgumentException`; Laravel casts `"abc"` to `0` and `1.9` to `1`.
4. Matching treats a whole number of any Java type and canonical integer text as the same key:
   `"1"` matches `1`, while `"01"`, `"1.0"`, `" 1"` and `"-0"` do not. Other text is compared as
   written, so a `UUID` object and its upper-case text do not match.
5. An eager constraint that sets `limit` or `offset` is refused with `IllegalStateException`.
   Laravel turns it into a per-parent window query, which needs window functions that SQLite on
   Android 9 lacks.
6. Self-join aliases (`laravel_reserved_0`, `_1`, ...) come from a counter owned by the outermost
   query and copied with it, so the same query always compiles to the same SQL. A nested self
   relation gets a new alias at every level.
7. `whereHas` and the other constrained existence filters wrap the constraint in parentheses
   inside the `exists` subquery, and add nothing when the constraint adds no `where`. Laravel
   groups only when the constraint has an `or`. The SQL text differs; the rows do not.
8. `save` and `create` through a relation whose parent has no key throw `IllegalStateException`
   before any write; Laravel writes the row with a null foreign key.
9. `getAttribute` never falls back to a relation. Relations are read with `getRelationValue`,
   `related` and `relatedMany`, so a typo in an attribute name never runs a query.
10. `belongsTo` needs its foreign key. Laravel derives it from the calling method's name through a
    backtrace, which Java does not offer.
11. `ModelQuery.preventLazyLoading(true)` is a process-wide setting made at startup. A lazy read
    of a relation on a model that came from a multi-row result throws
    `LazyLoadingViolationException` instead of querying. Laravel's replaceable violation callback
    is not ported.
12. The bridges between the `eloquent` and `eloquent.relations` packages live in
    `eloquent.internal`, which is not API and is left out of the published Javadoc. Each side
    registers its half in a static initialiser, so no reflection is needed.
13. `toMap()` writes loaded relations after the attributes, under snake-case keys unless the model
    turns that off, honouring `hidden` and `visible`. A model already being serialised on the
    current path is written with its attributes only, so a cycle ends. A shared instance is still
    written in full at every place it appears, so two-way relations loaded on both sides grow with
    the product of the two sides, as in Laravel.

## Consequences

- No reflection, annotations or keep rules; relations work the same on SQLite, PostgreSQL and
  Android.
- A relation must be declared twice, as a method and as a map entry. A mistake in the map is
  reported once, at first use, naming the model and the relation.
- A related model's class must have run its static initialiser before a relation names it. A class
  literal such as `Post.class` does not run it, so an application touches each model's `query`
  entry at startup (or the model is otherwise used first).
- Large eager loads never hit SQLite's or PostgreSQL's parameter limits; results come back in chunk
  order, which can differ from the order of one big query.
- An eager constraint runs once per chunk plus once, so it must not count its calls or have other
  side effects. An `or` in it is not grouped, as in Laravel, and widens the match; wrap it in
  `where(q -> ...)`.
- Passing the same relation to `with` twice runs both constraints; Laravel keeps the last.
- A model's default foreign key comes from its class name. On Android, R8 renames classes, so
  apps pass foreign keys explicitly or override `getForeignKey()`.
- Clauses chained onto a relation inside a `relations()` factory or a typed relation method, such
  as `hasMany(Comment.class).where("approved", true).latest()`, are supported. Before the relation
  is constrained they are recorded, not applied: a lazy read adds the foreign key first and then
  the clauses, an eager load applies them to its key query, and `has`/`whereHas` take only their
  `where` clauses, as Laravel does.
- `cursor()` with eager loads throws; Laravel silently ignores the eager loads there. Use `lazy()`.
- Bound eager keys keep the parent's Java type (the first value seen for each key), so text key
  `"123"` binds as text and PostgreSQL compares it with a `varchar` column. Keys are matched back
  by a normal form that treats `123` and `"123"` alike.
- The internal access holders (`org.aristonis.diwan.database.eloquent.internal`) are public only
  because the packages need them without reflection and without `module-info`. They are not API
  and must not be called.
- `ModelQuery.preventLazyLoading` is process-wide. Set it once at startup; tests set it in one
  place and not from tests that run in parallel.
- A relation that names a model class whose `ModelQuery` entry is not registered yet fails that
  use but is checked again on the next one; other map errors are reported once and cached.

## Alternatives rejected

- Finding relations by method name: needs reflection and breaks under R8.
- Annotations on relation methods: removed by R8 and need a processor.
- Laravel's static constraints switch: unsafe when threads share models' entries.
- One process-wide alias counter: SQL text would depend on earlier queries.
- Chunking eager keys only when a limit is reached, or applying a global limit: silently differs
  from Laravel's per-parent limit.
- A `ThreadLocal` recursion guard for `toMap()`: hidden state; an explicit visited set is passed
  instead.
