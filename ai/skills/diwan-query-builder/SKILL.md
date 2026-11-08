---
name: diwan-query-builder
description: Read and write rows with Diwan's query builder (table, where, join, paginate, chunk, cursor, upsert, row locks, raw expressions, limit and offset). Use when writing or reviewing any query that is not a model call.
---

# Diwan query builder

## Steps

1. Start from the manager: `QueryFactory db = manager.query();` then `db.table("orders")`. Use
   `SQLiteQuery.of(connection)` only when there is no manager.
2. Filter with `where(column, value)` or `where(column, operator, value)`, with an operator such as
   `">="`, `"<"`, `"<>"`, `"like"`. Page with `limit(n)`/`take(n)`, `offset(n)`/`skip(n)`, or
   `forPage(page, perPage)`. All return the same builder. Then end with `get()`, `first()`,
   `find(id)`, `value(col)`, `exists()`, `count()`, or a write (`insert`, `update`, `delete`,
   `upsert`).
3. For large reads pick `chunkById`, `lazyById`, or `cursor()`.
4. For user-chosen ordering, check the column against an allowlist first.

## Example

```java
private static final Set<String> SORTABLE = Set.of("created_at", "total", "id");

String column = SORTABLE.contains(requested) ? requested : "id";
LengthAwarePage page = query.table("orders")
        .where("status", "open")
        .orderBy(column).orderBy("id")
        .paginate(20, 1);

try (QueryCursor<Map<String, Object>> rows = query.table("orders").orderBy("id").cursor()) {
    for (Map<String, Object> row : rows) {
        export(row);
    }
}

connection.transaction(c -> db.on(c).table("accounts")   // inside a transaction, call db.on(c)
        .where("id", 1).lockForUpdate().get());
```

## Pitfalls

- Close every `QueryCursor` in try-with-resources. An unclosed cursor keeps its pooled connection.
  `stream()` and `iterator()` are single use, and closing the stream closes the cursor.
- Column and direction names are quoted, not bound. An allowlist is your job.
- Raw methods (`selectRaw`, `whereRaw`, `orderByRaw`, `groupByRaw`, `havingRaw`, `fromRaw`,
  `Expression.raw`) take SQL as given. Put values in the bindings list:
  `whereRaw("total > ? and status = ?", List.of(100, status))`. The SQL text comes from constants.
- An unknown operator is taken as the value: `where("total", "=>", 10)` compares `total = '=>'`.
  Use the listed operators.
- An empty `whereIn` matches no row; an empty `whereNotIn` matches every row, as in Laravel. Check
  the list before a delete.
- User text goes through `whereContains`, `whereStartsWith` or `whereEndsWith`, which escape `%`
  and `_`. Never build a `whereLike` pattern from user input: `whereLike` takes `%` and `_` as
  wildcards and escapes nothing.
- Row locks need a transaction on PostgreSQL, which throws `IllegalStateException` outside one.
  SQLite has no row locks: every strength is a no-op there, and `NOWAIT` and `SKIP_LOCKED` are refused.
- `upsert` needs a unique index on exactly the `uniqueBy` columns, and SQLite 3.24 (Android API 30).
  `updateOrInsert` reads then writes and is not atomic.
- `chunk` pages by offset and can skip rows on a changing table. Use `chunkById`.
- `cursorPaginate` needs an `orderBy` on columns that are never null. Cursor tokens are not signed.
- Batched `insert`, `upsert` and `insertOrIgnore` must stay under the binding limit (999 on SQLite
  before 3.32, 32766 after, 65535 on PostgreSQL).

Docs: Query builder on https://aristonis.org/open-source/diwan-database/docs/main/home
