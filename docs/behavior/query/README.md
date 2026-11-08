# Query builder

The query builder reads and writes rows the way Laravel's `DB::table(...)` does. You chain
conditions on a builder; the grammar of your database turns them into SQL with every value bound
as a parameter.

```java
QueryFactory query = SQLiteQuery.of(db);   // PostgresQuery.of(db) on PostgreSQL

List<Map<String, Object>> open = query.table("orders")
        .where("status", "open")
        .where("total", ">", 1000)
        .orderByDesc("id")
        .limit(10)
        .get();

long count = query.table("orders").where("status", "open").count();
Object id = query.table("orders").insertGetId(Map.of("customer", "ada", "status", "open"));
query.table("orders").where("id", id).update(Map.of("status", "paid"));
query.table("orders").where("id", id).delete();

List<Object> names = query.table("customers")
        .select("customers.name")
        .join("orders", "customers.id", "=", "orders.customer_id")
        .whereExists(q -> q.from("payments").whereColumn("payments.customer_id", "customers.id"))
        .union(query.table("archived_customers").select("name"))
        .orderBy("name")
        .pluck("name");
```

## What you can do

| You want to | Use |
|---|---|
| Start a query | `table`, `query`, `select`, `addSelect`, `selectRaw`, `distinct` |
| Filter rows | `where`, `orWhere`, `whereNot`, `whereIn`, `whereNull`, `whereBetween`, `whereLike`, `whereColumn`, `whereDate` and friends, `whereInstantBetween`, `whereAll`, `whereAny`, `whereNone`, `whereRaw` |
| Sort, group and page | `orderBy`, `latest`, `oldest`, `inRandomOrder`, `reorder`, `groupBy`, `having`, `limit`, `offset`, `forPage` |
| Combine | `join`, `leftJoin`, `rightJoin`, `crossJoin`, `joinSub`, `union`, `unionAll` |
| Use a subquery | `fromSub`, `selectSub`, `whereIn` or `whereExists` with a query, `whereRowValues`, `orderBy` a query |
| Read | `get`, `first`, `find`, `value`, `pluck`, `exists`, `doesntExist` |
| Count and sum | `count`, `min`, `max`, `sum`, `avg` |
| Write | `insert`, `insertGetId`, `insertUsing`, `insertOrIgnore`, `insertOrIgnoreUsing`, `upsert`, `updateOrInsert`, `update`, `increment`, `decrement`, `delete`, `truncate` |
| Read in batches or stream | `chunk`, `each`, `chunkMap`, `chunkById`, `chunkByIdDesc`, `eachById`, `lazy`, `lazyById`, `lazyByIdDesc`, `cursor` |
| Page a list | `paginate`, `simplePaginate`, `cursorPaginate`, `getCountForPagination` |
| See the SQL | `toSql`, `getBindings`, `toRawSql` |
| Copy | `clone`, `newQuery` |

An `Instant` is stored as `Blueprint.instant` stores it on each database.

## Insert or ignore, upsert and update-or-insert

```java
db.table("users").insertOrIgnore(rows);                       // skips rows that break a unique key
db.table("users").upsert(rows, List.of("email"));              // updates every column of the first row
db.table("users").upsert(rows, List.of("email"), List.of("name"));
db.table("users").upsert(rows, List.of("email"), Map.of("votes", Expression.raw("users.votes + 1")));
db.table("users").updateOrInsert(Map.of("email", "a@x"), Map.of("name", "A"));
```

- `insertOrIgnore` writes `insert or ignore` on SQLite and `on conflict do nothing` on PostgreSQL,
  and returns the number of rows inserted. No rows runs nothing and returns 0.
- `upsert` writes `insert ... on conflict ("email") do update set "name" = "excluded"."name"`. The
  second list names the unique columns; the database needs a unique index on exactly those, or it
  refuses the statement with a `QueryException`. The third argument is a list of columns to take
  from the refused row (none given: every column of the first row), or a map of column to value;
  map values are bound after the row values, and an `Expression` is written as given.
- The checks run in this order: no unique columns is an `IllegalArgumentException`; no rows
  returns 0 without SQL; a row with no columns, or rows with different columns, is an
  `IllegalArgumentException`.
- `updateOrInsert(attributes, values)` looks for a row matching the attributes. If there is none it
  inserts the attributes merged with the values; if there is one it updates it (`limit 1`) and
  returns whether a row changed. With no values it only checks and returns `true`.
- The base grammar refuses all three with `UnsupportedOperationException`; SQLite and PostgreSQL
  implement them.

## Row locks

```java
connection.transaction(c -> {
    var rows = query.on(c).table("accounts").where("id", 1).lockForUpdate().get();
    var next = query.on(c).table("jobs").lock(LockStrength.UPDATE, LockWait.SKIP_LOCKED).limit(10).get();
    return null;
});
```

Inside a transaction, `query.on(c)` returns the factory bound to the transaction's connection; another kind of database is refused with `IllegalArgumentException`.

- `lockForUpdate()` and `sharedLock()` are `lock(LockStrength.UPDATE)` and
  `lock(LockStrength.SHARE)`. `lock(strength, wait)` adds `LockWait.NOWAIT` or
  `LockWait.SKIP_LOCKED`. The strengths are `UPDATE`, `NO_KEY_UPDATE`, `SHARE` and `KEY_SHARE`.
  The last lock set wins, and `clone` keeps it.
- PostgreSQL writes `for update`, `for no key update`, `for share` or `for key share`, then
  `nowait` or `skip locked`, last in the select, after the offset.
- SQLite has no row locks: every strength (`UPDATE`, `NO_KEY_UPDATE`, `SHARE`, `KEY_SHARE`)
  writes nothing, as in Laravel. `NOWAIT` and `SKIP_LOCKED` are refused with
  `UnsupportedOperationException`, since they change which rows a caller reads. A locked select
  takes no lock early; on the JDBC driver a transaction starts deferred, so two read-then-update
  transactions on one row do not queue: the second writer fails with a busy error.
- A locked select on PostgreSQL outside a transaction throws `IllegalStateException`, because the
  lock would be released as soon as the statement ends. This does not depend on the connection's
  strict transactions flag. Run the query on the connection a `transaction(...)` callback gives.
- `count`, `sum` and the other aggregates, `exists` and the count that `paginate` makes leave the
  lock out, as PostgreSQL refuses `for update` there. The page's own select keeps it.
- A union with a lock on the query or on any part is an `IllegalArgumentException`.
- A model query forwards all four: `lockForUpdate()`, `sharedLock()`, `lock(strength)` and
  `lock(strength, wait)`.

## Limits

- In the generated Javadoc, `Builder` methods declared in its package-private layers show their
  links as plain text; javadoc only links a member to its declaring class. The methods are public
  and callable; the guides on the docs site describe them.
- A right join needs SQLite 3.39 or later. Older SQLite, including Android 9 to 13, reports its
  own error as a `QueryException`.
- `upsert` needs SQLite 3.24 or later, which is Android API 30 and up. Older SQLite, as on
  Android 9, is refused with an `UnsupportedOperationException` that names the version, before any
  SQL runs; a version that cannot be read is refused with its own message ("Cannot read the SQLite
  version ..."). `insertOrIgnore` works on every version.
- `updateOrInsert` is not atomic: it reads, then writes. Two callers can both find no row and both
  insert. Use `upsert` on a unique index when that matters: it is one atomic statement.
- `insertOrIgnore` and `upsert` send every row in one statement and never split it. Each value is
  a binding, so rows times columns must stay under the driver's limit: 999 on SQLite before 3.32,
  32766 on SQLite 3.32 and later, 65535 on PostgreSQL. Past it the database refuses the
  statement. Chunk the rows yourself, inside a transaction when the chunks must succeed together.
- An `upsert` batch that holds two rows with the same unique key differs by database. SQLite
  applies the rows in order, so the last one wins. PostgreSQL refuses the statement ("ON CONFLICT
  DO UPDATE command cannot affect row a second time"). Remove duplicate keys from the batch first.
- An `upsert` with an empty update list or map leaves a conflicting row as it is
  (`on conflict ... do nothing`). Laravel runs a plain insert there, which fails on the conflict.
- On SQLite `insertOrIgnore` also skips a row that fails a `not null` or `check` constraint, not
  only a unique one.
- An update or delete with a join on a SQLite `without rowid` table fails with SQLite's error.
- Row locks take the two enums only. Laravel's raw string lock (`lock('lock in share mode')`) is
  not available, and there is no `for update of table`. SQLite ignores the plain locks, so a
  program that relies on them for mutual exclusion is only safe on PostgreSQL. On PostgreSQL a
  lock needs a transaction.
- Neither database lets a whole union be ordered by a subquery.
- A write needs a table: insert, update, delete and truncate are refused on a query that reads
  from a subquery or raw SQL.
- `chunk` pages by offset. If rows are inserted or deleted while it runs, rows can be skipped or
  read twice; use `chunkById` for a table that changes.
- A null in a `cursorPaginate` order column can make rows be skipped, as in Laravel. Order by
  columns that are never null.
- `cursorPaginate` quotes each order column as a name, but the app must allowlist any sort column
  that comes from user input.
- On SQLite an open cursor holds a read lock, so another connection's write waits or fails as
  busy until the cursor is closed.
- A cursor outside a transaction that is neither read to the end nor closed keeps its pooled
  connection for good; nothing closes it for you. A few of them can use up the pool, and on
  PostgreSQL each one also leaves the server session idle in a transaction, which blocks vacuum.
  Always use try-with-resources.
- `cursor()` returns a `QueryCursor`: `iterator()` and `stream()` share one single use, and a
  second call throws `IllegalStateException`. A `stream()` releases the connection only when the
  stream is closed, so a bare `cursor().stream().forEach(...)` that stops early leaks the session;
  put the stream in try-with-resources.
- On Android the cursor reads through a window that is refilled by running the query again, so
  rows changed during the loop can be skipped or repeated. Use `lazyById` or `chunkById` for a
  large or changing table.
- On Android a cursor is used only from the thread that opened it; another thread is refused.
- Cursor tokens are not signed. A client can forge one; its values are bound as parameters, so it
  cannot inject SQL, but it can ask for any rows the query already allows.
