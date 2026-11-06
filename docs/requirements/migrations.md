# Migrations requirements

What the migration contract and the migrations table must do, observed from outside. The reasons
behind the choices are in [ADR 0007](../adr/0007-migrations-table-without-a-schema-builder.md); the
detailed contract of each type is its Javadoc.

## 1. Problem

The migrator must know which migrations already ran, and in which batch, so it runs only new ones
and rolls back the right ones. Laravel keeps this in a `migrations` table and loads PHP files by
name. Java has no files to load by name, and Diwan Database has no query builder or schema builder yet.

## 2. Goal

A `Migration` type the application extends, and a `MigrationRepository` that keeps the migrations
table through the schema builder and its connection, with Laravel's operations and ordering.

## 3. Functional requirements

Migration contract

- A migration gives a name, `up(Schema)` and optionally `down(Schema)`, which does nothing by
  default. The schema builder it receives is bound to the migration's transaction.
- A migration says whether it runs inside a transaction (default yes), whether it should run
  (default yes), and optionally the connection it targets (default none).
- A migration name is non-blank, at most 191 characters, without control characters; an invalid
  name is refused when it is logged.

Repository

- Created from a `Schema` and a table name (default `migrations`), with the connection's table
  prefix in front. The full name must be lowercase letters, digits and underscores, not starting
  with a digit, at most 63 characters.
- `createRepository()` creates the table with Laravel's layout (`id`, unique `migration` of up to
  191 characters, `batch`); `repositoryExists()` reports whether it exists; `deleteRepository()`
  drops it.
- `getRan()` returns every name, ordered by batch then name, ascending.
- `getMigrations(steps)` returns up to `steps` entries, ordered by batch then name, descending;
  `steps` below 1 is refused.
- `getMigrationsByBatch(batch)` and `getLast()` return one batch, by name descending.
- `getMigrationBatches()` returns name to batch in `getRan()` order.
- `log(name, batch, checksum)` records a migration, with a `null` checksum for none; a second `log`
  of the same name fails with `UniqueConstraintViolationException`. `delete(name)` removes it; an
  absent name changes nothing.
- `getLastBatchNumber()` is 0 on an empty table; `getNextBatchNumber()` is one more.
- Returned lists and maps cannot be modified.

## 4. Non-functional requirements

- Values are always bindings; only the checked table name is part of the SQL text.
- Runs on Java 17 and Android 9 (API 28), with no runtime dependency.
- Tested against SQLite; the SQL is written for PostgreSQL and MySQL as well.

## 5. Non-goals

The migrator itself, the migration creator and its stubs, a command line, a lock between two
processes that migrate at once, choosing a connection by name, an `id` column, SQL Server and
Oracle.
