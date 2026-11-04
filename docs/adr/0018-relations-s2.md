# 0018. Relation aggregates, touches, push and inverse relations

## Status

Accepted

## Context

On top of the relations in [ADR 0017](0017-relations.md), Laravel developers expect `withCount`
and the other relation aggregates, the same on loaded models (`loadCount`), `touches` to bump a
parent's `updated_at`, `push` to save a loaded graph, and `chaperone` to point children back to
their parent. Laravel's versions carry defects a port should not copy: `Collection::loadAggregate`
fails with a fatal error when a row was deleted meanwhile or the query returns nothing, and never
splits its key list; `touchOwners` lazy loads every touched relation, so a save can run hidden
`SELECT`s or throw under `preventLazyLoading`; `withoutTouching` keeps a process-wide static list;
`withAggregate` writes any function text into the SQL; aggregate and existence subqueries correlate
on the table name even when the outer query aliases it; inverse linking runs twice on eager loads.
Diwan also had no per-instance casts, so `exists` would read as `1`/`0` or `true`/`false` by
database.

## Decision

1. Aggregates are subselects built on the relation's existence query, one per relation in call
   order, with Laravel's SQL and default alias. `as` gives an alias only between single spaces.
   The function is checked against `count`, `max`, `min`, `sum`, `avg`, `exists` (any case);
   other text throws `IllegalArgumentException`. A nested dot path or an unknown relation throws
   `RelationNotFoundException` when the method is called, and nothing is added to the query. A
   core `Expression` is accepted as the column on the single-relation forms.
2. A constraint closure runs inside the subselect's where group, as `whereHas` does. Its `where`
   clauses, `limit`, `offset` and `groupBy` are carried into the subselect; `select` and
   `orderBy` are not compiled. Any other clause (`distinct`, `join`, `having`, `union`, a later
   clause kind) throws `IllegalStateException` rather than being dropped.
3. Aggregate and existence subqueries correlate on the outer `from` alias when there is one, so
   `from("users as u")` works for `has`, `whereHas` and the aggregates alike.
4. Only `exists` is cast, to `Boolean`, through a per-instance cast overlay (`mergeCasts`) that
   `withCasts` gives to every hydrated model. Other aggregates keep the driver's type. The overlay
   never changes the class casts; `withCasts` merges into a new map on each call.
5. `load*` runs one query per key chunk, selecting the key and the aggregates only, with keys split
   as eager keys are. A constraint whose own values leave no room for a key throws
   `IllegalArgumentException`. A model whose row is gone, or whose key is null, is skipped. Copied
   values are marked original, so the model is not dirty because of them.
6. `touches()` is read and checked once per model class: each name must be a `belongsTo`, `hasOne`
   or `hasMany` relation, else `InvalidRelationException`. A save that changed something, after
   its `saved` hook, and a delete, before its statement, run `touchOwners()`: one raw `UPDATE` per
   name, with no events, casts or `SELECT`. The walk continues only into owners already loaded,
   never loading one; a loaded single owner gets its `saved` hook, a loaded collection's items
   touch their own owners.
7. Touch and `push` keep a per-call done set: each model is walked or saved once per outermost
   call, so diamonds stay linear and many children sharing one loaded owner cost one `UPDATE`. A
   loaded `belongsTo` owner that was already walked, and whose key matches the child's foreign key,
   gets no second `UPDATE`; that includes a back edge to the model the call started from. `hasOne`
   and `hasMany` touches always run their `UPDATE`, because a loaded collection may be partial, and
   an owner that is not loaded is updated once per child.
8. `withoutTouching` and `withoutTouchingOn` hold their class list on the calling thread for the
   length of one call, restored in `finally` and removed when the outermost scope ends. Listed
   classes and their subclasses are not touched; another thread is never affected.
9. `push()` saves the model, returns `false` at once if that fails, then pushes each loaded
   relation value in load order. It sets no foreign key, runs no transaction and inserts a
   `withDefault` placeholder it finds, as Laravel does. Each `save()` inside it starts its own touch
   walk, so its touches are not shared with the push.
10. `chaperone` lives on `hasOne` and `hasMany`. The name is checked against the related model's
    relation map at the call, or guessed in Laravel's order (foreign key less its key suffix, the
    parent's foreign key, the parent's class name, `owner`, `parent` for a self relation). The link
    is set at the read and write sites only, and on eager loads after matching, to each child's
    own parent.
11. Serialization, touching and `push` share one guard type, an identity path passed by parameter,
    entered on the way in and left in `finally`. Serialization keeps path-only semantics: a shared
    model is written in full under each parent.

## Consequences

- No reflection, annotations or keep rules; the features work the same on SQLite, PostgreSQL and
  Android. On Android, R8 renames classes, so the class-name guesses of `chaperone()` fail there;
  pass the name.
- `select` after `withCount` drops the aggregate, as in Laravel; `addSelect` keeps it.
- `load*` fills models with no `retrieved` hook, as Laravel's raw copy does.
- A touched owner in memory keeps its old `updated_at`. A `hasMany` touch updates every child row.
- An owner's `saved` hook that changes and saves it again starts a new walk; a hook that keeps doing
  so in a cycle does not end, as in Laravel.
- Pushing many changed, touching children of one owner updates that owner once per child, as in
  Laravel, because each save walks on its own.
- `push()` without a transaction can leave some rows saved; callers that need all or nothing wrap
  it in one.
- The touch scope is a `ThreadLocal`. It is empty after every call, also on exception, so a pooled
  thread never carries it into the next task; a thread started inside the scope touches.
- A relation instance with `chaperone()` is single-use like any relation; declaring it in the
  relation method keeps it on every use.
- `touchOwners()`, `push()` and `toMap()` recurse once per level of the loaded graph; a very deep
  chain can overflow a small thread stack.

## Alternatives rejected

- Lazy loading touched owners, as Laravel does: hidden queries on every save, and a lazy-loading
  violation from a write.
- A process-wide `withoutTouching` list: one request would switch touching off for another.
- Any function text in `withAggregate`: the text is written into SQL unquoted.
- Dropping unsupported closure clauses silently: the count would differ from what the code says.
- Failing `load*` on a missing row, as Laravel does: a delete racing a page load is normal.
- Skipping `withDefault` placeholders in `push()`: needs a marker on every instance, for a case
  Laravel also inserts.
- Sharing the push's done set with each save's touch walk: needs a second `save` entry point or
  more thread state.
- Linking the inverse through a hook on every query result: on eager loads it links a blank parent
  first.
