# 0025. Connection events

## Status

Accepted

## Context

An application needs to see the statements a connection runs and the steps of its transactions: to
log slow queries, count queries per request, show a debug log on a device, or send a mail only
after an order has committed. Laravel does this by dispatching `QueryExecuted`,
`TransactionBeginning` and the other events through the application's event dispatcher, and keeps
an unbounded query log and an `afterCommit` queue in a transaction manager.

Diwan Database has no container and no global dispatcher. A `Connection` is shared between threads,
and one root connection hands out sessions, transaction connections and transaction scopes that
all run statements. A server may keep one connection for days, so an unbounded log would grow
without limit. Bindings can hold passwords and personal data. A transaction connection is closed
before the code that wanted to run after the commit gets control, so Laravel's habit of running
callbacks from inside the transaction does not carry over.

## Decision

1. Listeners, the query log and the slow-query handlers live in one `ConnectionEvents` object per
   root connection, reached with `Connection.events()`. Sessions, transaction connections and
   scopes share it, so a listener added on the root sees all of them. No global dispatcher exists.

2. A `ConnectionListener` has default no-op methods: `queryExecuted`, `queryFailed`,
   `transactionBeginning`, `transactionCommitting`, `transactionCommitted` and
   `transactionRolledBack`. Registration is safe from any thread while statements run.

3. `queryExecuted` fires after a statement succeeded. A failed statement fires `queryFailed`, which
   Laravel does not have, before the `QueryException` reaches the caller. A cursor fires when it
   opens, so its time covers opening only. A pretended statement fires with a zero time.

4. When nothing listens, no handler is set and the log is off, a statement reads no clock and
   builds no event.

5. While a listener or handler runs, statements run by the same thread fire no events and are not
   logged. A listener can therefore use the connection without recursing, and the log cannot grow by
   reading itself.

6. Transaction events fire for every level, including savepoints. Inside a transaction opened
   outside the library the outermost level is only a savepoint, and its event says so.

7. `afterCommit` and `afterRollback` callbacks run after the transaction has fully ended: after the
   commit or rollback, the end of the attempt, the release of the session and the thread leaving the
   transaction. A callback that throws never rolls anything back. Callbacks use the root connection,
   because the transaction's connection is already closed.

8. `afterCommit` outside a transaction runs at once. `afterRollback` outside a transaction does
   nothing. Inside a caller-owned transaction `afterCommit` throws `IllegalStateException`, because
   the library cannot know when the caller commits. Releasing a savepoint moves its `afterCommit`
   callbacks to the parent level; rolling it back drops them and runs its `afterRollback` callbacks.
   Each retry attempt has its own callbacks; a failed attempt's `afterRollback` callbacks run when
   the whole transaction call ends.

9. A failing commit fires no `transactionCommitted`, drops the `afterCommit` callbacks and runs the
   `afterRollback` ones. A failing rollback still runs the `afterRollback` callbacks and fires no
   `transactionRolledBack`. If the connection drops during `COMMIT`, the outcome is unknown and the
   `afterRollback` callbacks may run although the commit landed.

10. A failure of a `queryExecuted` listener or slow handler is thrown to the caller as a
    `ListenerFailedException` whose message says the statement already succeeded. Every listener
    runs first; the first failure is the cause and the rest are suppressed onto it. A failure in
    `transactionCommitting` propagates at once and the transaction rolls back. A failure in any
    later transaction event or callback lets the others run, then the first failure propagates with
    the rest suppressed. A failure in `queryFailed` is suppressed onto the `QueryException` and
    never replaces it.

11. The query log is off until enabled, shared by the connection family, bounded (default 1000
    entries, set with `ConnectionBuilder.queryLogLimit`) and drops the oldest entry first. It stores
    a compact copy of the bindings: a `byte[]` becomes `<byte[n]>` and a string over 1024
    characters is cut and ends with `...(n chars)`. When `showBindingsInErrors` is off, the log
    stores `?` for every binding. The text form of an event or a log entry shows
    the number of bindings, never their values. The event handed to listeners keeps the raw
    bindings and offers `redacted()`.

12. `whenQueryTakesLongerThan` (per statement) and `whenQueryingForLongerThan` (total time, fires
    once until `resetTotalQueryDuration()`) return a `Registration` whose `remove()` undoes them.

## Consequences

- Adding a listener costs nothing for code that never registers one.
- A listener that throws on `queryExecuted` can surprise a caller whose statement worked. The
  exception type and message say so, and inside a transaction the caller decides whether to roll
  back.
- A callback that needs the database must use the root connection. With a held session, the
  callbacks run when the transaction ends and before that session closes, so a root call borrows a
  second connection.
- Listeners see bindings as passed. Code that forwards events to a log service should call
  `redacted()` first.
- The query log keeps strings only up to 1024 characters, so a very long statement value cannot be
  recovered from it.
- Model after-commit listeners, migration events and manager wiring are not part of this decision.

## Alternatives rejected

- A global dispatcher: there is no container, and two connections in one process would share
  listeners they did not ask for.
- Fifteen registration methods on `Connection`: one `events()` accessor keeps the interface small
  and puts every registration in one place.
- Running callbacks inside the transaction, as Laravel does: a callback that fails would roll back
  work that was already decided, and one that touches the database would run on a connection that
  is about to close.
- Letting a callback failure roll back the transaction: the commit has already happened.
- Swallowing a listener failure: a broken audit listener would go unnoticed. The failure is thrown,
  with a type that says the statement succeeded.
- An unbounded query log, as in Laravel: a long-running server would run out of memory.
- Logging statements that listeners run: a listener that reads the log or writes to the database
  would recurse or grow the log without end.
- Keeping callbacks of a rolled-back savepoint: the work they announce never happened.
