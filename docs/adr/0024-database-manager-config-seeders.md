# 0024. Database manager, configuration and seeders

## Status

Accepted

## Context

An application used to build each `Connection`, `QueryFactory` and `Schema` by hand and pass them
around. Laravel does this once: a `DatabaseManager` reads `config/database.php`, builds a named
connection the first time it is used, and a `Seeder` class fills the tables.

Diwan Database has no PHP config array, no `.env` loader, no container and no console. It runs on
servers, on desktops, in tests and on Android, where R8 renames classes and removes anything found
by name, and there is no file to read. Laravel's seeder keeps its list of already-called seeders in
a static property that is never cleared, so a second run in the same process skips every
`callOnce` seeder. Laravel's `usingConnection` swaps the one global default, which another thread
in a server sees.

## Decision

1. Configuration is read through the `ConfigSource` interface: `get(key)` and `keys(prefix)`.
   Adapters cover a map, a `.properties` file, the environment, and a first-match-wins stack of
   sources. The core reads keys under `database.` and needs no file system, so the same code runs
   on Android, on a server and in a test.

2. `${NAME}`, `${NAME:-fallback}` and the escape `$${` are resolved in each value from a second
   `ConfigSource`, the environment by default. A missing variable without a fallback fails the
   load with the key and the variable name and never the value.

3. A database is reached through a `Connector`, one per driver name, registered explicitly on the
   manager. The core depends on no JDBC driver and uses no `ServiceLoader` and no reflection.
   `SQLiteConnector`, `PostgresConnector` and `AndroidConnector` live in their own modules. The
   name `Connector` avoids a clash with the core's low-level `Driver`.

4. Connections are built lazily, once per name. Each name has its own holder with its own lock, so
   a slow connect for one name never blocks another, and nothing runs SQL under the map's lock.

5. A connection built from config uses a `DriverManager` data source. The user name and password
   are passed as properties and never placed in the URL. There is no pool; a server that wants one
   registers its own `DataSource` for the name. `DriverManager` may not see drivers loaded by a
   webapp class loader and keeps them registered across redeploys, which is another reason. Its
   errors are rethrown with the password masked.

6. `usingConnection` changes the default for the calling thread only and nests.

7. A password is masked in every string form of the configuration, in a URL that carries one, and
   in error messages.

8. Eloquent reaches the manager through `ConnectionResolver.of(manager)`. Lookups are lazy and the
   default name follows the manager, including `usingConnection`. `register` and
   `setDefaultConnection` on that resolver throw, because the manager owns that state.

9. An in-memory SQLite database (`:memory:`, or a user url such as `jdbc:sqlite::memory:`,
   `jdbc:sqlite:file::memory:`, `jdbc:sqlite:` or any `mode=memory`) uses one real connection, kept
   until the connection is purged or the manager is closed and lent to one session at a time.
   Each JDBC connection would otherwise see its own empty database. A second session waits up to
   the `busy_timeout` option (default 30000 ms), then fails with a message that the database is
   busy. A second session on the same thread, such as a seeder calling `manager.query()` inside its
   own transaction, waits the full timeout and fails; seeders use the context's `query()` and
   `schema()`. `foreign_keys` is also appended to a user url unless it sets it.
   `hasConnection(name)` is true for configured names, built or not.

10. A seeder is a class with `run(SeedContext)`, created by a supplier. The record of seeders
    already called lives in the run, not in a static field, so every top-level seed starts clean.
    A seeder reached again while it is still running throws `IllegalStateException` naming the
    chain.

11. Seeding is not wrapped in a transaction unless `SeedOptions.inTransaction(true)` is set. When
    it is, the context's `query()`, `schema()` and `connection()` and every nested call use the
    transaction's connection.

## Consequences

- Tests build a manager from a `MapConfigSource` and an in-memory database, with no file.
- A deployment overrides any key from the environment with no code change, by layering the
  environment above a file.
- Two threads can use different default connections at once. Work handed to another thread does not
  inherit the override.
- A server that does not register a pooled `DataSource` opens a new JDBC connection per checkout.
  This is documented and is the caller's choice to change.
- Purging, disconnecting or closing while a session is running can make that session fail with the
  driver's error, as in Laravel.
- A seed run that does not use a transaction leaves earlier rows in place when a later seeder
  fails, as in Laravel.
- Seeders are found by supplier, so a seeder cannot be named by a string.

## Alternatives rejected

- YAML, JSON or XML config: each needs a parser dependency and none works on every platform.
- Per-environment sections in a properties file: layering a file under the environment does the
  same job with no section rules.
- Discovering drivers with `ServiceLoader` or class names: R8 removes them on Android.
- Building the connection inside the map's `computeIfAbsent`: a slow connect would block every
  other name and could deadlock on a re-entrant lookup.
- A process-wide `usingConnection`: one request would redirect every other thread's queries.
- Keeping the called-seeders record in a static field: a second run in the same process, such as
  the next test, would silently skip seeders.
- Seeding in a transaction by default: Laravel does not, and a transaction around a large seed
  changes locking and memory behavior the caller did not ask for.
- A built-in pool: it adds a dependency or a large amount of code, and the user can pass any
  `DataSource`.
