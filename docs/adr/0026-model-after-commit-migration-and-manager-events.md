# 0026. Model after-commit listeners, migration events and manager wiring

## Status

Accepted

## Context

[0025](0025-connection-events.md) gave a connection listeners and `afterCommit`. Three things
were left: a model listener that waits for the commit, a way to watch a migration run, and one
place to attach listeners to every connection an application uses.

Laravel puts the first in `illuminate/events`, not in the database package: an observer with
`$afterCommit = true`, or a listener implementing `ShouldHandleEventsAfterCommit`, is queued in the
transaction manager and runs after the outermost commit. Laravel fires `MigrationsStarted`,
`MigrationStarted`, `MigrationEnded`, `MigrationSkipped`, `NoPendingMigrations` and
`MigrationsEnded` through the dispatcher, with no event for a failure. Ebean runs its after-commit
persist listeners on a background thread. Flyway and Liquibase both report a failed migration to
their callbacks.

A model write can run on the root connection, on a transaction connection, or inside a transaction
the caller opened outside this library, where the commit is unknown. The manager builds each
connection lazily and may build it again after a purge, so wiring must happen on every build.

## Decision

1. A model after-commit listener uses `afterCommit` of the connection that wrote the model, on the
   caller's thread. No background thread is used, so the thread policy stays with the application.
   `ModelEvents.afterCommit()` returns a view whose registrations wait. Outside a transaction a
   listener runs at once; a rollback drops it.

2. Only the seven events that run after a write can wait: saved, created, updated, deleted,
   softDeleted, restored and forceDeleted. The view does not offer the halting events, because
   their result decides a write that has not happened yet, nor `retrieved` and `replicating`, which
   are not writes. Calling one on the view throws `UnsupportedOperationException` naming the event.

3. An observer opts in with a default method `afterCommit()` that returns `false`. When it returns
   `true`, its seven non-halting write methods wait and every other method runs where it always
   did. The answer is read once, when the observer is registered.

4. A deferred listener sees the model as it is when it runs, not as it was when the event fired,
   as in Laravel. With a retried transaction it runs once, after the attempt that committed.

5. A save, delete, restore, force delete, increment or decrement of a class that has after-commit
   listeners is refused with `IllegalStateException` before any write when the model's connection
   runs inside a transaction the caller opened outside the library.
   `Connection.canDeferUntilCommit()` answers that question without registering anything. Muted events skip the check.

6. A model bound to the root connection while the caller runs a transaction on that root is
   written on its own session, outside the caller's transaction in lenient mode, so the write is
   already committed and the listener runs at once. To join a transaction, bind the model to the
   transaction's connection, as for every other write.

7. `MigrationListener` has default methods: `migrationsStarted`, `migrationsEnded`,
   `noPendingMigrations`, `migrationStarted`, `migrationEnded`, `migrationSkipped` and
   `migrationFailed`. Laravel's names are kept; `migrationFailed` is added. Events carry the
   direction, the connection name, the pretend flag and, for one migration, its name and batch.
   Listeners register on `Migrator.Builder.listener` and run in registration order.

8. A started event that throws stops the call before that work. An ended, skipped or
   no-pending event that throws propagates after the work is done and recorded, so the migrations
   table stays correct. A failing `migrationFailed` listener is added as suppressed to the
   `MigrationFailedException` and never replaces it.

9. Placement: `migrationsStarted` fires inside the migration lock; `migrationStarted` before the
   migration's transaction opens; `migrationEnded` after the migration ran and its record was
   written or removed; `migrationsEnded` only when the whole call succeeded. A failed migration
   fires `migrationFailed` and the call ends there. The first attempt of a migration that is run
   again with foreign keys off fires no failure; only the second can. A failure to write the record
   fires `migrationFailed` too. A pretend call fires the same events with the pretend flag, outside
   the scope that collects SQL, so a listener's statements are not collected.

10. `DatabaseManager.Builder` takes three kinds of listener. `listener` takes a
    `ConnectionListener` that is attached to every connection the manager builds, again after a
    purge or reconnect. `managerListener` takes a `ManagerListener` whose `connectionEstablished`
    gets the connection name, the driver and the connection. `migrationListener` adds a
    `MigrationListener` to every `Migrator.Builder` that `migrator(name)` returns.

11. Attaching and `connectionEstablished` run inside the build of the connection, before it is
    published, so no caller sees a connection without its listeners. A listener that throws fails
    the build: the new connection is closed, the failure reaches the caller that asked, and the
    next call builds again. A listener that asks the manager for the connection being built, on the
    building thread, gets `IllegalStateException` naming it, because the lock is reentrant and the
    call would build a second time.

## Consequences

- A listener that must run only for committed work needs no transaction code of its own.
- A model with after-commit listeners cannot be written inside a transaction the caller owns. The
  refusal is early and names the class, so no row is changed.
- An after-commit listener that throws cannot undo the write; the transaction has ended, as for
  every `afterCommit` callback in 0025.
- A migration listener sees a failure that Laravel's events do not report, so a log or a progress
  bar can end on a clear last line.
- A slow `connectionEstablished` listener delays the first use of that connection, and blocks other
  threads asking for the same name, though not other names.
- Manager wiring happens on every build, so a purge and reconnect keep the same listeners.

## Alternatives rejected

- Delivery on a background thread, as Ebean does: a pool policy belongs to the application, and the
  caller's thread keeps ordering and thread-local state predictable.
- Letting the view register halting events and ignore their result: the listener would look like
  a veto and never be one.
- Writing first and failing in `afterCommit` inside a caller-owned transaction: the row would be
  changed and the listener lost. The check runs before the write.
- A registration flag on `observe` instead of a method on the observer: the observer states its
  own need, as Laravel's property does, and the answer travels with the class.
- A per-statement migration event: `queryExecuted` already covers statements.
- Publishing the connection before its listeners attach: another thread could run statements the
  listeners never see.
- Retiring the holder after a failing `connectionEstablished` listener: one bad start would make the
  name unusable for the rest of the process.
