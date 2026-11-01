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
the SQLite grammar ships in `diwan-database-sqlite` and the PostgreSQL grammar in
`diwan-database-pgsql`. The query builder reads and writes rows with Laravel's method names,
through a query grammar from the same modules.

| Layer | State |
|---|---|
| Connection, driver interface, JDBC adapter | available |
| Schema builder: SQLite and PostgreSQL grammars | available |
| Migrations: contract, migrations table and migrator | available |
| Query builder: SQLite and PostgreSQL grammars | available |
| Models | planned |

## Requirements

- Java 17 or later. The JAR is plain Java 17 bytecode and is tested on JDK 17, 21 and 25.
- Android 9 (API 28) or later, through `diwan-database-android`, a driver over Android's own
  `SQLiteDatabase`. The build
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

For PostgreSQL, add the PostgreSQL module and the PostgreSQL JDBC driver:

```kotlin
implementation("org.aristonis:diwan-database-pgsql:0.1.0")
runtimeOnly("org.postgresql:postgresql:42.7.5")
```

```java
Schema schema = PostgresSchema.of(db);
Migrator migrator = Migrator.builder(schema)
        .migrations(migrations)
        .lock(new PostgresMigrationLock(dataSource))
        .build();
```

On Android, add the Android module and the SQLite grammar:

```kotlin
implementation("org.aristonis:diwan-database-android:0.1.0")
implementation("org.aristonis:diwan-database-sqlite:0.1.0")
```

```java
SQLiteDatabase database = helper.getWritableDatabase();
Connection db = Connection.builder().name("app").driver(AndroidSqliteDriver.of(database)).build();
Migrator.builder(SQLiteSchema.of(db)).migrations(migrations).build().run();
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
    table.enumColumn("role", Arrays.asList("admin", "member")).defaultValue("member");
    table.rememberToken();
    table.softDeletes();
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

`change()` redefines a column. As in Laravel 11 and later, the new definition replaces the old one
in full, so restate every modifier you want to keep:

```java
schema.table("posts", table -> table.string("title", 500).nullable().change());
```

Checks, generated columns and raw SQL:

```java
schema.create("lines", table -> {
    table.id();
    table.integer("qty").check("qty > 0");
    table.integer("price");
    table.integer("total").storedAs("qty * price");
    table.check("lines_price_check", "price >= 0");
});
if ("pgsql".equals(schema.driverName())) {
    schema.statement("create index lines_total on lines using brin (total)");
} else {
    schema.statement("create index lines_total on lines (total)");
}
```

The SQL in `check`, `storedAs`, `virtualAs`, `rawColumn` and `statement` is written into the
statement as given; build it from constants, never from user input.

Changing an auto-increment column is not supported. On SQLite, rename or drop a column that another
table references in a separate `Schema.table` call from a `change()`.

### Money

Store money as whole minor units, exact on every database and summed in SQL:

```java
table.money("amount", 2);                       // 10.10 is stored as 1010
long cents = Money.toMinor(new BigDecimal("10.10"), 2);
```

Or as an exact decimal, `numeric` on PostgreSQL and checked fixed-scale text on SQLite:

```java
table.money("debit", 6).asDecimal(20).unsigned();
String text = Money.toText(new BigDecimal("1000"), 6);   // "1000.000000"
```

Nothing rounds: a value with more decimal places than the scale is refused. Keep the currency in its
own column. `MoneyAmount.read(row.get("amount"), 2)` reads either kind of column, on either
database, as the same amount.

For moments, `table.instant("created_at")` stores whole microseconds on SQLite and `timestamptz` on
PostgreSQL; convert with `Instants.toMicros` and `Instants.read`.

### Query builder

SQLite (and Android, which uses the same grammar):

```java
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;

QueryFactory query = SQLiteQuery.of(db);
Object id = query.table("users").insertGetId(Map.of("email", "ada@example.com"));
List<Map<String, Object>> active = query.table("users")
        .where("email", "like", "%@example.com")
        .whereNotNull("email")
        .orderByDesc("id")
        .limit(10)
        .get();
long total = query.table("users").count();
query.table("users").where("id", id).update(Map.of("email", "ada@example.org"));
```

PostgreSQL:

```java
import org.aristonis.diwan.database.pgsql.PostgresQuery;

QueryFactory query = PostgresQuery.of(db);
Object id = query.table("users").insertGetId(Map.of("email", "grace@example.com"));
Optional<Map<String, Object>> grace = query.table("users").find(id);
query.table("users").where("id", id).delete();
```

Every value is bound as a parameter; `raw` and the `*Raw` methods write trusted SQL as given. An
`Instant` is stored as `Blueprint.instant` stores it; bind money explicitly with `Money.toMinor` or
`Money.toText` and read it with `MoneyAmount.read`. Date wheres (`whereDate` and friends) are for
text date columns; use `whereInstantBetween` on instant columns. A builder is not thread-safe;
`clone()` gives an independent copy. Differences from Laravel are listed in
[ADR 0013](docs/adr/0013-query-grammar.md).

Joins, subqueries and unions:

```java
List<Map<String, Object>> rows = query.table("orders")
        .join("customers", "customers.id", "=", "orders.customer_id")
        .leftJoinSub(query.table("payments").selectRaw("order_id, sum(amount) as paid")
                .groupBy("order_id"), "p", "p.order_id", "=", "orders.id")
        .whereExists(q -> q.from("notes").whereColumn("notes.order_id", "orders.id"))
        .select("orders.id", "customers.name", "p.paid")
        .get();

List<Object> latest = query.table("orders").select("id").where("status", "open")
        .union(query.table("archived_orders").select("id"))
        .orderByDesc("id")
        .limit(20)
        .pluck("id");
long open = query.table("orders").where("status", "open")
        .union(query.table("archived_orders").where("status", "open")).count();

query.table("archive").insertUsing(List.of("id", "total"),
        q -> q.from("orders").select("id", "total").where("status", "closed"));
query.table("orders").join("customers", "customers.id", "=", "orders.customer_id")
        .where("customers.blocked", true).update(Map.of("status", "held"));
```

A subquery is copied when attached, so changing it later does not change the outer query. A right
join needs SQLite 3.39 or later (older Android SQLite reports its own error). An update or delete
with a join needs a table with a rowid on SQLite. Writes need a table, not a subquery.

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
- [Query builder requirements](docs/requirements/query.md)
- [Query builder behavior](docs/behavior/query/README.md)
- [Migrations requirements](docs/requirements/migrations.md)
- [Migrations behavior](docs/behavior/migrations/README.md)
- [Migrator requirements](docs/requirements/migrator.md)
- [Migrator behavior](docs/behavior/migrator/README.md)
- [Decision records](docs/adr/)
- [Documentation standard](docs/documentation-standard.md)
- [Changelog](CHANGELOG.md)
