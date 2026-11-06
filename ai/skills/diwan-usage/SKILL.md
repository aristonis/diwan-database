---
name: diwan-usage
description: Set up Diwan Database in a Java or Android project and run SQL and transactions. Use when adding the dependency, building a Connection, choosing SQLite or PostgreSQL, or wrapping work in a transaction.
---

# Diwan usage

## Steps

1. Add the core module plus one grammar module: `org.aristonis:diwan-database` with
   `diwan-database-sqlite` or `diwan-database-pgsql`. Add `diwan-database-eloquent` for models and
   `diwan-database-android` on Android. PostgreSQL also needs its JDBC driver.
2. Build one `Connection` per database, once, at start-up.
3. Get the query or schema factory from the grammar module: `SQLiteQuery.of(db)`,
   `PostgresQuery.of(db)`, `SQLiteSchema.of(db)`, `PostgresSchema.of(db)`.
4. Wrap multi-statement work in `db.transaction(tx -> ...)` and use `tx` for everything inside.

## Example

```java
Connection db = Connection.builder()
        .name("main")
        .dataSource(dataSource)           // any javax.sql.DataSource
        .build();

long orderId = db.transaction(tx -> {
    long id = tx.insert("insert into orders (total) values (?)", 1200);
    tx.update("update stock set qty = qty - ? where sku = ?", 1, "A1");
    tx.afterCommit(() -> mailer.sendReceipt(id));
    return id;
}, 3);                                    // retries up to 3 times on a deadlock
```

## Pitfalls

- Strict transactions are on. Calling `db` instead of `tx` inside the callback throws
  `IllegalStateException`. Inside a transaction, pass `tx` down; never call the root connection there.
- A retried transaction runs the whole callback again, so keep outside effects in `afterCommit`.
- A failed `COMMIT` is never retried. If the connection drops during `COMMIT`, check the database
  before you undo anything.
- Values go in as `?` bindings. Never join user input into the SQL string.
- `TransactionScope` from `db.beginTransaction()` rolls back on close unless `commit()` ran.
- Errors are unchecked and the message hides binding values. Do not log bindings with personal data.

Docs: Getting started and Connections and transactions on https://docs.aristonis.org/diwan-database/
