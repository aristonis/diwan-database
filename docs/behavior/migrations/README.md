# Migrations

A migration is one schema change, written as a Java class with an `up` step and an optional `down`
step. The migrations table records which ones ran and in which batch, so the next run skips them
and a rollback undoes the last batch.

## What you can do

| You want to | Use |
|---|---|
| Write a schema change | extend `Migration`: `name()`, `up(Schema)`, optionally `down(Schema)` |
| Run, roll back and inspect migrations | `Migrator`; see [the migrator](../migrator/README.md) |
| Watch a run, rollback or failure | `MigrationListener`; see [Migration events](../migrator/README.md#migration-events) |
| Keep it out of a transaction, skip it, or point it at another connection | override `withinTransaction()`, `shouldRun()`, `connection()` |
| Create, check or drop the migrations table | `createRepository()`, `repositoryExists()`, `deleteRepository()` |
| See what ran | `getRan()`, `getMigrationBatches()` |
| Pick what to roll back | `getLast()`, `getMigrations(steps)`, `getMigrationsByBatch(batch)` |
| Record or forget a run | `log(name, batch, checksum)`, `delete(name)`, `getNextBatchNumber()` |
| Protect a migration from edits after it ran | override `checksum()`; see [Checksums](#checksums) |

## How it behaves

- Name each migration with its date first, for example `2026_10_31_000000_create_users_table`, and
  never rename one that has run anywhere.
- Migrations are listed by the application; nothing is found by scanning the classpath.
- Inside `up` and `down`, run raw SQL through `schema.connection()`, so it joins the migration's
  transaction.
- Create and drop the table outside a transaction: MySQL commits any open transaction on DDL.
- In pretend mode reads come back empty, so read the table outside the pretend callback.
- Inside one batch, order follows the database's collation.

## Checksums

A migration can declare a checksum with `checksum()`. The default is empty, which means the
migration is never checked. Return a fixed constant set once for this migration, or a hash of a
canonical description of the change with `MigrationChecksum.of(String)`. A deployed migration is
never edited, so a schema change is always a new migration. That helper gives the SHA-256 in hex
of the text after line endings become `\n` and accents are put in Unicode form NFC, so how the file
was saved does not matter. The library never hashes compiled code, because that changes between
compilers and code shrinkers.

- A run records the declared checksum in the table's `checksum` column, which is nullable and last.
- Before a run (and a pretend run) the migrator compares every migration that already ran and has
  both a recorded and a declared checksum. If any differ, it throws one `MigrationChecksumException`
  that lists each migration with its recorded and current value, and nothing runs.
- A null on either side is never checked. A migration that declares nothing never blocks.
- A migration that ran with no checksum and later declares one gets it recorded on the next run.
- To accept an intended edit, call `migrator.repairChecksums(migrator.migrations())`. To drop every
  recorded checksum, call `migrator.clearChecksums()`. Both take the migration lock and fire no
  events.

See [ADR 0029](../../adr/0029-migration-checksums.md).

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md).
