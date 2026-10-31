# Migrations

A migration is one schema change, written as a Java class with an `up` step and an optional `down`
step. The migrations table records which ones ran and in which batch, so the next run skips them
and a rollback undoes the last batch.

## What you can do

| You want to | Use |
|---|---|
| Write a schema change | extend `Migration`: `name()`, `up(Schema)`, optionally `down(Schema)` |
| Run, roll back and inspect migrations | `Migrator`; see [the migrator](../migrator/README.md) |
| Keep it out of a transaction, skip it, or point it at another connection | override `withinTransaction()`, `shouldRun()`, `connection()` |
| Create, check or drop the migrations table | `createRepository()`, `repositoryExists()`, `deleteRepository()` |
| See what ran | `getRan()`, `getMigrationBatches()` |
| Pick what to roll back | `getLast()`, `getMigrations(steps)`, `getMigrationsByBatch(batch)` |
| Record or forget a run | `log(name, batch)`, `delete(name)`, `getNextBatchNumber()` |

## How it behaves

- Name each migration with its date first, for example `2026_10_31_000000_create_users_table`, and
  never rename one that has run anywhere.
- Migrations are listed by the application; nothing is found by scanning the classpath.
- Inside `up` and `down`, run raw SQL through `schema.connection()`, so it joins the migration's
  transaction.
- Create and drop the table outside a transaction: MySQL commits any open transaction on DDL.
- In pretend mode reads come back empty, so read the table outside the pretend callback.
- Inside one batch, order follows the database's collation.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md).
