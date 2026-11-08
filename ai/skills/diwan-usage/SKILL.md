---
name: diwan-usage
description: Set up Diwan Database in a Java or Android project and run SQL and transactions. Use when adding the dependency, building a Connection, choosing SQLite or PostgreSQL, wrapping work in a transaction, or porting an existing JDBC or SQL layer to Diwan.
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

Object orderId = db.transaction(tx -> {
    Order order = Order.query.on(tx).create(Map.of("total", 1200));      // a model call
    query.on(tx).table("stock").where("sku", "A1").decrement("qty", 1);  // a builder call
    tx.afterCommit(() -> mailer.sendReceipt(order.getKey()));
    return order.getKey();
}, 3);                                    // retries up to 3 times on a deadlock
```

## Pitfalls

- Strict transactions are on. Calling `db` instead of `tx` inside the callback throws
  `IllegalStateException`. Inside a transaction, pass `tx` down; never call the root connection there. For the query factory or schema, call `db.on(tx)` so the work runs on the transaction.
- A retried transaction runs the whole callback again, so keep outside effects in `afterCommit`.
- On SQLite, set `transaction_mode` to `immediate` when transactions read then update the same rows. With the default `deferred`, the second one fails at once with a busy error.
- A failed `COMMIT` is never retried. If the connection drops during `COMMIT`, check the database
  before you undo anything.
- Raw `tx.insert(sql, bindings...)` and `tx.update(...)` are for a statement the builder cannot
  express.
- Values go in as `?` bindings. Never join user input into the SQL string.
- `TransactionScope` from `db.beginTransaction()` rolls back on close unless `commit()` ran.
- Errors are unchecked and the message hides binding values. Do not log bindings with personal data.
- Branch on `((DriverException) e.getCause()).error().code()`, such as `DWN-CST-0001` for a unique
  violation. Never branch on vendor codes or message text.

## Porting an existing JDBC or SQL layer

- Map each table to a model and each column to a schema builder call.
- Keep a database rule (check, collation, partial index) only where the builder has a call for it.
- Move every other rule to application validation.
- Report each gap you find.
- Never copy the old DDL as raw SQL per database.

Docs: Getting started and Connections and transactions on https://aristonis.org/open-source/diwan-database/docs/main/home
