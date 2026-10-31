# Schema builder requirements

What the schema builder must do, observed from outside. The reasons behind the choices are in
[ADR 0008](../adr/0008-schema-grammar-per-database-module.md); the detailed contract of each type is
its Javadoc.

## 1. Problem

Migrations change tables. Laravel does it with `Schema::create('users', fn (Blueprint $table) => ...)`:
the blueprint collects columns and commands, a grammar per database turns them into SQL, and the
builder runs the SQL through the connection.

## 2. Goal

A schema builder and blueprint with Laravel's names for the common columns, compiling to SQL through
a grammar in each database's module, so migrations can create, alter, rename, drop and inspect
tables.

## 3. Functional requirements

Schema builder

- Made from a `Connection` and a `SchemaGrammar`.
- `create(table, callback)` creates a table; `table(table, callback)` changes one.
- `drop`, `dropIfExists`, `rename(from, to)`.
- `hasTable(table)` and `hasColumn(table, column)`; column names compare without letter case.
- The connection's table prefix is added to every table name.
- In pretend mode statements are recorded and nothing runs.
- Several statements from one call run in one transaction when the database can undo schema
  changes and no transaction is open.

Columns, with Laravel's defaults

- `id()` (auto-increment big integer primary key), `string` (255), `text`, `integer`, `bigInteger`,
  `booleanColumn`, `decimal` (8, 2), `date`, `timestamp`, `json`, `timestamps()` (nullable
  `created_at` and `updated_at`).
- Modifiers: `nullable`, `defaultValue` (text, numbers, booleans, `Expression`, or `null` for SQL
  `NULL`), `useCurrent` on date and timestamp, `unique` and `unique(name)`, `autoIncrement` on
  integer columns, `unsigned` and `comment` (ignored on SQLite).

Indexes and keys

- `index`, `unique` and `primary` over one or more columns, on the blueprint or on a column, with
  Laravel's default names (`<table>_<columns>_<type>`, lower case, `-` and `.` as `_`) or a chosen
  name.
- `foreign(columns).references(columns).on(table)` with `onDelete` and `onUpdate` and Laravel's
  helpers (`cascadeOnDelete`, `restrictOnDelete`, `nullOnDelete`, `noActionOnDelete` and the
  `OnUpdate` four).
- `foreignId(column)` with `constrained()`, which names the table from the column (`user_id` to
  `users`), or `constrained(table)` and `constrained(table, column)`.
- `dropIndex`, `dropUnique`, `dropPrimary` and `dropForeign` by name or by columns;
  `dropConstrainedForeignId`; `renameIndex`.
- `dropColumn` and `renameColumn`. A column an index or key still uses is refused before any SQL.
- `indexes(table)` and `foreignKeys(table)` list what the database has.
- `withoutForeignKeyConstraints(work)` runs work in one transaction with enforcement off;
  `pretend(work)` records the statements while reading the live schema.

SQLite

- Keys on a new table are written into its create statement.
- Adding or dropping a primary or foreign key on an existing table rebuilds it: rows, indexes,
  unique constraints and the auto-increment counter are kept; rows that break a key undo the change
  with `ForeignKeyCheckException`.
- A rebuild never applies other tables' delete actions: outside a transaction it runs with
  enforcement off on one connection; inside a transaction with enforcement on, a table other tables
  point at is refused. The migrator then runs that migration again with enforcement off and checks
  every table before commit; other migrations keep enforcement on.
- Tables with a CHECK constraint, COLLATE, WITHOUT ROWID, a generated column, a trigger, a view
  over them, or a partial or expression index are refused by the rebuild.
- `renameColumn` and `dropColumn` run in place on SQLite 3.25 and 3.35 and later, and rebuild the
  table on older versions such as Android 9's, where a column another table references is refused.
- `dropForeign` by name is refused, since SQLite keys have no names.

Safety and errors

- Names are quoted with embedded quotes doubled; empty names and control characters are refused.
- Default values are escaped literals; `Expression` is trusted SQL written as given.
- A table without columns, a column added twice, a repeated index name, an index name over the
  database's limit, a string length below 1 or invalid decimal digits are refused before any SQL.
- A command or type a grammar does not support fails with an error naming it and the grammar.
- A failing statement raises the connection's `QueryException`.

## 4. Non-functional requirements

- The core does not depend on any database module.
- Java 17 and Android 9 (API 28) in every module, with no runtime dependency.
- Every statement the SQLite grammar produces is run against SQLite in the tests.

## 5. Non-goals

Fulltext, spatial, partial and expression indexes, changing a column's type, `foreignIdFor`,
uuid and ulid keys, other column types, the PostgreSQL and MySQL grammars, temporary tables,
schema-qualified names, listings of tables and columns.
