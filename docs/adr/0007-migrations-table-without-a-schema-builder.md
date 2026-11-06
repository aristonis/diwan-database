# 0007. Migrations table without a schema builder

## Status

Accepted. Points 2 and 4 are superseded by
[ADR 0009](0009-migrator-transactions-and-records.md): the table is now created and checked through
the schema builder, with Laravel's layout. Point 2 below states the table as it is today, with the
`checksum` column added by [ADR 0029](0029-migration-checksums.md); the original wording is kept at
the end under "Superseded text".

## Context

The migrator needs a table that records which migrations ran and in which batch. Laravel creates it
with its schema builder and reads it with its query builder; Diwan Database has neither yet. Laravel also
finds migrations by loading PHP files by name, which Java cannot do, and a classpath scan breaks on
Android and with code shrinkers.

## Decision

1. Migrations are classes extending `Migration`, given to the migrator as an explicit list. The name
   comes from the migration itself.
2. `DatabaseMigrationRepository` creates and checks the table through the schema builder and reads
   and writes it with plain SQL through the `Connection`. The table has an auto-increment `id`, a
   unique `migration varchar(191)`, a `batch integer` and a nullable `checksum varchar(64)`, last.
3. The table name, prefix included, must be lowercase letters, digits and underscores, at most 63
   characters. It is the only text put into SQL; every value is a binding.
4. Whether the table exists is read from the database catalog with a query per database family
   (`DatabaseFamily`): SQLite, PostgreSQL and MySQL/MariaDB. Other databases are refused.
5. Result columns are read by position, and numbers may arrive as any `Number` or as text.
6. `MigrationRepository` is an interface; methods added after 0.1 are default methods.

## Consequences

- Migrations work on Android and after shrinking, at the cost of listing them by hand.
- 191 characters keeps the key indexable on older MySQL with `utf8mb4`, as in Laravel.
- Lowercase names match how PostgreSQL stores unquoted names, so the catalog check finds the table.
- The family-specific SQL (`limit ?`, the catalog queries) moves into the query grammar and the
  schema builder when they arrive. Until then SQL Server and Oracle are not supported.
- Creating or dropping the table must happen outside a transaction, because MySQL commits on DDL.

## Superseded text

Point 2 as first accepted, before the schema builder existed: `DatabaseMigrationRepository` keeps
the table with plain SQL through the `Connection`. The table has `migration varchar(191)` as primary
key and `batch integer`; there is no `id` column. Point 4 as first accepted: whether the table
exists is read from the database catalog with a query per database family (`DatabaseFamily`):
SQLite, PostgreSQL and MySQL/MariaDB; other databases are refused. Both were replaced when the
schema builder arrived, because the builder already knows each database's catalog and column types,
and Laravel's own layout keeps the table readable by anyone who knows Laravel.
