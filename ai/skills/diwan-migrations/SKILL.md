---
name: diwan-migrations
description: Write and run Diwan migrations and change the schema safely. Use when creating a table, altering a column, adding a migration, running or rolling back, or when a checksum error appears.
---

# Diwan migrations

## Steps

1. Create a class that extends `Migration`. Return a fixed, dated string from `name()`.
2. Write `up(Schema)` and `down(Schema)` with `schema.create`, `schema.table`, `schema.drop`.
3. Declare `checksum()` so an edit to a migration that already ran is caught.
4. Add the migration to the `Migrator` builder by hand. Nothing is found by scanning.
5. Call `migrator.run()` at start-up. Use `pretendRun()` to see the SQL first.

## Example

```java
final class CreateUsersTable extends Migration {
    @Override public String name() { return "2026_10_31_000000_create_users_table"; }

    @Override public Optional<String> checksum() { return Optional.of("v1"); }

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

## Pitfalls

- Never edit a migration that ran. Add a new one. If you edited one on purpose, call
  `migrator.repairChecksums(migrator.migrations())` deliberately, once, and say why in the commit.
- Pending migrations run in name order, so start each name with its date. Never rename one that ran.
- Name from a fixed string, never from the class name or compiled bytecode: R8 changes them.
- Raw SQL inside `up` goes through `schema.connection()` so it joins the migration's transaction.
- `MigrationChecksumException` means a ran migration changed. Nothing ran. Restore it or repair.
- `change()` replaces the whole column definition. Restate every modifier you keep.
- The migrator refuses to run inside your own transaction.
- On several processes, plug in a lock: `.lock(new PostgresMigrationLock(dataSource))`.
- Rollback with a recorded migration missing from your list throws `MissingMigrationsException`.

Docs: Schema and Migrations on https://docs.aristonis.org/diwan-database/
