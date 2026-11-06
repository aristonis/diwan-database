# 0013. Query grammar per database module

## Status

Accepted

## Context

Laravel's query builder collects clauses in PHP arrays and asks the connection's grammar for SQL.
Diwan Database ships one module per database and already passes the schema grammar in instead of
finding it ([ADR 0008](0008-schema-grammar-per-database-module.md)), so the core `Connection`
cannot offer `table()` on its own. PHP arrays, closures with a variable argument count and string
dispatch have no direct Java form. Moments also need a storage rule, and SQLite before
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
   decimal. Diwan ships no money type; store money in a `decimal` column or an integer column of
   minor units.
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
| Money is not converted by the builder; Diwan ships no money type | Storage shape is per column |
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
- Running `last_insert_rowid()` on a pooled connection without a transaction: the second call may
  land on another session and return another row's id.
- Echoing the caller's operator text after an allowlist check: two spellings of one operator in
  generated SQL, for no gain.

## Addendum: joins, unions and subqueries

Status: Accepted. This addendum extends the decision above; rule 4's reserved join and union
buckets are now filled as described here.

### Context

Reports and application screens need joins, subqueries and unions. Laravel stores a subquery's
bindings when it is attached but compiles its SQL only when the outer query compiles, so a
subquery changed after attaching yields SQL and values that no longer match. PHP mixes many
traits into one builder class; Java has no mixins, and one class holding every method passes 800
lines.

### Decision

1. Attach-time freeze. A subquery given to any slot (select, from, join, where, order, union,
   update value, `insertUsing`) is copied when it is attached, and its bindings are taken from that
   copy into the bucket of the clause that holds it. The copy never changes, so the SQL compiled
   later always matches its bindings. A consumer form fills a fresh plain builder on the same
   connection and grammar. `clone()` shares these frozen copies and copies only the top-level lists.
2. Binding order. The buckets keep Laravel's order: select, from, join, where, groupBy, having,
   order, union, unionOrder. A join's own where values go to the join bucket. One golden test puts
   a subquery in every slot and checks the values line up with the placeholders on both grammars
   and both databases.
3. Joins. `JoinClause` is a `Builder` subclass, as in Laravel: `on` compares columns, `where` binds
   a value. Join types are inner, left, right and cross. An empty nested `on` group is skipped.
   The covariant `JoinClause` return covers `on`, `orOn`, `where`, `orWhere`, `whereNull` and
   `whereIn`; other where forms return `Builder`.
4. Union wrap per dialect. After the first union, `orderBy`, `limit` and `offset` apply to the
   whole union. SQLite wraps each part as `select * from (...)`, so a part may keep its own order
   and limit; PostgreSQL wraps each part in parentheses. `count()` and the other aggregates over a
   union, or over a query with havings, wrap the query as `temp_table`. `exists()` wraps the union.
5. Writes with joins. An update or delete on a joined query is compiled from a copy, so the
   caller's builder is unchanged: SQLite writes `rowid in (select alias.rowid ...)`, PostgreSQL
   `ctid in (select ...)`. Update bindings are the set values (a subquery value in its column's
   place), then join, then where, then the rest except select.
6. The base grammar refuses an update or delete with joins and `insertOrIgnoreUsing`; only a
   database grammar knows how to write them.
7. A write needs a table. An insert, update, delete or truncate on a query that reads from a
   subquery or raw SQL, or has no table, is refused before any SQL is compiled.
8. The builder is split into package-private abstract layers with `Builder` as the public leaf,
   and the query grammar the same way. Public methods are `final`, so the layers are not an
   extension point.

### Limits

- A right join needs SQLite 3.39 or later. Older SQLite, as shipped with Android 9 to 13, reports
  its own error, which passes through as `QueryException`.
- An update or delete with a join on a SQLite table created `without rowid` fails with SQLite's
  error.
- Ordering a whole union by a subquery is refused by both databases, since a compound query may
  only be ordered by a result column.

### Consequences

- Subqueries can be built once and reused; later changes to the caller's builder never leak in.
- Javadoc links to methods declared in a package-private layer render as plain text in the
  published HTML.

### Alternatives rejected

- Snapshot bindings at attach and compile SQL later, as Laravel does: SQL and values can drift.
- Thin delegate methods instead of layers: every public method would still be documented in
  `Builder`, which stays over 800 lines.
- A self-typed `Builder<B>`: `JoinClause` extends `Builder`, which would already have fixed the
  type parameter, so it could not narrow it.
- Detecting `without rowid` tables or the SQLite version on every write: needs a schema or
  version read per call.
