# Migrator requirements

What the migrator must do, observed from outside. The reasons are in
[ADR 0009](../adr/0009-migrator-transactions-and-records.md); the detailed contract of each type is
its Javadoc.

## 1. Problem

Migrations, the migrations table and the schema builder exist; the migrator runs pending
migrations in order, records them in batches and rolls them back.

## 2. Functional requirements

Setup

- Built from a default `Schema`, optional named `Schema`s, the migrations, a table name and a lock.
- Before anything runs, the list is checked: valid names, no name twice, every named connection
  known.

Run

- `run()` runs every migration not yet recorded, in name order, in one new batch; `run(true)` gives
  each its own batch, skipped ones included. Nothing pending: nothing happens.
- The migrations table is created first when missing.
- A migration whose `shouldRun()` is false is skipped and not recorded.
- A migration runs in its own transaction when it asks for one and the database can undo schema
  changes; on the migrations table's connection its record joins that transaction.
- A failure stops the call: the failing migration is undone where it ran in a transaction, earlier
  ones stay recorded, later ones do not run.

Rollback and reset

- `rollback()` undoes the last batch, `rollbackSteps(n)` the last n migrations, `rollbackBatch(b)`
  one batch, `reset()` everything, newest first.
- A recorded name the migrator was not given refuses the whole call before anything is undone,
  unless the builder allows it; then it is reported as not found and kept.
- Without a table or records, nothing happens.

Status and pretend

- `status()` lists each migration as ran or pending with its batch, then records with no migration.
- `pretendRun()`, `pretendRollback()` and `pretendReset()` return each migration's SQL and change
  nothing, not even creating the table.

Errors and safety

- Calls refuse to start inside an open transaction.
- Failures are `MigrationFailedException` with name, direction, phase and the finished results.

## 3. Non-goals

A command line, migration stubs, refresh and fresh, events, a database lock implementation, a
connection manager, schema dumps.
