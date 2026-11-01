# 0013. Query grammar per database module

## Status

Accepted

## Context

Laravel's query builder collects clauses in PHP arrays and asks the connection's grammar for SQL.
Diwan Database ships one module per database and already passes the schema grammar in instead of
finding it ([ADR 0008](0008-schema-grammar-per-database-module.md)), so the core `Connection`
cannot offer `table()` on its own. PHP arrays, closures with a variable argument count and string
dispatch have no direct Java form. Moments and money also need a storage rule, and SQLite before
3.35 (shipped with many Android versions) has no `returning` clause.

## Decision

1. The core holds `QueryFactory`, `Builder`, the abstract `QueryGrammar`, `Processor`,
   `Expression`, the `Where` and `Having` clause types, `Order`, `Aggregate` and `BindingType`. Each
   database module holds its grammar and processor: `SQLiteQueryGrammar` and `SQLiteProcessor`
   (also used on Android), `PostgresQueryGrammar` and `PostgresProcessor`.
2. The application passes the grammar in: `QueryFactory.of(connection, grammar, processor)`, or a
   module shortcut, `SQLiteQuery.of(connection)` or `PostgresQuery.of(connection)`. Builders come
   from `factory.table(name)` and `factory.query()`. No registry and no service loading. The
   factory refuses a grammar whose table prefix differs from the connection's.
3. A where clause is one immutable final class per kind behind the `Where` interface; records and
   sealed types are not used, so the jar stays usable on Android 9 (ADR 0006). The grammar compiles
   each kind in its own method, in Laravel's fixed component order, without reflection.
4. Bindings live in buckets named by `BindingType`, declared in Laravel's full order: select,
   from, join, where, groupBy, having, order, union, unionOrder. `getBindings()` flattens them in
   that order, so values line up with placeholders whatever order the calls were made in. The join
   and union buckets are reserved and stay empty until joins and unions exist; a test pins the
   order so it cannot drift.
5. Read helpers (`first`, `find`, `value`, `pluck`) and aggregates run on a copy. The caller's
   builder keeps its columns, orders, limit and bindings. Without havings the copy drops columns,
   limit, offset and (unless grouped) orders; with havings the query is wrapped as a subquery, as
   Laravel does.
6. The grammar's `prepareBindings` converts `Instant` values on the way to the database: whole
   microseconds since the epoch on SQLite, unchanged on PostgreSQL, matching how
   `Blueprint.instant` stores them. It applies at execution and in `toRawSql`. Money is not
   converted: the builder does not know whether a column holds minor units, checked text or a
   decimal, so a `MoneyAmount` is refused and the caller binds `toMinor()`, `toText()` or
   `amount()`.
7. `insertGetId` asks the processor. PostgreSQL and SQLite 3.35 and later use
   `insert ... returning`. Older SQLite runs the insert and `select last_insert_rowid()` inside one
   transaction, so both use the same session; versions are compared as numbers. That path returns
   the rowid, so a table whose key is not an integer rowid needs SQLite 3.35 or later.
8. Each grammar has an operator allowlist, written in lower case. An operator is matched without
   letter case and written in the list's spelling. A string that is not in the list is treated as
   the value with `=`. `whereColumn` and `having` check operators against the same list. Sort
   direction must be `asc` or `desc`.
9. Values only ever reach SQL as `?`. Names always go through the grammar's quoting, with embedded
   quotes doubled. `Expression` and the `*Raw` methods are trusted SQL, written as given.

### Known differences from Laravel

| Difference | Reason |
|---|---|
| Entry is `SQLiteQuery.of(connection).table(...)`, not `connection.table(...)` | The core cannot find a grammar (rule 2) |
| Operators are written in lower case (`LIKE` becomes `like`) | One allowlist spelling; same meaning |
| `get()` returns `List<Map<String, Object>>`, not a collection of objects | Same shape as `Connection.select` |
| On SQLite, case-sensitive `whereLike` compiles to `glob` with the pattern translated in SQL | No PHP-side binding hook; same rows, different SQL text |
| PostgreSQL offers no operators containing `?` | They collide with JDBC placeholders |
| PostgreSQL `truncate` never cascades | No global flag; cascading must be explicit |
| `whereInstantBetween` added; date wheres are for text date columns only | SQLite date functions misread epoch microseconds |
| Stricter values: a single value must be a scalar, between needs exactly two, rows with different keys and empty updates are refused, increments must be finite numbers | Errors before SQL instead of wrong SQL |
| Money is never converted by the builder | Storage shape is per column |
| No dynamic wheres, macros, `dump` or `dd` | PHP-only mechanisms |

## Consequences

- A database module adds a grammar and processor without touching the core.
- Callers write one extra call (the module shortcut) compared with Laravel's facade.
- Adding joins and unions later fills reserved buckets; existing binding order does not change.
- Golden SQL tests differ from Laravel's text where the table above says so.
- The builder is mutable and not thread-safe, as in Laravel; `clone()` gives an independent copy.

## Alternatives rejected

- `connection.table()` through a grammar registry: needs classpath scanning or a global registry,
  which ADR 0008 rejects for Android and code shrinking.
- Converting money by column metadata: the builder has no schema; guessing would round or
  mis-store.
- Running `last_insert_rowid()` on a pooled connection without a transaction: the second call may
  land on another session and return another row's id.
- Echoing the caller's operator text after an allowlist check: two spellings of one operator in
  generated SQL, for no gain.
