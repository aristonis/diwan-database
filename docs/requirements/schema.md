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
- `create(table, callback)` creates a table; `table(table, callback)` adds columns to one.
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

Indexes other than a column's `unique`, foreign keys, changing, renaming or dropping columns, other
column types, the PostgreSQL and MySQL grammars, temporary tables, schema-qualified names, listings
of tables and columns, the migrator.
