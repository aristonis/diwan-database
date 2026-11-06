---
name: diwan-usage
description: Set up Diwan Database in a Java or Android project and run SQL and transactions. Use when adding the dependency, building a Connection, choosing SQLite or PostgreSQL, or wrapping work in a transaction.
---

# Diwan usage

## Steps

1. Add the core module plus one grammar module: `org.aristonis:diwan-database` with
   `diwan-database-sqlite` or `diwan-database-pgsql`. Add `diwan-database-eloquent` for models and
   `diwan-database-android` on Android. PostgreSQL also needs its JDBC driver.
2. Build one `DatabaseManager` at start-up, with the connection config and the connectors
   (`SQLiteConnector`, `PostgresConnector`). The `driver` setting picks the database.
3. Get the factories from the manager: `manager.query()`, `manager.schema()`,
   `manager.migrator(name)`. Never import `SQLiteQuery` or `PostgresQuery` in application code.
   Without a manager, use `SQLiteQuery.of(connection)` and the other grammar entry points.
4. Wrap multi-statement work in `db.transaction(tx -> ...)` and use `tx` for everything inside.

## Example

```java
DatabaseManager manager = DatabaseManager.builder()
        .config(DatabaseConfig.from(source))      // driver, host, database per connection
        .connector(new SQLiteConnector())
        .build();
Connection db = manager.connection();
QueryFactory query = manager.query();

long orderId = db.transaction(tx -> {
    long id = tx.insert("insert into orders (total) values (?)", 1200);
    tx.update("update stock set qty = qty - ? where sku = ?", 1, "A1");
    tx.afterCommit(() -> mailer.sendReceipt(id));
    return id;
}, 3);                                    // retries up to 3 times on a deadlock
```

## Pitfalls

- Strict transactions are on. Calling `db` instead of `tx` inside the callback throws
  `IllegalStateException`. Inside a transaction, pass `tx` down; never call the root connection there. For the query factory or schema, call `db.on(tx)` so the work runs on the transaction.
- A retried transaction runs the whole callback again, so keep outside effects in `afterCommit`.
- A failed `COMMIT` is never retried. If the connection drops during `COMMIT`, check the database
  before you undo anything.
- Values go in as `?` bindings. Never join user input into the SQL string.
- `TransactionScope` from `db.beginTransaction()` rolls back on close unless `commit()` ran.
- Errors are unchecked and the message hides binding values. Do not log bindings with personal data.

Docs: Getting started and Connections and transactions on https://docs.aristonis.org/diwan-database/
