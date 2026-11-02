# 0015. Eloquent models

## Status

Accepted

## Context

Applications read and write rows as maps through the query builder. Laravel developers expect
Eloquent: a class per table with `find`, `save` and `delete`, dirty tracking and mass-assignment
protection. Eloquent leans on PHP features Java lacks: static calls resolved at runtime
(`User::find(1)`), `new static` from a parent class, magic properties and a global container that
holds the connections. The same jar must run on Android API 28, where R8 removes annotations and
reflective constructors unless the app writes keep rules, and where bytecode enhancement needs a
build plugin.

## Decision

1. Models live in their own module, `diwan-database-eloquent`, package
   `org.aristonis.diwan.database.eloquent`. Apps that use only the query builder do not pull it in.
2. Static entry without reflection. Each model declares
   `public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);`. The
   constructor reference builds every instance; nothing is created by reflection or enhanced at
   build time, so the module works on Android without keep rules or plugins.
3. Configuration by overridable methods, not annotations: `table`, `primaryKey`, `keyType`,
   `incrementing`, `connectionName`, `timestamps`, `createdAtColumn`, `updatedAtColumn`,
   `fillable`, `guarded`, `perPage`, `dateFormat`, `timestampFormat`, `uniqueIdKind`. R8 cannot
   strip a method the code calls.
4. `Model` is not generic. `ModelQuery<T>` and `EloquentBuilder<T>` carry the type, so finders
   return `T`; fluent instance methods return `Model`. A generic `Model<T>` would put a type
   parameter on every user class, and adding one later would break them all.
5. Layers. `Model` stacks configuration, attributes, guards and persistence in package-private
   base classes. `ModelQuery<T>` is the entry, `EloquentBuilder<T>` wraps the query `Builder` and
   turns rows into models, `ModelCollection<T>` is a read-only list with key helpers, and
   `ModelPage`, `SimpleModelPage`, `CursorModelPage` and `ModelCursor` wrap the query pages and
   cursor.
6. Forwarders. `EloquentBuilder` forwards the common `where`, order, limit, paging and select
   methods by name. Everything else is reached through `query(Consumer<Builder>)` or `toBase()`. A
   test lists every public clause of `Builder` and fails when one is neither forwarded nor named
   as omitted.
7. Connections. A model finds its `QueryFactory` (connection, grammar and processor) through
   `ModelQuery.on(QueryFactory)`, through `using(ConnectionResolver)` by its `connectionName`, or
   through a process-wide default resolver installed with `ConnectionResolver.setDefault`, which
   returns a handle that restores the previous default. No connection gives a clear
   `IllegalStateException`. `ModelQuery.on(Connection)` binds the models to a transaction's
   connection and keeps the grammar and processor of the factory they use now.
8. Timestamps. By default `created_at` and `updated_at` are written as Laravel writes them: UTC
   text `yyyy-MM-dd HH:mm:ss` (`dateFormat` can change it), which fits `Blueprint.timestamps()`
   columns on every database. A model can return `TimestampFormat.INSTANT` to write `Instant`
   values through the query layer, for tables built with instant columns. `getInstant` reads both.
   The clock is injectable and truncated to microseconds.
9. Dirty rules. A key missing from the original is dirty. `null` equals only `null`. Numbers and
   numeric strings compare by canonical text, so `1`, `1L`, `"1"` and `1.0` are equal and `"1.0"`
   against `1` is dirty, as Laravel's string compare says. Moments compare as instants whatever
   their stored form; a value that cannot be read as one is compared as is. Byte arrays compare by
   content. Anything else uses `equals`. The check never throws.
10. Mass assignment follows Laravel: `guarded` is `["*"]` and `fillable` is empty, so nothing is
    mass assignable until the model allows it. `fill` drops keys it may not set, and throws
    `MassAssignmentException` with Laravel's message when the model is totally guarded or prevents
    silent discards. `forceFill` and `unguarded` bypass the guards.
11. Divergences from Laravel, where Laravel's behavior is a trap:
    - `delete()` on a model that is not in the database returns `false` without SQL (Laravel
      returns `null`).
    - `increment` and `decrement` on such a model are refused (Laravel runs the update with no key
      and changes every row).
    - `saveOrFail()` runs in a transaction and throws `IllegalStateException` when a
      non-incrementing model has nothing to insert. `save()` keeps Laravel's `true` with no row
      written.
    - Laravel checks a guarded model's keys against the table's column listing before filling. That
      check is not done: it costs a query per model class and the guard lists already decide.
    - The default table name is the snake case of the class name pluralized by a small English rule
      set with a short irregular list, not Laravel's full inflector. Anything else overrides
      `table()`.
12. Model events are protected hook methods (`saving`, `creating`, `updating`, `deleting` return
    `boolean`, `false` aborts with no SQL; `saved`, `created`, `updated`, `deleted`, `retrieved`
    return nothing). A dispatcher can be added beside them later without changing their names.

## Consequences

- One line of boilerplate per model (the `query` field) replaces Laravel's static magic, and the
  same model class runs on JDBC and Android.
- SQL is the query builder's SQL for the same calls; models add no dialect code.
- Models are not thread-safe. Share the `ModelQuery`, not model instances.
- Without casts, a `Boolean` set on a column SQLite stores as `1` stays dirty.
- A `ModelCursor` left open keeps its connection, as a `RowCursor` does.

## Alternatives rejected

- Reflection (`Class.newInstance`) or bytecode enhancement for `User.find(1)`: breaks under R8
  without keep rules and needs a build plugin.
- Annotations (`@Table`, `@Fillable`): stripped by R8, and a second way to say what a method says.
- A generic `Model<T extends Model<T>>`: noisy in every class and fixed forever once released.
- Forwarding every `Builder` method: a large surface that drifts; the escape hatch plus a drift
  test keeps it honest.
- Storing timestamps as instants by default: does not fit the text columns Laravel's
  `timestamps()` creates, and breaks reading tables Laravel apps wrote.
- A global connection container as the only path: hides state from tests and transactions.
