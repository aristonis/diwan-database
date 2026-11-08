---
name: diwan-migrations
description: Write and run Diwan migrations and change the schema safely. Use when creating a table, altering a column, adding a migration, running or rolling back, seeding a database, or when a checksum error appears.
---

# Diwan migrations

## Steps

1. Create a class that extends `Migration`. Return a fixed, dated string from `name()`.
2. Write `up(Schema)` and `down(Schema)` with `schema.create`, `schema.table`, `schema.drop`.
3. Declare `checksum()` as a fixed constant for that migration, set once. It detects an accidental
   edit; it is not a version.
4. Add the migration to the `Migrator` builder by hand. Nothing is found by scanning.
5. Call `migrator.run()` at start-up. Use `pretendRun()` to see the SQL first.
6. One migration runs on every database. Never branch on the driver to write two versions of one
   rule. A DDL feature the builder lacks: enforce it in application code first; only when the
   database itself must have it (for example an index for speed), write it through
   `schema.connection()` and report the gap.

## Example

```java
final class CreateUsersTable extends Migration {
    @Override public String name() { return "2026_10_31_000000_create_users_table"; }

    @Override public Optional<String> checksum() { return Optional.of("1"); } // fixed, never changed

    @Override public void up(Schema schema) {
        schema.create("users", table -> {
            table.id();
            table.string("email").unique();
            table.timestamps();
        });
    }

    @Override public void down(Schema schema) { schema.drop("users"); }
}

Migrator migrator = manager.migrator("main")
        .add(new CreateUsersTable())
        .build();
migrator.run();
```

## Seeders

Extend `Seeder` and override `protected void run(SeedContext context)`. Inside, use
`context.query().table("roles").insertOrIgnore(Map.of("name", "admin"))` and
`context.call(RolesSeeder::new)`. Run with `manager.seed(DatabaseSeeder::new)` (no transaction by
default) or `manager.seed(DatabaseSeeder::new, SeedOptions.defaults().inTransaction(true))`.
`callOnce` skips a seeder already run in this run; `callSilent` hides it from the listener.

## Pitfalls

- A deployed migration is locked: never edit it and never change its checksum; add a new migration.
  `repairChecksums` is recovery only: a local database that was never deployed, or a known accident.
- Pending migrations run in name order, so start each name with its date. Never rename one that ran.
- Name from a fixed string, never from the class name or compiled bytecode: R8 changes them.
- When raw DDL is unavoidable, run it through `schema.connection()` so it joins the migration's
  transaction, and report the missing builder call.
- `MigrationChecksumException` means a ran migration changed. Nothing ran. Restore the file and
  add a new migration; repair only as recovery.
- `change()` replaces the whole column definition. Restate every modifier you keep.
- The migrator refuses to run inside your own transaction.
- On several processes, plug in a lock: `.lock(new PostgresMigrationLock(dataSource))`.
- Rollback with a recorded migration missing from your list throws `MissingMigrationsException`.

Docs: Schema and Migrations on https://aristonis.org/open-source/diwan-database/docs/main/home
