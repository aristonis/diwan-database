# Query builder requirements

What the query builder must do, observed from outside. The reasons behind the choices are in
[ADR 0013](../adr/0013-query-grammar.md) and, for chunking, cursors and pagination,
[ADR 0014](../adr/0014-cursors-and-pagination.md);
the detailed contract of each type is its Javadoc.

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

Joins

- `join`, `leftJoin`, `rightJoin` and `crossJoin` with a column comparison or a join clause built
  by a consumer; `joinWhere` and its left and right forms compare with a bound value; `joinSub`,
  `leftJoinSub`, `rightJoinSub` and `crossJoinSub` join a subquery under an alias. A table may
  carry an `as` alias.
- A join clause offers `on`, `orOn`, nested `on` groups and every where form; a `null` value in a
  join where is `is null`; an empty nested group adds nothing.
- A right join needs SQLite 3.39 or later; on older SQLite the database's error passes through.

Subqueries

- A subquery is a builder, a consumer that fills a fresh builder, or (for `fromSub`, `joinSub`
  and `selectSub`) raw SQL text.
- `fromSub`, `fromRaw`, `from(query, alias)`, `selectSub`, `addSelect(alias, query)`; `whereIn`
  and `whereNotIn` with a subquery; `whereExists`, `whereNotExists` and their `or` forms; a column
  compared with a subquery and a subquery compared with a value; `whereRowValues`, which refuses
  different column and value counts; `orderBy` a subquery.
- A subquery is copied when attached: changing it afterwards changes neither the SQL nor the
  values of the outer query.
- Values come out in the same order as the placeholders, whichever slots hold subqueries.

Unions

- `union` and `unionAll` with a builder or a consumer. After the first union, `orderBy`, `limit`
  and `offset` apply to the whole union; on SQLite each part may also keep its own order and limit.
- `count` and the other aggregates over a union, or over a query with havings, count the combined
  rows; `exists` works on a union.
- Ordering a whole union by a subquery is refused by both databases.

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
- `insertUsing(columns, query)` copies the rows a subquery returns; `insertOrIgnoreUsing` skips
  rows that break a unique key (SQLite `insert or ignore`, PostgreSQL `on conflict do nothing`).
- `update` accepts a subquery as a value, written as `(select ...)`.
- Update and delete on a joined query change only the joined rows and leave the builder unchanged.
  On SQLite this needs a table with a rowid; a `without rowid` table fails with SQLite's error.
- A write on a query that reads from a subquery or raw SQL, or has no table, is refused before
  anything runs.

Chunking, cursors and pagination

- `chunk(count, callback)` reads pages of `count` rows and stops when the callback returns
  `false`; `each` does the same per row; `chunkMap` collects the mapped rows. They need an order
  (refused with Laravel's message otherwise), honor the query's limit and offset, and leave the
  builder unchanged. A count below 1 is refused.
- `chunkById`, `chunkByIdDesc` and `eachById` page by key (default `id`, optionally a qualified
  column and the alias read from the rows); a page whose last row lacks the key is refused. The
  query's limit is honored and its offset applies to the first page only.
- `lazy`, `lazyById` and `lazyByIdDesc` return an iterable that loads one page per step and holds
  no connection between pages.
- `cursor()` streams rows on SQLite, PostgreSQL and Android, inside or outside a transaction. It
  is iterated once, closes itself when exhausted and gives its connection back when closed. While
  it is open in a session or transaction, any other statement there is refused.
- `paginate(perPage, page)` returns the page's rows, the total and the last page; a total of 0
  runs no select. The total counts groups for a grouped query, combined rows for a union and
  distinct rows for a distinct query. `simplePaginate` reads one extra row to know whether more
  follow. `getCountForPagination()` is public.
- `cursorPaginate(perPage, cursor)` pages by the query's order columns (one or more, aliases
  resolved) forward and back; the next and previous cursors encode to a text token and decode
  back. A malformed token, or one for other columns, is refused.

SQL output and copies

- `toSql()`, `getBindings()` in clause order, `toRawSql()` with values inlined and escaped (for
  debugging only).
- `raw(sql)` values are written into the SQL as given and never bound.
- `clone()` gives a copy that shares no mutable state; `newQuery()` a fresh builder on the same
  connection and grammar.

Values

- Instants are stored as each database stores `Blueprint.instant`: whole microseconds on SQLite,
  `timestamptz` on PostgreSQL. Other values follow the connection's binding rules.

## 4. Non-functional requirements

- Generated SQL matches Laravel's grammars for the same calls, apart from the differences listed
  in ADR 0013.
- Every user value goes through a bound parameter; names are always quoted by the grammar.
- No new runtime dependency; Java 17 bytecode and Android 9 (API 28).
- The builder is mutable and not thread-safe. A cursor is used from one thread.
- A cursor keeps memory flat: rows arrive while the query is still running.

## 5. Non-goals

Lateral joins, `straightJoin`, `updateFrom` and cross-database name prefixes; URL and HTML
paginators; JSON and full-text wheres; upsert and `insertOrIgnore`; dynamic wheres and macros;
`dump` and `dd`; locks; bitwise operators; `distinct on`; a typed row object; Eloquent models;
MySQL and SQL Server.
