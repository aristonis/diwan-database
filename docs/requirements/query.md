# Query builder requirements

What the query builder must do, observed from outside. The reasons behind the choices are in
[ADR 0013](../adr/0013-query-grammar.md); the detailed contract of each type is its Javadoc.

## 1. Problem

Applications read and write rows by writing SQL strings by hand, and each database (SQLite,
PostgreSQL, Android's SQLite) needs its own quoting, date functions and insert-id rules.

## 2. Goal

A fluent query builder with Laravel's names over the existing `Connection`, producing the same SQL
as Laravel's SQLite and PostgreSQL grammars for the same calls, and returning rows as
`List<Map<String, Object>>`.

## 3. Functional requirements

Entry

- `SQLiteQuery.of(connection)`, `PostgresQuery.of(connection)` or
  `QueryFactory.of(connection, grammar, processor)` give a factory; `table(name)` (optionally with
  an alias, or `"users as u"`) and `query()` give a new builder.

Select and where

- `select`, `addSelect`, `selectRaw`, `distinct`, `from`; no columns means `*`.
- `where` with an operator or an implied `=`, with a map of conditions, or with a nested group;
  `orWhere` and the `not` forms of each; `whereColumn`, `whereRaw`, `whereLike` and
  `whereNotLike` (case-insensitive unless asked), `whereNullSafeEquals`, `whereIn` and
  `whereNotIn`, `whereNull` and `whereNotNull`, `whereBetween`, `whereNotBetween`,
  `whereBetweenColumns`, `whereValueBetween`, `whereDate`, `whereTime`, `whereDay`, `whereMonth`,
  `whereYear`, `whereAll`, `whereAny`, `whereNone`, `whereInstantBetween`.
- A `null` value with `=` (or no operator) is `is null`; with `!=` or `<>` it is `is not null`;
  with any other operator it is refused.
- An operator not in the grammar's list is taken as the value, compared with `=`.
- `whereIn` with an empty list matches nothing; `whereNotIn` with one matches everything. An empty
  nested group adds nothing.
- Date wheres apply to text date and time columns. Instant columns use `whereInstantBetween`, a
  range that includes the start and excludes the end.

Order, group and limit

- `orderBy`, `orderByDesc`, `latest`, `oldest`, `inRandomOrder`, `orderByRaw`, `reorder`; a
  direction other than `asc` or `desc` is refused.
- `groupBy`, `groupByRaw`, `having` and `orHaving`, `havingNested`, `havingNull`,
  `havingNotNull`, `havingBetween`, `havingRaw`.
- `limit`/`take`, `offset`/`skip`, `forPage`, `forPageBeforeId`, `forPageAfterId`. A negative
  limit is ignored and a negative offset is 0.

Read

- `get`, `first`, `find(id)`, `value(column)`, `pluck(column)` and `pluck(column, key)`,
  `exists`, `doesntExist`.
- `count`, `min`, `max`, `sum`, `avg` and `average`. They count every matching row whatever the
  order, limit or offset, and leave the builder as it was.

Write

- `insert` of one row or many; an empty list runs nothing and returns `true`; rows with different
  keys are refused before anything is written.
- `insertGetId(row)` returns the new key (a `Long` when numeric) on PostgreSQL, on SQLite with
  `returning`, and on older SQLite through `last_insert_rowid()` on one session.
- `update(values)` returns the number of rows changed; `increment`, `decrement` (with optional
  extra columns), `incrementEach`, `decrementEach`.
- `delete()` and `delete(id)`; update and delete with a limit change at most that many rows on
  both databases.
- `truncate()` empties the table and resets its id counter.

SQL output and copies

- `toSql()`, `getBindings()` in clause order, `toRawSql()` with values inlined and escaped (for
  debugging only).
- `raw(sql)` values are written into the SQL as given and never bound.
- `clone()` gives a copy that shares no mutable state; `newQuery()` a fresh builder on the same
  connection and grammar.

Values

- Instants are stored as each database stores `Blueprint.instant`: whole microseconds on SQLite,
  `timestamptz` on PostgreSQL. Money is bound explicitly with `toMinor()`, `toText()` or
  `amount()`; a `MoneyAmount` value is refused. Other values follow the connection's binding rules.

## 4. Non-functional requirements

- Generated SQL matches Laravel's grammars for the same calls, apart from the differences listed
  in ADR 0013.
- Every user value goes through a bound parameter; names are always quoted by the grammar.
- No new runtime dependency; Java 17 bytecode and Android 9 (API 28).
- The builder is mutable and not thread-safe.

## 5. Non-goals

Joins, unions, subqueries, `whereExists`, chunking, cursors and pagination; JSON and full-text
wheres; upsert, `insertOrIgnore` and `insertUsing`; dynamic wheres and macros; `dump` and `dd`;
locks; bitwise operators; `distinct on`; a typed row object; Eloquent models; MySQL and SQL Server.
