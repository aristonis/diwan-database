# Migrator

The migrator brings a database up to date with the application's migrations, and undoes them
when needed, as `php artisan migrate` does in Laravel.

```java
Migrator migrator = Migrator.builder(SQLiteSchema.of(db))
        .add(new CreateUsersTable())
        .add(new AddBioToUsers())
        .build();

migrator.pretendRun().forEach(result -> System.out.println(result.name() + " " + result.queries()));
migrator.run();          // pending migrations, one batch
migrator.rollback();     // undo the last batch
migrator.status();       // ran, pending, missing
```

## What you can do

| You want to | Call |
|---|---|
| Run pending migrations | `run()`, or `run(true)` for one batch each |
| Undo | `rollback()`, `rollbackSteps(n)`, `rollbackBatch(b)`, `reset()` |
| See where things stand | `status()` |
| Review the SQL first | `pretendRun()`, `pretendRollback()`, `pretendReset()` |
| Run some migrations elsewhere | `builder(...).connection("reports", schema)` and override `connection()` in the migration |
| Keep two processes apart | `builder(...).lock(yourLock)` |

## How it behaves

- Pending migrations run in name order; give names a date prefix.
- On SQLite and PostgreSQL each migration and its record commit together, so a failure leaves no
  half-applied migration and no stray record.
- A failure stops the call with `MigrationFailedException`; `completed()` lists what finished,
  `phase()` tells whether the migration or only its record failed.
- Rollback and reset refuse to start when a record to undo names a migration the app does not have
  (usually older code against a newer database): `MissingMigrationsException` lists the names and
  nothing is undone. `builder(...).allowMissingOnRollback()` restores Laravel's behaviour: the
  known migrations are undone, the unknown records are reported as not found and kept.
- The migrator refuses to run inside a transaction you opened.
- In pretend mode a migration that checks `hasTable` sees nothing, so its SQL may differ from a real
  run.
- Nothing stops two processes from migrating at once unless you plug in a lock.

## Migration events

A `MigrationListener` watches a migrator work, as Laravel's migration events do. Every method is
optional.

```java
Migrator migrator = Migrator.builder(SQLiteSchema.of(db))
        .add(new CreateUsersTable())
        .listener(new MigrationListener() {
            @Override
            public void migrationStarted(MigrationEvent event) {
                System.out.println("running " + event.name());
            }

            @Override
            public void migrationFailed(MigrationEvent event, Throwable failure) {
                System.err.println(event.name() + " failed: " + failure.getMessage());
            }
        })
        .build();
```

Listeners are called in registration order, on the thread that runs the migrator. Through
`DatabaseManager`, `migrationListener(...)` adds a listener to every migrator it hands out.

### The events

| Method | Event | When |
|---|---|---|
| `migrationsStarted` | `MigrationsEvent` | once, before the first migration of a run, rollback or reset, inside the lock |
| `noPendingMigrations` | `MigrationsEvent` | instead of `migrationsStarted`, when there is nothing to run or undo |
| `migrationStarted` | `MigrationEvent` | before one migration runs, before its transaction opens |
| `migrationEnded` | `MigrationEvent` | after the migration ran and its record was written or removed |
| `migrationSkipped` | `MigrationEvent` | going up, when the migration's `shouldRun()` is `false` |
| `migrationFailed` | `MigrationEvent`, `Throwable` | when a migration or its record failed, just before the failure is thrown |
| `migrationsEnded` | `MigrationsEvent` | once, after the last migration of a call that succeeded |

`MigrationsEvent` gives the direction, the pretend flag and the connection name. `MigrationEvent`
adds the migration name and its batch.

- **Direction.** `UP` for `run`, `DOWN` for `rollback`, `rollbackSteps`, `rollbackBatch` and
  `reset`.
- **Batch.** The batch the migration is recorded in, or removed from. It is 0 for a skipped
  migration and for one that is only pretended going up.
- **Pretend.** The pretend calls fire the same events with the pretend flag set. They fire outside
  the scope that collects SQL, so statements a listener runs are not collected.

### When a listener fails

| Event | What a throwing listener does |
|---|---|
| `migrationsStarted`, `migrationStarted` | The call stops before that work starts. Migrations already finished stay applied and recorded. |
| `migrationEnded`, `migrationSkipped`, `noPendingMigrations`, `migrationsEnded` | The exception propagates after the work is done and recorded, so the migrations table and the result list stay correct. The call ends there. |
| `migrationFailed` | The exception is added as suppressed to the `MigrationFailedException` that is thrown. The other listeners still run. |

### A failed migration

A failed migration fires `migrationFailed` and the call ends with `MigrationFailedException`;
`migrationsEnded` does not fire. A failure to write or remove the record fires `migrationFailed`
too. On SQLite a migration that fails on foreign keys is run again with enforcement off: the first
attempt fires no failure, only the second can.

### Differences from Laravel

| Laravel | Here |
|---|---|
| Events go through the application's event dispatcher | Listeners register on the migrator builder; no dispatcher |
| No event for a failure | `migrationFailed` |
| The events carry the migration object | The events carry the name, direction, batch and pretend flag |
| A listener failure is whatever the dispatcher does | The rules above: started stops, ended propagates after recording, failed is suppressed |

Per-statement events are not part of this: `ConnectionListener.queryExecuted` already covers them.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md).
