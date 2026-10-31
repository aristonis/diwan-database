# Connection

The connection is how a Java application, and every later part of Photisma, sends SQL to a
database. It follows Laravel's `Connection`: the same method names, transactions that nest, and a
dry-run mode. It is built for Java callers: it is safe to share between threads, borrows a pooled
connection only while it works, and can join a transaction owned by Spring or your own code.

## What you can do with it

| You want to | Call |
|---|---|
| Run any statement | `statement`, `unprepared` (raw SQL, no bindings) |
| Change rows and know how many | `insert`, `update`, `delete`, `affectingStatement` |
| Read rows | `select` (every row), `selectOne` (first row or empty), `scalar` (one value or empty) |
| Group work so all of it or none of it happens | `transaction(callback)`, or `beginTransaction()` with try-with-resources |
| Survive a deadlock | `transaction(callback, attempts)` |
| See the SQL without running it | `pretend(callback)` |

Values are passed as positional bindings, as varargs or a list, and are checked before anything is
sent. Rows come back as read-only maps with the columns in select order.

## How it behaves

- Each call outside a transaction borrows a connection from your pool and gives it back before it
  returns. A transaction keeps one connection until it ends, and always gives it back.
- A transaction inside a transaction uses a savepoint, so an inner failure can undo only the inner
  work.
- Inside a transaction, use the connection handed to your callback (or the scope). By default the
  outer connection refuses calls from inside its own transaction, because they would run outside
  it.
- When the database reports a deadlock or serialization failure, `transaction(callback, attempts)`
  runs the whole callback again. A failed commit is never retried, because it may have landed.
- Inside a transaction someone else owns (for example Spring's), Photisma works through a
  savepoint and leaves commit and rollback to the owner.
- Errors are unchecked `QueryException`s that carry the SQL, the values and the database's codes.
  A duplicate key has its own type. Values stay out of the message unless you switch them on,
  because they can hold personal data.

## Where it runs

Java 17 and later, inside Spring through a connection provider, and on Android 9 and later through
a driver that does not need JDBC. The core has no runtime dependency.

## More

- [User stories](user-stories.md)
- [User scenarios and edge cases](user-scenarios.md)
- [Requirements](../../requirements/connection.md)
- [Design](../../architecture/connection-design.md)
