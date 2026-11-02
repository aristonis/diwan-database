# Eloquent model requirements

What models must do, observed from outside. The reasons behind the choices are in
[ADR 0015](../adr/0015-eloquent-models.md) and
[ADR 0016](../adr/0016-attributes-casts-and-the-json-codec.md); the detailed contract of each
type is its Javadoc.

## 1. Problem

Applications read and write rows as maps. Laravel developers expect Eloquent models: a class per
table with `find`, `save` and `delete`, dirty tracking and mass-assignment protection. They also
expect casts that read typed values, accessors and mutators, control over what a model serializes,
and a dirty check that knows the cast.

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

Kept as Laravel does it: decimals compare after rounding to the scale, `int` and `bool` compare by
cast value (so `"1.9"` and `1` are equal under `int`), and NaN or Infinity in a value makes
`toJson` throw.

## 6. Scope and non-goals

Not covered now, planned later: encrypted and hashed casts, collection and value-object cast
classes, JSON path writes, cast-aware `increment`, relations, eager
loading, query scopes, soft deletes, model events with a dispatcher and observers, factories.
Also out: `push`, upsert through a model, read and write connections, annotations.

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
