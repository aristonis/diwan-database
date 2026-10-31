# Migrations: user stories

### Write a migration as a Java class

As an application developer, I want to write each schema change as a class with an up and a down
step, so that I can add and undo changes the way I do in Laravel.

Done when: a class with only an up step is a valid migration.

### Remember what already ran

As the migrator, I want a table that lists each migration that ran and its batch, so that a second
run skips them and a rollback undoes the last batch.

Done when: entries can be logged, read in Laravel's order, and removed.

## Turned down

| Story | Reason |
|---|---|
| Find migrations by scanning the app | Breaks on Android and with code shrinkers |
| Two machines migrating at once safely | Needs a lock; comes with the migrator or later |
