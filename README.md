# Diwan Database

Diwan Database is a hand port of Laravel's database layer (`illuminate/database`) to Java. It
keeps Laravel's layers and method names where they read well in Java, and uses Java's own idioms
(`Optional`, `java.time`, try-with-resources, unchecked exceptions) where PHP shaped Laravel's
choices.

## Current scope

The connection layer is in place: running SQL, nested transactions with savepoints, retry on
deadlocks, joining a transaction someone else owns, pretend mode, and errors that keep personal
data out of logs. It runs over a small driver interface; a JDBC adapter ships in the core. Migrations run through the `Migrator`: run, rollback, reset, status and pretend, one transaction per
migration where the database allows. The schema builder creates and changes tables through a grammar per database;
the SQLite grammar ships in `diwan-database-sqlite`.

| Layer | State |
|---|---|
| Connection, driver interface, JDBC adapter | available |
| Schema builder: common columns, SQLite grammar | available |
| Migrations: contract, migrations table and migrator | available |
| Query builder | planned |
| Models | planned |

## Requirements

- Java 17 or later. The JAR is plain Java 17 bytecode and is tested on JDK 17, 21 and 25.
- Android 9 (API 28) or later, through a driver that does not need JDBC (planned). The build
  checks the core against the Android 28 API.
- Inside Spring, through a `JdbcConnectionProvider` that reports Spring's transaction, so
  Diwan Database nests inside it with savepoints instead of committing it.
- No runtime dependency.

## Coordinates

```kotlin
implementation("org.aristonis:diwan-database:0.1.0")
```

For SQLite schema building, add the SQLite module:

```kotlin
implementation("org.aristonis:diwan-database-sqlite:0.1.0")
```

Not yet published. Build it locally with `./gradlew check`.

## Usage

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.TransactionScope;

Connection db = Connection.builder()
        .name("main")
        .dataSource(dataSource) // any javax.sql.DataSource, pooled or not
        .build();

db.statement("create table users (id integer primary key, email text not null unique)");
db.insert("insert into users (email) values (?)", "ada@example.com");

List<Map<String, Object>> rows = db.select("select id, email from users where id > ?", 0);
Optional<Map<String, Object>> ada = db.selectOne("select email from users where id = ?", 1);

// Commits when the callback returns, rolls back when it throws,
// and runs again up to 3 times on a deadlock.
int removed = db.transaction(tx -> {
    tx.update("update users set email = ? where id = ?", "ada@example.org", 1);
    return tx.delete("delete from users where email like ?", "%@spam.test");
}, 3);

// Rolls back on close unless commit() succeeded.
try (TransactionScope tx = db.beginTransaction()) {
    tx.insert("insert into users (email) values (?)", "grace@example.com");
    tx.commit();
}

// Records the SQL without touching the database.
List<LoggedQuery> plan = db.pretend(p -> p.statement("drop table users"));
```

Inside a transaction, pass the connection you were handed (`tx`) to everything the work calls. By
default the outer connection refuses calls from inside its own transaction, because they would run
outside it.

### Schema builder

```java
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

Schema schema = SQLiteSchema.of(db);
schema.create("users", table -> {
    table.id();
    table.string("email").unique();
    table.booleanColumn("active").defaultValue(true);
    table.timestamps();
});
schema.create("posts", table -> {
    table.id();
    table.foreignId("user_id").constrained().cascadeOnDelete();
    table.string("title");
    table.index("user_id", "title");
});
```

On SQLite, adding or dropping a key on a filled table rebuilds it and keeps every row.

### Migrations

```java
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;

final class CreateUsersTable extends Migration {
    @Override
    public String name() {
        return "2026_10_31_000000_create_users_table";
    }

    @Override
    public void up(Schema schema) {
        schema.create("users", table -> {
            table.id();
            table.string("email").unique();
        });
    }

    @Override
    public void down(Schema schema) {
        schema.drop("users");
    }
}

Migrator migrator = Migrator.builder(SQLiteSchema.of(db))
        .add(new CreateUsersTable())
        .build();
migrator.run();       // creates the migrations table, runs what is pending
migrator.rollback();  // undoes the last batch
```

## Documentation

- [System design](docs/architecture/system-design.md)
- [Connection design](docs/architecture/connection-design.md)
- [Connection requirements](docs/requirements/connection.md)
- [Connection behavior](docs/behavior/connection/README.md)
- [Schema builder requirements](docs/requirements/schema.md)
- [Schema builder behavior](docs/behavior/schema/README.md)
- [Migrations requirements](docs/requirements/migrations.md)
- [Migrations behavior](docs/behavior/migrations/README.md)
- [Migrator requirements](docs/requirements/migrator.md)
- [Migrator behavior](docs/behavior/migrator/README.md)
- [Decision records](docs/adr/)
- [Documentation standard](docs/documentation-standard.md)
- [Changelog](CHANGELOG.md)
