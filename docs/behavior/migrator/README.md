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
- A record that names a migration the app no longer has is reported as not found during rollback
  and kept.
- The migrator refuses to run inside a transaction you opened.
- In pretend mode a migration that checks `hasTable` sees nothing, so its SQL may differ from a real
  run.
- Nothing stops two processes from migrating at once unless you plug in a lock.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md).
