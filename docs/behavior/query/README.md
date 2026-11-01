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
```

## What you can do

| You want to | Use |
|---|---|
| Start a query | `table`, `query`, `select`, `addSelect`, `selectRaw`, `distinct` |
| Filter rows | `where`, `orWhere`, `whereNot`, `whereIn`, `whereNull`, `whereBetween`, `whereLike`, `whereColumn`, `whereDate` and friends, `whereInstantBetween`, `whereAll`, `whereAny`, `whereNone`, `whereRaw` |
| Sort, group and page | `orderBy`, `latest`, `oldest`, `inRandomOrder`, `reorder`, `groupBy`, `having`, `limit`, `offset`, `forPage` |
| Read | `get`, `first`, `find`, `value`, `pluck`, `exists`, `doesntExist` |
| Count and sum | `count`, `min`, `max`, `sum`, `avg` |
| Write | `insert`, `insertGetId`, `update`, `increment`, `decrement`, `delete`, `truncate` |
| See the SQL | `toSql`, `getBindings`, `toRawSql` |
| Copy | `clone`, `newQuery` |

Money is written explicitly (`Money.toMinor(amount, 2)` or `Money.toText(amount, scale)`) and read
with `MoneyAmount.read`. An `Instant` is stored as `Blueprint.instant` stores it on each database.
