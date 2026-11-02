# Eloquent model requirements

What models must do, observed from outside. The reasons behind the choices are in
[ADR 0015](../adr/0015-eloquent-models.md); the detailed contract of each type is its Javadoc.

## 1. Problem

Applications read and write rows as maps. Laravel developers expect Eloquent models: a class per
table with `find`, `save` and `delete`, dirty tracking and mass-assignment protection.

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

## 4. Non-functional requirements

1. The SQL equals what the query builder produces for the same Laravel model calls.
2. No reflection for instantiation, no new dependency, runs on Android API 28.
3. Line coverage of at least 80%; tests pass on JDK 17, 21 and 25 and on Android.

## 5. Scope and non-goals

Not covered now, planned later: attribute casts, accessors and mutators, relations, eager
loading, query scopes, soft deletes, model events with a dispatcher and observers, factories.
Also out: `push`, upsert through a model, read and write connections, annotations.

## 6. Acceptance criteria

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
