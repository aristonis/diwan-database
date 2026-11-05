# Connection events

A connection can tell your code about every statement it runs and every step of its transactions.
You register a listener once, on the root connection, and it sees the root, every session, every
transaction connection and every transaction scope that comes from it. Nothing is global: two
connections in one process have separate listeners.

When nothing listens, no handler is set and the query log is off, a statement reads no clock and
builds no event, so the feature costs nothing until you use it.

This page covers statement and transaction events, the query log, slow-query handlers, and the
`afterCommit` and `afterRollback` callbacks.

## Examples

### Log slow queries on a server

```java
Connection connection = Connection.builder()
        .name("main")
        .dataSource(dataSource)
        .build();

Registration slow = connection.events().whenQueryTakesLongerThan(
        Duration.ofMillis(500),
        event -> LOG.warning("slow query " + event.time().toMillis() + " ms: "
                + event.redacted()));

// later, when you no longer want it
slow.remove();
```

The handler runs for each statement that took longer than the threshold. `redacted()` replaces the
bindings with `?`, so the log line carries no personal data.

### Count the queries of one request

```java
AtomicInteger count = new AtomicInteger();
Registration counter = connection.events().listen(event -> count.incrementAndGet());
try {
    handleRequest();
} finally {
    counter.remove();
}
LOG.info("request ran " + count.get() + " queries");
```

A listener sees the statements of every thread that uses the connection. On a shared connection
used by many requests at once, key the counter by thread or request instead of using one counter.

To be told when the statements of a connection have taken too long in total, use the total-time
handler:

```java
connection.events().whenQueryingForLongerThan(
        Duration.ofSeconds(2),
        (ran, event) -> LOG.warning("queries have taken over 2 s in total"));
```

It fires once, for the statement that pushes the total past the limit, and stays quiet until you
call `connection.events().resetTotalQueryDuration()`.

### Debug query log on Android

```java
Connection connection = Connection.builder()
        .name("app")
        .driver(androidDriver)
        .queryLogLimit(200)
        .build();

if (BuildConfig.DEBUG) {
    connection.events().enableQueryLog();
}

// in a debug screen
for (LoggedQuery entry : connection.events().getQueryLog()) {
    Log.d("diwan", entry.sql() + " " + entry.bindings());
}
```

The log keeps the last 200 statements and drops the oldest first, so a debug build cannot fill
memory.

### Send a mail only after the order committed

```java
connection.transaction(tx -> {
    long orderId = tx.insert("insert into orders (total) values (?)", total);
    tx.afterCommit(() -> mailer.sendReceipt(orderId));
    return orderId;
});
```

If the transaction rolls back, or fails to commit, the mail is never sent. The callback runs after
the transaction has finished, so nothing is rolled back if the mailer throws.

## Listener interface

Implement `ConnectionListener` and override only what you need. Every method has an empty default.

| Method | Called | Argument |
|---|---|---|
| `queryExecuted` | after a statement succeeded | `QueryExecuted` |
| `queryFailed` | when a statement failed, before the `QueryException` reaches the caller | `QueryFailed` |
| `transactionBeginning` | after `BEGIN` or `SAVEPOINT` succeeded | `TransactionEvent` |
| `transactionCommitting` | before `COMMIT` or `RELEASE SAVEPOINT` | `TransactionEvent` |
| `transactionCommitted` | after `COMMIT` or `RELEASE SAVEPOINT` | `TransactionEvent` |
| `transactionRolledBack` | after `ROLLBACK` or `ROLLBACK TO SAVEPOINT` | `TransactionEvent` |

Register it on the builder, or later on the events object:

```java
ConnectionListener listener = new ConnectionListener() {
    @Override
    public void queryExecuted(QueryExecuted event) {
        metrics.record(event.kind().name(), event.time());
    }
};

Connection connection = Connection.builder()
        .name("main")
        .dataSource(dataSource)
        .listener(listener)
        .build();

Registration registration = connection.events().addListener(listener);
Registration lambda = connection.events().listen(event -> { /* QueryExecuted only */ });
```

`addListener` and `listen` return a `Registration`; call `remove()` to undo it. Calling it twice is
harmless. Listeners run in the order they were added, on the thread that ran the statement.
Registering while statements run is safe.

A `QueryExecuted` event holds the SQL, the bindings, the time as a `Duration`, the connection name,
the `QueryKind` and the transaction level (0 outside a transaction). A `QueryFailed` event holds
the same and the `QueryException`. The bindings are copied, so changing your array afterwards does
not change the event.

## What kind of statement

`QueryKind` tells which `Connection` method ran the statement.

| Kind | Method |
|---|---|
| `SELECT` | `select` |
| `SELECT_ONE` | `selectOne` |
| `SCALAR` | `scalar` |
| `CURSOR` | `cursor` |
| `INSERT` | `insert` |
| `UPDATE` | `update` |
| `DELETE` | `delete` |
| `STATEMENT` | `statement` and `unprepared` |
| `AFFECTING` | `affectingStatement` |

## When an event fires

- `queryExecuted` fires after the statement succeeded. The time is how long the statement took.
- A cursor fires when it opens. Its time covers opening only, not the reading of rows, because the
  rows are read later and in pieces.
- A pretended statement (see `pretend`) fires `queryExecuted` with a time of zero, because nothing
  reached the database.
- A failed statement fires `queryFailed`, not `queryExecuted`. It carries the exception and the time
  spent. The listeners run before the exception reaches you.

## Failed statements

```java
connection.events().addListener(new ConnectionListener() {
    @Override
    public void queryFailed(QueryFailed event) {
        LOG.warning("failed after " + event.time().toMillis() + " ms: " + event.sql());
    }
});
```

`event.exception()` is the `QueryException` that is about to be thrown. If your `queryFailed`
listener throws, that failure is added to the `QueryException` as a suppressed exception. It never
replaces the original error, so you always see why the statement failed.

## Transaction events

Each level fires its own events, with `level()` (1 is the outermost transaction) and
`isSavepoint()`.

| Where | Events |
|---|---|
| `transaction(callback)` or `beginTransaction()` on the root, outermost level | `transactionBeginning` after `BEGIN`; `transactionCommitting` before `COMMIT`; `transactionCommitted` after it. On a failed callback, `transactionRolledBack` after `ROLLBACK`. |
| A nested `transaction` inside a transaction, level 2 and deeper | The same four events with `isSavepoint()` true; they fire around `SAVEPOINT`, `RELEASE SAVEPOINT` and `ROLLBACK TO SAVEPOINT`. |
| A held `session` with a `transaction` inside it | The same events as on the root. |
| A transaction a caller opened outside the library (for example Spring) | The outermost level is only a savepoint, so its events have `isSavepoint()` true. The library never commits or rolls back the caller's transaction, and fires no event for it. |

A rollback of a level fires `transactionRolledBack` once for that level, after the rollback
succeeded. A deadlock retry from `transaction(callback, attempts)` fires a rolled-back event for
the failed attempt and a new beginning event for the next one.

## afterCommit and afterRollback

```java
tx.afterCommit(() -> cache.evict(key));
tx.afterRollback(() -> LOG.warning("order was not saved"));
```

Both are on `Connection`. They take a `Runnable`, reject `null`, and keep registration order.

### Rules

1. Outside a transaction, `afterCommit` runs at once on the calling thread. There is nothing to wait
   for. `afterRollback` outside a transaction is dropped, because there is nothing to roll back.
2. Inside a transaction, `afterCommit` callbacks run after the outermost commit. A callback
   registered inside a savepoint stays when the savepoint is released and moves to the parent level.
3. When a savepoint is rolled back, its `afterCommit` callbacks are dropped, and its `afterRollback`
   callbacks run. Callbacks of other levels stay.
4. When the whole transaction rolls back, `afterCommit` callbacks are dropped and `afterRollback`
   callbacks run. When it commits, `afterRollback` callbacks of levels that were not rolled back are
   dropped.
5. A deadlock retry gives each attempt its own callbacks. A failed attempt drops its `afterCommit`
   callbacks, so a retried transaction does not send a mail twice. Its `afterRollback` callbacks
   run when the whole transaction call ends, not between attempts, on the root and on a session
   alike. The rolled-back event of the failed attempt fires right away.
6. When the `COMMIT` itself fails, no committed event fires, the `afterCommit` callbacks are dropped
   and the `afterRollback` callbacks run. When the `ROLLBACK` command fails, the `afterRollback`
   callbacks still run and no rolled-back event fires.
   If the connection drops while `COMMIT` runs, the outcome is unknown and the commit may have
   landed. The `afterRollback` callbacks run anyway, so undo logic such as a refund must check the
   database first and not assume the work is gone.
7. Callbacks run after the transaction has fully ended: after the commit or rollback, the end of
   the attempt, the release of its database connection and the thread leaving the transaction. A
   callback that throws never rolls anything back.
8. Because the transaction's connection is already closed, a callback must use the root connection,
   not the one it was registered on. Strict transactions allow this, since the thread has left the
   transaction.
9. With a held session (`connection.session(...)`), the callbacks run when the transaction ends,
   which is before the held session closes. A call on the root connection from a callback therefore
   borrows a second database connection for as long as it runs. Keep such callbacks short, or hand
   the work to another thread.
10. A callback that throws does not stop the others. The first failure is rethrown to the caller of
    the outermost transaction, or added as a suppressed exception to the failure that caused the
    rollback. Later failures are suppressed onto it.
11. Inside a transaction a caller opened outside the library, `afterCommit` throws
    `IllegalStateException`, because the library cannot know when the caller commits.
    `afterRollback` is accepted and runs when the library rolls back its own savepoint.
12. Registering on a connection whose transaction or scope has already ended throws
    `IllegalStateException`.

Callbacks of one transaction are only used by the thread that owns it, so they need no locking.

## Listeners that run SQL

A `transactionRolledBack` listener on the root path runs before the thread has left the
transaction, so SQL it runs on the root connection is refused while strict transactions are on.
Use an `afterRollback` callback for that work, because those run after the thread has left.

While a listener or slow handler runs, statements the same thread runs on the connection fire no
events and are not added to the query log. A listener can write an audit row or read the log
without calling itself again. The slow-query handlers receive the connection that ran the
statement.

## When a listener fails

| Event | What the caller sees |
|---|---|
| `queryExecuted` listener or slow handler throws | A `ListenerFailedException` after the statement ran. Its message says the statement already succeeded; its cause is the first listener's exception, later ones suppressed. All other listeners still ran. |
| `queryFailed` listener throws | The failure is added to the `QueryException` as suppressed. The `QueryException` is thrown as before. |
| `transactionBeginning` listener throws | The failure propagates after all listeners ran. The level that began is undone. |
| `transactionCommitting` listener throws | The failure propagates at once and the transaction rolls back. |
| `transactionCommitted` or `transactionRolledBack` listener throws | The state is already final. The other listeners still run, then the first failure propagates, the rest suppressed. |
| `afterCommit` or `afterRollback` callback throws | See rule 10 above. |

`ListenerFailedException` is unchecked. The statement is not undone. Inside a transaction you decide
whether to roll back by letting the exception leave the callback or by catching it.

## Query log

```java
ConnectionEvents events = connection.events();
events.enableQueryLog();
// ... run statements
List<LoggedQuery> log = events.getQueryLog();   // a copy, oldest first
events.flushQueryLog();
events.disableQueryLog();                       // recorded entries are kept
boolean on = events.isLoggingQueries();
```

- The log is off until you enable it.
- It is bounded. The default is 1000 entries, set with `ConnectionBuilder.queryLogLimit(int)`. When
  full, the oldest entry is dropped.
- The connection family shares one log: a statement run on a session, a transaction connection or a
  scope appears in the same log as one run on the root, from any thread.
- Bindings are stored compactly, so one large value cannot take the memory of the log. A `byte[]`
  appears as `<byte[n]>`. A string over 1024 characters is cut and ends with `...(n chars)`. The
  event your listener receives keeps the raw bindings.
- When the connection's `showBindingsInErrors` is off (the default), the log stores `?` for every
  binding, so it holds no values. Switch it on only where the log may hold personal data, such as a
  debug build.
- The text form of a log entry or an event shows how many bindings there are, not their values.

## Slow handlers and Registration

| Call | Fires |
|---|---|
| `whenQueryTakesLongerThan(Duration, Consumer<QueryExecuted>)` | for each statement whose time is longer than the threshold |
| `whenQueryingForLongerThan(Duration, BiConsumer<Connection, QueryExecuted>)` | once, for the statement that pushes the total past the threshold, then not again until `resetTotalQueryDuration()` |

Both return a `Registration`. `remove()` removes the handler and is safe to call more than once or
from another thread. `totalQueryDuration()` returns the time counted so far. Only statements run
while something listens, logs or has a handler are counted. A negative threshold throws
`IllegalArgumentException`.

## Bindings may hold secrets

A `QueryExecuted` or `QueryFailed` event carries the values you passed: passwords, tokens, names.
Do not send the raw event to a log service, a metrics tag or a crash report. Call `redacted()`
first. It returns a copy with every binding replaced by `?`, so the SQL, time, kind and level stay
and the values are gone.

## Differences from Laravel

| Laravel | Diwan Database |
|---|---|
| Events go through the application's event dispatcher. | Listeners live on the connection family. There is no container and no global dispatcher. |
| `whenQueryingForLongerThan` tests the total time only. | The total-time handler exists, and `whenQueryTakesLongerThan` adds a per-statement one. |
| The query log grows without a limit. | The log is bounded (default 1000) and drops the oldest entry. |
| There is no failed-query event. | `queryFailed` fires before the exception reaches the caller. |
| The event holds raw bindings only. | `redacted()` returns a copy with the values hidden. |
| Callbacks of a rolled-back savepoint are dropped. | Same. |
| `afterCommit` callbacks run from the transaction manager. | They run after the transaction has fully ended and use the root connection. |
| A listener exception leaves the statement's result unclear. | It is a `ListenerFailedException` that says the statement succeeded. |
| Statements run by a listener fire events again. | They fire nothing and are not logged. |

## Wiring and other events

- Model listeners that wait for the commit use the `afterCommit` described above. See
  [After-commit listeners and observers](../eloquent/model-events.md#after-commit-listeners-and-observers).
- Migration progress has its own listener. See
  [Migration events](../migrator/README.md#migration-events).
- To attach a `ConnectionListener` to every connection an application builds, register it on
  the database manager. See
  [Manager listeners](database-manager.md#manager-listeners-and-connection-established).

## Not in this release

- Laravel's `StatementPrepared` and `DatabaseBusy` events do not exist.
- Queued or asynchronous listeners do not exist; every listener runs on the caller's thread.
- There is no read/write type on events, because connections have no read/write split yet.

## More

- [Connection](README.md)
- [Database manager and configuration](database-manager.md)
- Decision: [0025 Connection events](../../adr/0025-connection-events.md)
- Decision: [0026 Model after-commit listeners, migration events and manager wiring](../../adr/0026-model-after-commit-migration-and-manager-events.md)
