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
| Write | `insert`, `insertGetId`, `insertUsing`, `insertOrIgnoreUsing`, `update`, `increment`, `decrement`, `delete`, `truncate` |
| Read in batches or stream | `chunk`, `each`, `chunkMap`, `chunkById`, `chunkByIdDesc`, `eachById`, `lazy`, `lazyById`, `lazyByIdDesc`, `cursor` |
| Page a list | `paginate`, `simplePaginate`, `cursorPaginate`, `getCountForPagination` |
| See the SQL | `toSql`, `getBindings`, `toRawSql` |
| Copy | `clone`, `newQuery` |

Money is written explicitly (`Money.toMinor(amount, 2)` or `Money.toText(amount, scale)`) and read
with `MoneyAmount.read`. An `Instant` is stored as `Blueprint.instant` stores it on each database.

## Limits

- A right join needs SQLite 3.39 or later. Older SQLite, including Android 9 to 13, reports its
  own error as a `QueryException`.
- An update or delete with a join on a SQLite `without rowid` table fails with SQLite's error.
- Neither database lets a whole union be ordered by a subquery.
- A write needs a table: insert, update, delete and truncate are refused on a query that reads
  from a subquery or raw SQL.
- `chunk` pages by offset. If rows are inserted or deleted while it runs, rows can be skipped or
  read twice; use `chunkById` for a table that changes.
- A null in a `cursorPaginate` order column can make rows be skipped, as in Laravel. Order by
  columns that are never null.
- On SQLite an open cursor holds a read lock, so another connection's write waits or fails as
  busy until the cursor is closed.
- A cursor outside a transaction that is neither read to the end nor closed keeps its pooled
  connection for good; nothing closes it for you. A few of them can use up the pool, and on
  PostgreSQL each one also leaves the server session idle in a transaction, which blocks vacuum.
  Always use try-with-resources.
- On Android the cursor reads through a window that is refilled by running the query again, so
  rows changed during the loop can be skipped or repeated. Use `lazyById` or `chunkById` for a
  large or changing table.
- On Android a cursor is used only from the thread that opened it; another thread is refused.
- Cursor tokens are not signed. A client can forge one; its values are bound as parameters, so it
  cannot inject SQL, but it can ask for any rows the query already allows.
