# 0008. Schema grammar per database module

## Status

Accepted

## Context

Laravel's schema builder collects columns and commands in a `Blueprint` and asks a grammar for the
SQL of the connection's database. Diwan Database ships one module per database, and the core must work on
Android and after code shrinking, so it cannot find a grammar by scanning the classpath. Later
releases add indexes, foreign keys, column changes (which SQLite does by rebuilding the table) and
the PostgreSQL and MySQL grammars; each of those must not break grammars written before them.

## Decision

1. The core holds `Schema`, `Blueprint`, `ColumnDefinition`, `ColumnType`, `Command`, `Expression`,
   `SchemaQuery` and the abstract `SchemaGrammar`. Each database module holds its grammar; the first
   is `diwan-database-sqlite` with `SQLiteSchemaGrammar`.
2. The application passes the grammar in: `Schema.of(connection, grammar)`, or a module shortcut such
   as `SQLiteSchema.of(connection)`. No registry and no service loading.
3. A command is a name with read-only attributes. The grammar sends each command to its own compile
   method; every compile method has a body that refuses the command by name, so adding commands
   never breaks an existing grammar. Unknown column types are refused the same way.
4. `compile` and the existence queries receive the `Connection`, so a grammar can read the current
   schema or bind the current database.
5. Names are quoted with embedded quotes doubled; default values are escaped literals; an
   `Expression` is written as given and is for trusted SQL only.
6. Index names are fixed in the blueprint and checked against the grammar's identifier limit.
7. When one call produces several statements, they run in one transaction if the connection is not
   in one and the driver reports transactional DDL.
8. Laravel names that are Java keywords change: `boolean` is `booleanColumn`, `default` is
   `defaultValue`.

## Consequences

- A database module can be added without touching the core, and a module from an older release keeps
  compiling as commands are added.
- Callers write one extra argument (the grammar) compared with Laravel's facade.
- A driver that does not report transactional DDL runs multi-statement calls one by one; a failure
  leaves the earlier statements applied.
