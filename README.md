# Photisma Database

Photisma Database is a hand port of Laravel's database layer (`illuminate/database`) to Java. It
keeps Laravel's layers and method names where they read well in Java, and uses Java's own idioms
(`Optional`, `java.time`, try-with-resources, unchecked exceptions) where PHP shaped Laravel's
choices.

## Current scope

The connection layer is in place: running SQL, nested transactions with savepoints, retry on
deadlocks, joining a transaction someone else owns, pretend mode, and errors that keep personal
data out of logs. It runs over a small driver interface; a JDBC adapter ships in the core. The
`Migration` contract and the migrations table (`DatabaseMigrationRepository`) are in place for the
migrator to build on.

| Layer | State |
|---|---|
| Connection, driver interface, JDBC adapter | available |
| Schema builder | planned |
| Migrations: contract and migrations table | available |
| Migrator | planned |
| Query builder | planned |
| Models | planned |

## Requirements

- Java 17 or later. The JAR is plain Java 17 bytecode and is tested on JDK 17, 21 and 25.
- Android 9 (API 28) or later, through a driver that does not need JDBC (planned). The build
  checks the core against the Android 28 API.
- Inside Spring, through a `JdbcConnectionProvider` that reports Spring's transaction, so
  Photisma nests inside it with savepoints instead of committing it.
- No runtime dependency.

## Coordinates

```kotlin
implementation("org.aristonis:photisma-database:0.1.0")
```

Not yet published. Build it locally with `./gradlew check`.

## Usage

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.LoggedQuery;
import org.aristonis.photisma.database.TransactionScope;

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

### Migrations

```java
import org.aristonis.photisma.database.migrations.DatabaseMigrationRepository;
import org.aristonis.photisma.database.migrations.Migration;

final class CreateUsersTable extends Migration {
    @Override
    public String name() {
        return "2026_10_31_000000_create_users_table";
    }

    @Override
    public void up(Connection db) {
        db.statement("create table users (id integer primary key, email text not null unique)");
    }

    @Override
    public void down(Connection db) {
        db.statement("drop table users");
    }
}

DatabaseMigrationRepository migrations = new DatabaseMigrationRepository(db);
if (!migrations.repositoryExists()) {
    migrations.createRepository();
}
int batch = migrations.getNextBatchNumber();
migrations.log("2026_10_31_000000_create_users_table", batch);
```

## Documentation

- [System design](docs/architecture/system-design.md)
- [Connection design](docs/architecture/connection-design.md)
- [Connection requirements](docs/requirements/connection.md)
- [Connection behavior](docs/behavior/connection/README.md)
- [Migrations requirements](docs/requirements/migrations.md)
- [Migrations behavior](docs/behavior/migrations/README.md)
- [Decision records](docs/adr/)
- [Documentation standard](docs/documentation-standard.md)
- [Changelog](CHANGELOG.md)
