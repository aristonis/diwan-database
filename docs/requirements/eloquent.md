# Eloquent model requirements

What models must do, observed from outside. The reasons behind the choices are in
[ADR 0015](../adr/0015-eloquent-models.md) and
[ADR 0016](../adr/0016-attributes-casts-and-the-json-codec.md) and
[ADR 0017](../adr/0017-relations.md) and
[ADR 0018](../adr/0018-relations-s2.md); the detailed contract of each type is its Javadoc.

## 1. Problem

Applications read and write rows as maps. Laravel developers expect Eloquent models: a class per
table with `find`, `save` and `delete`, dirty tracking and mass-assignment protection. They also
expect casts that read typed values, accessors and mutators, control over what a model serializes,
a dirty check that knows the cast, and relations between models that load lazily or eagerly,
with counts and sums of related rows, parents touched when a child changes, a save that walks the
loaded graph and children that point back to their parent.

## 2. Goal

Models with Eloquent's names and behavior on SQLite, PostgreSQL and Android, built on the query
builder, without reflection, annotations or build plugins.

## 3. Functional requirements

1. A separate module holds models, so query-builder-only apps do not depend on it.
2. A model class needs only a static `ModelQuery` entry built from a constructor reference.
3. A model is configured by overridable methods: table, primary key, key type, incrementing,
   connection name, timestamps and their columns, fillable, guarded, page size, date format,
   timestamp format and unique id kind. The default table is the snake-case plural of the class
   name; the default foreign key is the snake-case class name plus `_id`.
4. A model finds its connection through a bound factory, a resolver by connection name, or a
   process-wide default resolver, and can be bound to a transaction's connection. Tests need no
   global state.
5. Attributes: get, set, typed getters with lenient conversion, originals, dirty and clean checks,
   changes after save, previous values, sync and discard.
6. Mass assignment: fillable and guarded lists, `fill`, `forceFill`, `unguarded`, and a switch
   that refuses silently dropped keys, with Laravel's exception messages.
7. Lifecycle: `save` inserts a new model or updates only dirty columns by the original key and runs
   nothing when clean; `saveOrFail`, `update`, `delete`, `destroy`, `increment`, `decrement`,
   `refresh`, `fresh`, `replicate`, `is`, `isNot`, `touch`.
8. Timestamps are set on insert and update, a column set to `null` is not written, the clock is
   injectable, and `withoutTimestamps` turns them off for a callback. They are Laravel's UTC text
   by default and instants on request.
9. String keys can be generated as UUID version 7 or ULID on insert; a key already set is kept.
10. The model builder forwards the query builder's common clauses and returns models from finders
    (`find`, `findMany`, `findOrFail`, `first`, `firstOrFail`, `sole`, `firstWhere`, `value`),
    creators (`create`, `forceCreate`, `firstOrNew`, `firstOrCreate`, `updateOrCreate`), chunking,
    lazy reads, cursors and the three page kinds. Builder writes add `updated_at`.
11. A missing model throws `ModelNotFoundException` with Laravel's message, the model class and
    the ids.
12. Before and after hooks for saving, creating, updating, deleting and retrieval; a before hook
    returning `false` aborts with no SQL.
13. Casts are declared per attribute by an overridable method, in Laravel's string form or by
    typed factories, or by a user cast object. Built-in casts: `int`/`integer`,
    `real`/`float`/`double`, `decimal:N`, `string`, `bool`/`boolean`, `date`, `datetime`,
    `immutable_date`, `immutable_datetime`, the same four with a `:format` argument, `timestamp`,
    `json`, `array`, `json:unicode` and enums. An invalid cast fails when the model first resolves
    its casts, naming the model and the key.
14. Reading an attribute applies, in order, its accessor, its cast, or nothing. Writing applies its
    mutator, its cast, or nothing, and always stores the storage form. Typed getters and
    `getOriginal` see cast values; the raw attributes, raw originals, dirty map and changes stay in
    storage form. Hydration from the database never casts.
15. A cast may write several columns, may serialize its value differently, and may decide its own
    equality.
16. Bad stored data (text that is not a number, a boolean word other than the accepted ones,
    broken JSON, an unknown enum value) throws an exception naming the model, the key and the cast.
    The message never carries the value.
17. The dirty check compares by meaning: JSON by structure, floats within a few units in the last
    place with NaN equal to NaN, moments as instants, dates as dates, decimals after rounding, and
    other casts by their cast values. It never throws.
18. Reading a cast value never makes a model dirty. Changing an object a cast returned (a JSON map)
    is seen by the dirty check and written on save.
19. Accessors and mutators are registered explicitly. An accessor may compute a value from other
    columns; a mutator may write several columns. A cached accessor value is cleared by any write.
20. `toMap()` and `toJson()` apply `visible`, then `hidden`, then `appends`, with per-instance
    changes. Moments serialize as ISO-8601 UTC with microseconds, unless a cast gives a format.
21. JSON is read and written through a codec the model chooses. The built-in codec is strict
    RFC 8259 and keeps numbers exact; Jackson or Gson can replace it without a dependency in the
    library.
22. Relations: one-to-one, one-to-many and its inverse, declared by a typed method and a name map
    checked once per model class. A typo or a bad declaration fails at first use, naming the model
    and the relation.
23. A relation is read lazily on first access and kept on the model. Relations can be eager
    loaded on a query, on one model or on a collection, with nested paths, selected columns and
    constraints, loading only what is missing on request.
24. Eager loading drops null keys, queries each key once, and splits large key lists so no query
    passes the database's parameter or statement limits. A constraint with a limit or offset is
    refused.
25. Queries can filter by the existence or count of related rows, with a constraint, nested paths
    and self relations, through Laravel's `has`, `whereHas`, `whereRelation` family.
26. Writes through a relation set the foreign key: make, save and create one or many children,
    associate and dissociate a parent. A write from a parent without a key is refused before any
    SQL.
27. A one-to-one or inverse relation can return a default model when nothing is found.
28. `toMap()` and `toJson()` include loaded relations and stop on cycles.
29. Lazy loading can be refused for models that came from a multi-row result, process-wide.
30. A query can add relation aggregates as columns: `withCount`, `withMax`, `withMin`, `withSum`,
    `withAvg`, `withExists` and `withAggregate`, one subselect per relation, with Laravel's default
    aliases, an `as` alias, a constraint closure and self relations. The function is one of
    `count`, `max`, `min`, `sum`, `avg`, `exists`; a nested dot path or an unknown relation is
    refused when the method is called.
31. `exists` aggregates read as booleans on every database; the other aggregates keep the type the
    driver returns.
32. Models already loaded can get the same aggregates with `loadCount` and the other `load*`
    methods, on a collection or one model: one query per key chunk, only the key and the aggregates
    selected, models left clean, and a model whose row is gone or whose key is null skipped.
33. A model instance can take extra casts (`mergeCasts`) and a query can give extra casts to every
    model it returns (`withCasts`), without changing the class casts or other instances.
34. A model can name relations in `touches()`; saving a changed model or deleting it updates the
    owners' `updated_at` with one `UPDATE` per relation and no `SELECT`, and continues through
    owners that are already loaded. Touching can be turned off for named classes for one call on
    the calling thread.
35. `push()` saves a model and then every loaded relation under it, depth first, and stops at the
    first save that returns `false`.
36. A one-to-one or one-to-many relation can set its parent on each child it returns or writes
    (`chaperone`), guessing the inverse name or taking it, without a query.
37. Serialization, touching and `push` all stop on cycles in the loaded graph.

## 4. Non-functional requirements

1. The SQL equals what the query builder produces for the same Laravel model calls.
2. No reflection for instantiation, no new dependency, runs on Android API 28.
3. Line coverage of at least 80%; tests pass on JDK 17, 21 and 25 and on Android.

## 5. Differences from Laravel

| Area | Laravel | Diwan | Why |
|---|---|---|---|
| JSON dirty check | Compared by text: key order and `1` against `1.0` make it dirty | Compared by structure | PostgreSQL `jsonb` reorders keys, so Laravel issues needless updates |
| Float dirty check | Equal within an absolute epsilon; NaN and Infinity never equal | NaN equals NaN, Infinity equals Infinity, otherwise within 4 ULP of the larger value | An absolute epsilon hides small real changes and flags large equal ones |
| Datetime dirty check | Compared as formatted text in the model's zone, to the second | Compared as instants, at the precision stored | The same wall clock in another zone was clean; sub-second changes were lost |
| Date dirty check | Read as start of day, compared against the full stored datetime | Compared as dates | A time-of-day change was dirty though both reads were equal |
| Formatted datetime and `timestamp` writes | Leave a date object in the attributes | Always store the storage form | Attributes, dirty map and serialized form agree before and after save |
| Cast name case | `DateTime:Y-m-d` lower-cased whole, then refused | Type lower-cased, format kept | The format is case-sensitive |
| Reading a class cast | Re-serializing a read object can mark the model dirty | Reading alone never dirties | A read must not cause a write |
| Accessor cache | Kept after the columns it depends on change; can be written back on save | Cleared on any write | Stale computed values |
| `decimal:N` | A string, not rounded on write | `BigDecimal`, rounded half up on write | Typed value; the stored text is what is compared |
| `date` | Stores a full datetime | Stores `yyyy-MM-dd` | The honest form for a date column |
| Bad stored data | Broken JSON reads `null`, `"abc"` reads `0`, `"false"` reads `true` | Throws | Silent data loss |
| Date formats | PHP letters (`Y-m-d H:i:s`) | Java `DateTimeFormatter` patterns (`yyyy-MM-dd HH:mm:ss`) | No translation layer. A PHP string such as `Y-m-d` compiles in Java with another meaning |
| Accessor discovery | `getFooAttribute` found by name | Registered in `accessors()` | No reflection |
| `immutable_*` casts | Differ from the mutable ones | Aliases | `java.time` is immutable |
| Incrementing key | Implicitly cast to `int` | Not cast | Key comparison already treats numbers by value |
| Relation lookup | Method found by name | Typed method plus a `relations()` name map | No reflection |
| Relation constraints | Added in the constructor unless a static switch is off | Added by the lazy read paths only | Thread safety |
| Self-join alias | Counter shared by the whole process | Counter per outermost query | The same query always gives the same SQL |
| Null parent keys in eager loads | Reach the `in` list; an integer list reads them as `0` | Dropped | Orphan rows were fetched |
| Bound eager keys | One `in` list of any size | Split per database limit | SQLite allows 999 parameters, PostgreSQL 65,535 |
| Integer `in` list values | `"abc"` reads `0`, `1.9` reads `1` | Exact or refused | Silent wrong matches |
| Write through a relation from a parent without a key | Writes a null foreign key | Refused | Silent orphan rows |
| `getAttribute("posts")` | Lazy loads the relation | Attributes only; `getRelationValue`, `related`, `relatedMany` | No surprise queries |
| Eager constraint with `limit` | Per-parent window query | Refused | SQLite on Android 9 has no window functions |
| `belongsTo` foreign key | Guessed from the calling method name | Required | Java has no backtrace naming |
| Lazy loading violation | Replaceable callback | Exception only | Smaller surface |
| `with("posts:id,title")` without the foreign key | Matches nothing | The key column is added to the select | A silent empty result |
| `with` the same relation twice | Last constraint wins | Both constraints run, first one first | The constraint map merges instead of replacing |
| `whereHas` constraint | Grouped only when it contains an `or` | Always grouped; nothing added when empty | One rule; same rows |
| `cursor()` with eager loads | Eager loads ignored | Throws; use `lazy()` | A silent missing relation |
| Bound eager key type | Keys passed as read | The parent's Java type is kept (first value seen per key); matching treats `123` and `"123"` alike | PostgreSQL refuses `varchar = bigint` |
| Internal access holders | None; PHP has no package privacy | Public classes in an `internal` package, not API, must not be called | No reflection and no `module-info` to hide them |
| `preventLazyLoading` | Per process | Per process, a volatile flag; set once at startup, not from parallel tests | Same scope, but Java tests share one JVM |
| `touchOwners` | Reads each touched relation, lazy loading an unloaded owner | Updates the owner row always; continues only into owners already loaded | No hidden `SELECT` and no lazy-loading violation from a save |
| `touchOwners` on a shared owner | Walked again for every child and path | Each model walked once per outermost call; a loaded `belongsTo` owner already walked gets no second `UPDATE` | Fan-out of many children to one owner costs one `UPDATE` |
| `withoutTouching` | A process-wide static list | Per call, held per thread, restored when the call ends | Another request is never affected |
| `touches()` names | Not checked | Checked once per class; a bad name or relation type throws `InvalidRelationException` | A typo is found at first use |
| `loadAggregate` on a deleted row or an empty result | Fatal error | The model is skipped | A normal race must not crash |
| `loadAggregate` key list | One `in` list of any size | Split as eager keys are | SQLite allows 999 parameters |
| Aggregate over `posts.comments` | Adds `posts_count`, then fails | `RelationNotFoundException` before anything changes | Clear error, no half-built query |
| `withAggregate` function | Any text, written into SQL | `count`, `max`, `min`, `sum`, `avg`, `exists` in any case | The text is not quoted |
| Aggregate constraint closure | Any clause | `where`, `limit`, `offset` and `groupBy` apply; `select` and `orderBy` are ignored; `distinct`, `join`, `having`, `union` and other clauses throw | A clause the subselect cannot keep is not dropped silently |
| Aggregate or existence query on an aliased `from` (`users as u`) | Correlates on the table name, which the alias hid | Correlates on the alias | Laravel's SQL fails or matches the wrong rows |
| `push()` on a shared model | Saved again on every path | Saved once per call | Linear in the size of the graph |
| Inverse on eager loads | Linked twice, first to a blank parent | Linked once, to the matched parent | No wasted work |
| Clauses chained inside a relation factory | Applied after the constraints the constructor added | Recorded and replayed after the lazy constraints; eager loads apply them to the key query; `has` takes their wheres | Constraints are added by the lazy read paths only |

Kept as Laravel does it, for relations: a one-to-one relation takes the first row in database
order, a relation loaded as null is not retried by `loadMissing`, an `or` inside an eager
constraint is not grouped, writes through a relation run without a transaction, and saving a child
does not set the relation on the parent.

Kept as Laravel does it, for aggregates, touches and `push`: `select` after `withCount` replaces
the column list and drops the aggregate, while `addSelect` keeps it; a dot in the aggregated column
is dropped from the default alias (`posts.votes` gives `posts_max_postsvotes`); `as` is an alias
only between single spaces; `count`, `sum` and the others keep the driver's types; a touched owner
held in memory keeps its old `updated_at`; touching raises no events on the touched rows; `push()`
sets no foreign key, runs no transaction and inserts a `withDefault` placeholder it finds among the
loaded relations; every `save()` inside `push()` runs its own touches, so pushing many touching
children of one owner updates that owner once per child.

Kept as Laravel does it: decimals compare after rounding to the scale, `int` and `bool` compare by
cast value (so `"1.9"` and `1` are equal under `int`), and NaN or Infinity in a value makes
`toJson` throw.

## 6. Scope and non-goals

Not covered now, planned later: encrypted and hashed casts, collection and value-object cast
classes, JSON path writes, cast-aware `increment`, many-to-many and through relations, polymorphic
relations and their aggregates, touches and inverses, per-parent limits in eager loads,
`pushQuietly` and `touchQuietly`, query scopes, soft deletes, model events with a dispatcher and
observers, factories.
Also out: upsert through a model, read and write connections, annotations.

## 7. Acceptance criteria

1. A model with only its `query` entry and a table override does create, read, update and delete
   on SQLite, PostgreSQL and Android.
2. `save` inserts new models with the generated id and timestamps, updates only dirty columns,
   and runs nothing when clean.
3. Guarded keys are refused with Laravel's messages; `forceFill` bypasses the guards.
4. Dirty, original and changes behave as Laravel after set, save, refresh and discard.
5. Finders and creators return models; `findOrFail`, `firstOrFail` and `sole` throw
   `ModelNotFoundException` with the right message.
6. Chunks, lazy reads, cursors and pages yield models.
7. UUID and ULID keys are generated on insert.
8. A model with every built-in cast reads and writes typed values on SQLite, PostgreSQL and
   Android.
9. Each difference in section 5 has a test showing the Diwan behavior.
10. Reading a JSON or class-cast attribute leaves the model clean.
11. Accessors and mutators registered by lambdas work without reflection, accessor before cast.
12. `toMap` and `toJson` apply visible, hidden and appends in Laravel's order, with ISO-8601 UTC
    dates.
13. A model that overrides `jsonCodec()` uses that codec.
14. Users, posts and comments load lazily, eagerly (nested, constrained, chunked) and through
    `whereHas` on SQLite, PostgreSQL and Android, with one query per relation level.
15. Eager loading over more keys than one statement allows gives the same models as one query.
16. A relation declared wrongly fails at first use with an exception naming the model and the
    relation; an unknown name lists the known ones.
17. With lazy loading prevented, a lazy read on a model from a multi-row result throws, and one
    from a single-row result, a new model or an eager load does not.
18. `withCount` and the other aggregates give Laravel's SQL and aliases on SQLite and PostgreSQL
    for one-to-one, one-to-many and inverse relations, `withExists` reads as a boolean, and a self
    relation gives the same SQL on every build.
19. `loadCount` over 2,000 string keys runs three queries on SQLite and one on PostgreSQL, skips a
    deleted row and leaves the models clean.
20. Saving or deleting a child with `touches()` updates its owner with one `UPDATE` and no `SELECT`;
    a clean or vetoed save touches nothing; `withoutTouching` on one thread does not affect another.
21. `push()` saves self first, then loaded relations in order, and stops at the first failure.
22. `chaperone()` links children to the same parent instance with no query, and `toJson()` of the
    linked graph ends.
