# System design

Photisma Database is a hand port of Laravel's database layer (`illuminate/database`) to Java. It
keeps Laravel's architecture layers and names where they read well in Java and uses Java's own
idioms where PHP shaped Laravel's choices.

## Layers

| Layer | Holds | Where it is described |
|---|---|---|
| Connection | Running SQL, transactions, pretend mode, errors | [connection-design.md](connection-design.md) |
| Driver interface and adapters | One physical connection per session; JDBC adapter; Android adapter later | [connection-design.md](connection-design.md) |
| Schema | Blueprint, column definitions, schema builder (built); index and foreign key definitions later; a grammar per database module | [ADR 0008](../adr/0008-schema-grammar-per-database-module.md), [requirements](../requirements/schema.md) |
| Migrations | Migration contract, migrations table and migrator (built); commands later | [ADR 0007](../adr/0007-migrations-table-without-a-schema-builder.md), [ADR 0009](../adr/0009-migrator-transactions-and-records.md), [requirements](../requirements/migrator.md) |
| Query builder | Fluent queries, query grammars and processors | not built yet |
| Models | Active-record models, relations, casts | not built yet |

## Platforms

Java 17 bytecode with no runtime dependency, so the same JAR runs on Java 17 and later desktop and
server runtimes, inside Spring applications, and on Android 9 (API 28) and later through a driver
that does not need JDBC.

## Modules

| Module | Artifact | Holds |
|---|---|---|
| `core` | `org.aristonis:photisma-database` | The connection, the driver interface, the JDBC adapter, migrations and the schema builder; later layers join it when built |
| `sqlite` | `org.aristonis:photisma-database-sqlite` | The SQLite schema grammar |
| one per further database (planned) | `org.aristonis:photisma-database-<driver>` | That database's grammars and driver details |

## Diagram: Photisma Database layers

```mermaid
flowchart TB
    Models --> Query[Query builder]
    Query --> Conn[Connection]
    Migrations --> Schema
    Schema --> Conn
    Migrations --> Conn
    Conn --> Driver[Driver interface]
    Driver --> JDBC[JDBC adapter]
    Driver --> Android[Android adapter]
```
