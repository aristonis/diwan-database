# Database manager and configuration

`DatabaseManager` builds named connections from configuration the first time they are used. It
hands out the `Connection`, the `QueryFactory`, the `Schema` and a `Migrator.Builder` for each
name, and runs seeders (see [seeders](seeders.md)). Configuration is read through a small
interface, so one code path serves a server, a desktop app, a test and Android.

## Start here

On a server, with a properties file and the password in the environment:

```properties
# database.properties
database.default=main
database.connections.main.driver=pgsql
database.connections.main.host=db.internal
database.connections.main.database=shop
database.connections.main.username=shop
database.connections.main.password=${DB_PASSWORD}
database.connections.main.sslmode=require
database.connections.cache.driver=sqlite
database.connections.cache.database=/var/lib/shop/cache.db
```

```java
ConfigSource source = LayeredConfigSource.of(
        EnvConfigSource.system(),                         // wins over the file
        PropertiesConfigSource.of(Path.of("database.properties")));

DatabaseManager db = DatabaseManager.builder()
        .config(DatabaseConfig.from(source))
        .connector(new PostgresConnector(), new SQLiteConnector())
        .build();

QueryFactory shop = db.query();              // the default connection, built now
Schema cache = db.schema("cache");           // built on first use
```

In a test, with no file and an in-memory database:

```java
DatabaseConfig config = DatabaseConfig.from(MapConfigSource.of(Map.of(
        "database.default", "test",
        "database.connections.test.driver", "sqlite",
        "database.connections.test.database", ":memory:")));

try (DatabaseManager db = DatabaseManager.builder()
        .config(config).connector(new SQLiteConnector()).build()) {
    // db.query(), db.schema(), db.migrator("test") ...
}
```

On Android there is no file and no JDBC. Build the configuration in code and register the Android
connector, which takes the query and schema grammars explicitly:

```java
DatabaseConfig config = DatabaseConfig.builder()
        .defaultConnection("app")
        .connection(ConnectionConfig.builder("app").driver("android").build())
        .build();

DatabaseManager db = DatabaseManager.builder()
        .config(config)
        .connector(AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of))
        .build();
```

`helper` is your `SQLiteOpenHelper`. It decides where the database lives, so `url`, `host` and
`database` are ignored. The first connection should be built off the main thread.

## Config sources

| Source | Built with | Reads |
|---|---|---|
| `MapConfigSource` | `MapConfigSource.of(map)` | an immutable copy of the map |
| `PropertiesConfigSource` | `of(Path)`, `of(InputStream)`, `fromClasspath(name)` | a UTF-8 `.properties` file; a read error names the path or resource |
| `EnvConfigSource` | `system()`, `of(map)` | environment variables, with the key mapping below |
| `LayeredConfigSource` | `of(sources...)` | the first source that has the key wins; the keys are the union of all sources |

`PropertiesConfigSource.of(InputStream)` does not close the stream; the caller does. A source's
`toString` lists keys only, never values.

## Keys

All keys start with `database.`. A connection is `database.connections.<name>.<key>`.

| Key | Meaning |
|---|---|
| `database.default` | name of the default connection; required, and it must be configured |
| `...driver` | driver name of a registered connector: `sqlite`, `pgsql` or `android` |
| `...url` | a complete JDBC URL; used instead of `host`, `port` and `database` |
| `...host` | server host name |
| `...port` | server port, a whole number; `pgsql` uses 5432 when it is not set |
| `...database` | database name, or for `sqlite` a file path or `:memory:` |
| `...username` | user name |
| `...password` | password; masked wherever the configuration is printed |
| `...prefix` | table prefix, empty by default |
| `...strict_transactions` | `true` or `false`; `true` by default |
| `...<anything else>` | kept as a string option and given to the connector |

Options the shipped connectors read:

| Driver | Option | Meaning |
|---|---|---|
| `sqlite` | `foreign_keys` | `true` or `false`; on by default, also added to a `url` unless it sets it |
| `sqlite` | `busy_timeout` | milliseconds a second in-memory session waits; 30000 by default |
| `pgsql` | `sslmode` | added to the URL as `sslmode` |
| `pgsql` | `search_path` or `schema` | added to the URL as `currentSchema` |

A configuration error is a `DatabaseConfigException`, a subclass of `IllegalArgumentException`. It
names the connection and the key. It never contains the value, so a mistyped secret stays out of
logs. A connection that is configured twice, or a default that is not configured, is refused when
the configuration is built.

## Variables in values

A value can read the environment, or any other source you pass as the second argument of
`DatabaseConfig.from(source, vars)`.

| Write | Result |
|---|---|
| `${DB_PASSWORD}` | the value of `DB_PASSWORD`; the load fails if it is not set |
| `${DB_HOST:-localhost}` | the value of `DB_HOST`, or `localhost` when it is not set |
| `$${DB_PASSWORD}` | the text `${DB_PASSWORD}`, not replaced |

A value is resolved in one pass: text that a variable brings in is not read again. An unclosed
`${` or an empty `${}` fails the load. A `${` inside a reference, such as a nested fallback
`${A:-${B}}`, is refused with an error that names the key. The failure names the key and, when it
is a missing variable, the variable name.

## The environment as a layer

`EnvConfigSource` finds a key by its exact name first, then by the upper-case form with each dot
turned into an underscore.

| Config key | Environment variable |
|---|---|
| `database.default` | `DATABASE_DEFAULT` |
| `database.connections.main.password` | `DATABASE_CONNECTIONS_MAIN_PASSWORD` |
| `database.connections.main.host` | `DATABASE_CONNECTIONS_MAIN_HOST` |

Put it first in a `LayeredConfigSource` and a variable overrides the same key in the file, so a
deployment changes a password or a host without touching the file. An environment layer does not
create a connection: connection names and keys are discovered from the sources that list them as
written, so the connection must exist in the file or the map. For a secret you read, prefer
`${DB_PASSWORD}` in the file, or a layer.

## Layering

```java
LayeredConfigSource.of(
        MapConfigSource.of(overrides),                     // in code, highest
        EnvConfigSource.system(),                          // deployment
        PropertiesConfigSource.fromClasspath("database.properties"));   // defaults
```

The first source that has a key supplies it. The set of connection names is the union over all
sources, so a lower layer can define a connection that a higher layer adjusts.

## Using connections

| You want | Call |
|---|---|
| the default connection, or a named one | `connection()`, `connection(name)` |
| a query factory | `query()`, `query(name)` |
| a schema builder | `schema()`, `schema(name)` |
| a migrator already tied to that connection's schema | `migrator(name)`, which returns a `Migrator.Builder` |
| the default name | `getDefaultConnection()`, `setDefaultConnection(name)` |
| names built so far | `getConnections()` |
| whether a name is configured, built or not | `hasConnection(name)` |
| run a block against another connection | `usingConnection(name, work)` |
| seed the database | `seed(supplier)`, `seed(supplier, options)` |

An unknown connection name, or a driver with no registered connector, throws
`IllegalArgumentException` that lists the known names.

### Connection lifecycle

- A connection is built lazily, on its first use, and once per name, even when several threads ask
  at the same moment. A slow connect for one name does not block another name.
- `purge(name)` and `disconnect(name)` drop the built connection and close what it owns, including a
  data source that is `AutoCloseable`. The configuration stays. The next call builds a new one.
- `reconnect(name)` purges, then builds and returns a new `Connection`.
- `close()` retires every connection. It is idempotent. Afterwards every call throws
  `IllegalStateException`. Use the manager in try-with-resources in a test.
- A session that is running when its connection is purged, disconnected or closed can fail with the
  driver's own error. Laravel behaves the same way. Do not purge a connection that is busy.
- An in-memory SQLite database lives until the connection is purged or the manager is closed.
  `:memory:` and the user urls `jdbc:sqlite::memory:`, `jdbc:sqlite:file::memory:`, `jdbc:sqlite:`
  and any url with `mode=memory` use one real connection, lent to one session at a time. A second
  session waits up to the `busy_timeout` option (milliseconds, default 30000), then fails with
  `In-memory SQLite database [name] is busy: one session at a time`. A second session on the same
  thread while one is open waits the full timeout and fails. A seeder that calls `manager.query()`
  inside its own transaction does this; use the `SeedContext` `query()` and `schema()` instead.
- `hasConnection(name)` is true for every configured name, built or not. The manager-backed
  `ConnectionResolver.hasConnection` uses it.

### usingConnection

```java
db.usingConnection("cache", () -> {
    db.query().table("entries").insert(row);       // goes to "cache"
});
```

The override applies to the calling thread only, nests, and ends when the block returns or throws.
It does not cross threads: work you hand to an executor inside the block sees the manager's normal
default. Pass the connection name to that work instead.

### No pooling

A connection built from configuration uses `DriverManager`, so each checkout opens a new JDBC
connection. That suits a test, a desktop app and a small tool. A server should give the manager a
pooled `DataSource` for the name. This matters on an application server too: `DriverManager` may
not see a driver loaded by a webapp class loader, and it keeps drivers registered across
redeploys.



```java
DatabaseManager db = DatabaseManager.builder()
        .config(config)
        .connector(new PostgresConnector())
        .dataSource("main", () -> pooledDataSource)    // any pool; closed on purge or close
        .build();
```

The supplier runs when the connection is first built. A data source that implements
`AutoCloseable` is closed with the connection. A name that is not configured is refused when the
manager is built.

## Manager listeners and connection established

```java
DatabaseManager db = DatabaseManager.builder()
        .config(config)
        .connector(new PostgresConnector())
        .listener(new SlowQueryLogger())             // every connection, now and after a rebuild
        .managerListener(new ManagerListener() {
            @Override
            public void connectionEstablished(ConnectionEstablished event) {
                log.info("connected " + event.connectionName() + " via " + event.driver());
            }
        })
        .migrationListener(new MigrationProgressBar())
        .build();
```

| Builder method | Takes | Effect |
|---|---|---|
| `listener(l)` | a `ConnectionListener` | attached to the events of every connection the manager builds |
| `managerListener(l)` | a `ManagerListener` | told after each connection is built |
| `migrationListener(l)` | a `MigrationListener` | added to every migrator builder `migrator(name)` returns |

All three can be repeated and run in registration order. A null listener is refused with
`NullPointerException`.

### connectionEstablished

- **Timing.** The connection is built, the manager's connection listeners are attached, and then
  `connectionEstablished` fires, all before any caller can see the connection. The event gives the
  connection name, the driver name and the new `Connection`, which the listener may use.
- **Again after a rebuild.** It fires each time a connection is built, so also after `purge`,
  `disconnect` and `reconnect`. Connection listeners attach again too, so a purge never loses them.
- **Failure.** A listener that throws fails the build. The new connection is closed, the failure
  reaches the caller that asked for the connection, and the name stays usable: the next call builds
  again and fires the listeners again. A close that fails too is added as suppressed.
- **Re-entry.** A listener that asks the manager for the connection being built, on the building
  thread, gets `IllegalStateException` naming the connection. Use the connection in the event
  instead. Asking for another name works, but a listener should not call the manager at all: two
  connections whose listeners ask for each other on different threads can deadlock.
- **Cost.** The listener runs while the connection is being built, so keep it short. Threads that
  ask for the same name wait for it; other names are not blocked.

### Migration listeners

`migrator(name)` returns a builder that already carries the manager's migration listeners, after
the connection and lock are set up. A listener you add on that builder with `listener(...)` runs
after the manager's. See [Migration events](../migrator/README.md#migration-events).

## Passwords

The password is passed to the JDBC driver as a property, never as part of the URL.
`ConnectionConfig` prints `***` for it, and for any option whose key names a secret. A `password=`
value or a `user:pass@` part inside a URL is masked in `toString` and in error messages.
`ConnectionConfig.mask` applies the same rule to any text you log. `DriverManagerDataSource` masks
the password in its own `toString` too. An error from `DriverManager` is rethrown with the
password masked, including `user:pass@` and `pwd` or `passwd` values.

## Eloquent

```java
ConnectionResolver resolver = ConnectionResolver.of(db);
ConnectionResolver.setDefault(resolver);
```

A model that names a connection with `connectionName()` resolves it through the manager, so the
connection is built the first time a model uses it. A model that names none gets the manager's
default, including a `usingConnection` override on the current thread. The resolver holds no state
of its own: `register` and `setDefaultConnection` on it throw `UnsupportedOperationException`.
Change the manager instead.

## Differences from Laravel

| Laravel | Here | Reason |
|---|---|---|
| `config/database.php` array and `.env` | `ConfigSource` with map, properties and environment adapters | no PHP config; works without a file |
| drivers found by name in the connection factory | connectors registered explicitly | R8 and no reflection |
| `DatabaseManager` fires events through the dispatcher; there is no event when a connection is built | `connectionEstablished` on a `ManagerListener`, fired before the connection is published | no dispatcher; one place to attach listeners |
| `usingConnection` changes the global default | changes the default for the calling thread | concurrent servers |
| connections are PDO objects built from an array | `Connection` built by a `Connector` from a typed `ConnectionConfig` | typed, checked config |
| `disconnect` drops the PDO and keeps the config | same; also closes an `AutoCloseable` data source | same |
| `url` key parsed for every driver | `url` is a complete JDBC URL | simpler; the driver parses it |
| a failed config value shows in the error | the key and connection are named, the value never is | secrets stay out of logs |

## Not in this release

- Connection pooling. Pass a pooled `DataSource` for the name.
- Read and write connection split.
- A `db:seed` console command.
- YAML, JSON or XML config files.
- Per-environment sections in a config file. Layer files and the environment instead.
- `connectUsing` with a name made up at run time.
- MySQL and SQL Server connectors.

## More

- [Seeders](seeders.md)
- [Connection](README.md)
- [Decision record](../../adr/0024-database-manager-config-seeders.md)
- [Decision: manager wiring](../../adr/0026-model-after-commit-migration-and-manager-events.md)
