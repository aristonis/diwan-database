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
through a query grammar from the same modules. Models in `diwan-database-eloquent` add
Eloquent's class-per-table API, with relations, eager loading, relation aggregates, touches,
`push` and inverse relations, on top of the query builder.

| Layer | State |
|---|---|
| Connection, driver interface, JDBC adapter | available |
| Schema builder: SQLite and PostgreSQL grammars | available |
| Migrations: contract, migrations table and migrator | available |
| Query builder: SQLite and PostgreSQL grammars | available |
| Models: Eloquent-style, in `diwan-database-eloquent` | available |

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

Large reads and pages:

```java
// Batches of 500 by offset; return false to stop. Needs an order.
query.table("orders").orderBy("id").chunk(500, rows -> {
    rows.forEach(this::recalculate);
    return true;
});

// Batches by key: safe when rows change during the loop.
query.table("orders").where("status", "open").chunkById(500, rows -> {
    rows.forEach(row -> query.table("orders").where("id", row.get("id"))
            .update(Map.of("status", "checked")));
    return true;
});

// One page per step, no connection held between pages.
for (Map<String, Object> row : query.table("orders").orderBy("id").lazy(1000)) {
    export(row);
}

// Streams rows; close it, best with try-with-resources.
try (RowCursor rows = query.table("orders").orderBy("id").cursor()) {
    for (Map<String, Object> row : rows) {
        export(row);
    }
}

LengthAwarePage page = query.table("orders").orderBy("id").paginate(20, 3);
long total = page.total();
SimplePage simple = query.table("orders").orderBy("id").simplePaginate(20, 3);
boolean more = simple.hasMorePages();

CursorPage first = query.table("orders").orderByDesc("created_at").orderBy("id").cursorPaginate(20);
String token = first.nextCursor().map(Cursor::encode).orElse(null); // send to the client
CursorPage next = query.table("orders").orderByDesc("created_at").orderBy("id")
        .cursorPaginate(20, Cursor.decode(token));
```

`chunk` pages by offset, so use `chunkById` when rows change during the loop.

Always open a cursor in try-with-resources. A cursor outside a transaction that is neither read
to the end nor closed keeps its connection for good, and nothing closes it for you. Each one
left open takes a pool slot, so a few of them can starve the app. On PostgreSQL it also leaves
the server session idle in a transaction, which blocks vacuum on the tables it read. Inside a
transaction no other statement runs on the connection until the cursor is closed. On Android,
use a cursor only from the thread that opened it.
Cursor tokens are not signed: a forged one cannot inject SQL but can ask for other allowed rows.
Choices and differences from Laravel are in
[ADR 0014](docs/adr/0014-cursors-and-pagination.md).

### Models

Models live in `diwan-database-eloquent`. A model is a class per table with one static entry; it
needs no reflection, annotations or build plugin, so the same class runs on Android.

```java
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

public final class User extends Model {
    public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);

    @Override
    protected List<String> fillable() {
        return List.of("name", "email");   // nothing is mass assignable until listed
    }
}
```

The table is `users`, the snake-case plural of the class name; override `table()` for an
irregular plural. Bind the entry to a query factory once:

```java
ModelQuery<User> users = User.query.on(SQLiteQuery.of(db));

User ada = users.create(Map.of("name", "Ada", "email", "ada@example.com"));  // id, timestamps set
User found = users.findOrFail(ada.getKey());
Optional<User> maybe = users.find(42);

found.setAttribute("name", "Ada L.");
found.isDirty("name");   // true
found.save();            // writes name and updated_at only
found.save();            // clean: runs nothing
found.delete();          // false when the model is not in the database
```

`fill` sets only the keys in `fillable` and drops the rest. With the default guards (`guarded` is
`*` and `fillable` is empty) it throws `MassAssignmentException`; `forceFill` bypasses the guards.

```java
User user = users.make(request);   // fills; "is_admin" in request is dropped
user.save();

User sam = users.firstOrCreate(Map.of("email", "sam@example.com"), Map.of("name", "Sam"));
users.updateOrCreate(Map.of("email", "sam@example.com"), Map.of("name", "Samuel"));
```

Inside a transaction, bind the models to the transaction's connection with `on(c)`, so every
statement runs in it:

```java
db.transaction(c -> {
    User u = users.on(c).findOrFail(1);
    u.setAttribute("name", "Grace");
    return u.save();
});
```

`saveOrFail()` saves inside its own transaction and throws `IllegalStateException` when a model
with a non-incrementing key has nothing to insert; `save()` returns `true` in that case, as in
Laravel.

```java
User grace = users.make(Map.of("name", "Grace", "email", "grace@example.com"));
grace.saveOrFail();
```

Pages, chunks and cursors hold models:

```java
ModelPage<User> page = users.query().orderBy("name").paginate(20, 2);
page.items();   // ModelCollection<User>
page.total();

try (ModelCursor<User> cursor = users.query().orderBy("id").cursor()) {
    for (User u : cursor) {
        // ...
    }
}
```

Casts turn stored values into Java types. Declare them in `casts()`, in Laravel's string form or
with the typed factories in `Casts`; `CastMap` keeps the order:

```java
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastMap;
import org.aristonis.diwan.database.eloquent.casts.Casts;

@Override
protected Map<String, Cast<?>> casts() {
    return CastMap.create()
            .put("price", "decimal:2")                      // BigDecimal, scale 2, half up
            .put("is_admin", "bool")                        // Boolean
            .put("options", "json")                         // Map or List
            .put("born_on", "date")                         // LocalDate, stored yyyy-MM-dd
            .put("paid_at", Casts.datetime("dd/MM/yyyy"))   // Instant; the pattern only shapes toMap
            .put("status", Casts.enumOf(Status.class))      // the constant; name() or BackedValue.value()
            .build();
}
```

```java
Order order = orders.findOrFail(1);
BigDecimal price = (BigDecimal) order.getAttribute("price");
Map<String, Object> options = (Map<String, Object>) order.getAttribute("options");
options.put("gift", true);
order.isDirty("options");   // true: the changed map is written on save
order.getRawAttribute("price");   // the stored form, "12.50"
```

`int` reads `Long`, `float` reads `Double`, `datetime` reads `Instant` and `timestamp` reads epoch
seconds. Bad stored data, such as broken JSON or `"abc"` under `int`, throws
`InvalidCastException` naming the model, the key and the cast; Laravel would read `null` or `0`.
The dirty check compares by meaning: JSON by structure, floats within 4 ULP, moments as instants
and dates as dates, so re-ordered keys or the same instant in another offset write nothing.
Reading a cast value never makes a model dirty. A cast of your own implements `Cast<T>` (`get` and
`set`), and optionally `CastSerializer` and `CastComparator`.

Date formats are Java `DateTimeFormatter` patterns, not PHP letters. Write `yyyy-MM-dd`, not
`Y-m-d`: the PHP form compiles in Java and means week-based year and minute.

Accessors and mutators are registered, not discovered by name. The lambdas receive the model, so
they hold no instance; an accessor wins over the cast:

```java
@Override
protected Map<String, Attribute<?>> accessors() {
    Map<String, Attribute<?>> map = new LinkedHashMap<>();
    map.put("display_name", Attribute.get((model, value, attrs) ->
            attrs.get("first_name") + " " + attrs.get("last_name")));
    map.put("email", Attribute.set((model, value, attrs) -> value.toString().toLowerCase(Locale.ROOT)));
    return map;
}
```

`toMap()` and `toJson()` apply `visible()`, then `hidden()`, then `appends()`; moments come out as
ISO-8601 UTC with microseconds, a `date:F` or `datetime:F` cast in its pattern, decimals as plain
text and enums as their stored value. `makeHidden`, `makeVisible` and `append` change one instance.

```java
@Override
protected List<String> hidden() {
    return List.of("password");
}

@Override
protected List<String> appends() {
    return List.of("display_name");
}

String json = user.toJson();   // {"id":1,"first_name":"Ada",...,"display_name":"Ada Lovelace"}
```

JSON goes through a `JsonCodec`. The built-in one is strict RFC 8259 and keeps numbers exact (no
`Double`); the library adds no JSON dependency. To use Jackson or Gson, implement the interface and
return it from `jsonCodec()`:

```java
final class JacksonCodec implements JsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonException("cannot encode " + value.getClass().getName());
        }
    }

    @Override
    public Object decode(String text) {
        try {
            return mapper.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new JsonException("invalid JSON");
        }
    }
}

// in the model: one shared instance
private static final JsonCodec CODEC = new JacksonCodec();

@Override
protected JsonCodec jsonCodec() {
    return CODEC;
}
```

Timestamps are written as Laravel writes them, UTC text `yyyy-MM-dd HH:mm:ss`, which fits
`timestamps()` columns; return `TimestampFormat.INSTANT` from `timestampFormat()` for tables built
with instant columns. Models are not thread-safe. Behavior and limits:
[docs/behavior/eloquent](docs/behavior/eloquent/README.md); decisions:
[ADR 0015](docs/adr/0015-eloquent-models.md), for casts
[ADR 0016](docs/adr/0016-attributes-casts-and-the-json-codec.md), and for relations
[ADR 0017](docs/adr/0017-relations.md).

#### Relations

A relation is a typed method plus a name in `relations()`. The map is how names such as
`"posts"` reach the method without reflection, so R8 cannot break it; it is checked once per class
on first use, and a mistake fails with `InvalidRelationException` naming the model and relation.

```java
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;

public final class User extends Model {
    public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);

    @Override
    protected RelationMap<?> relations() {
        return RelationMap.of(User.class).add("posts", User::posts);
    }

    public HasMany<Post> posts() {
        return hasMany(Post.class, "user_id");      // hasOne works the same way
    }
}

public final class Post extends Model {
    public static final ModelQuery<Post> query = ModelQuery.of(Post.class, Post::new);

    @Override
    protected RelationMap<?> relations() {
        return RelationMap.of(Post.class).add("author", Post::author);
    }

    public BelongsTo<User> author() {
        return belongsTo(User.class, "user_id");    // the foreign key is required
    }
}
```

Read a relation lazily by name, or build a query from the method. A lazy read runs once and keeps
the result on the model. `getAttribute("posts")` never loads a relation:

```java
ModelCollection<Post> posts = user.relatedMany("posts");   // select * from posts where user_id = ?
User author = post.related("author");                      // null when there is none
ModelCollection<Post> recent = user.posts().where("published", true).latest().get();
```

Eager load to avoid one query per model. Paths nest with dots, `name:col,...` selects columns
(the matching key is added for you), and a constraint narrows the related query:

```java
ModelQuery<User> users = User.query.on(SQLiteQuery.of(db));
ModelCollection<User> all = users.with("posts.comments").get();   // three queries in all
users.with("posts", q -> q.where("published", true)).get();
users.query().with("posts:id,title").paginate(20, 1);

all.load("posts.author");        // on a collection already read
user.loadMissing("posts");       // only what is not loaded yet
```

Null keys are dropped and long key lists are split so no statement passes SQLite's or
PostgreSQL's parameter limits. An eager constraint runs once per chunk plus once, so keep it free
of side effects; it may not set `limit` or `offset`. `cursor()` refuses eager loads; `lazy()` loads
them per page.

Filter by related rows with `exists` subqueries:

```java
users.query().has("posts").get();
users.query().has("posts", ">=", 3).get();
users.query().whereHas("posts", q -> q.where("title", "like", "Java%")).get();
users.query().doesntHave("posts.comments").get();
users.query().whereRelation("posts", "published", true).get();
```

Write through a relation; the foreign key is set for you. Writing from a parent that has no key
yet throws `IllegalStateException` before any SQL, where Laravel writes a null key:

```java
Post post = user.posts().create(Map.of("title", "Hello"));   // user_id set
user.posts().saveMany(List.of(draftA, draftB));             // no transaction, as in Laravel
post.author().associate(otherUser);                         // sets user_id and the relation
post.author().dissociate();
```

`hasOne` and `belongsTo` relations can return a default model with `withDefault()` when nothing
is found. `toMap()` and `toJson()` include loaded relations after the attributes, under snake-case
keys, honour `hidden` and `visible`, and stop on cycles.

To catch N+1 queries in development, refuse lazy reads on models that came from a multi-row
result. It is a process-wide setting; call it once at startup:

```java
ModelQuery.preventLazyLoading(true);
users.query().get().get(0).relatedMany("posts");   // LazyLoadingViolationException
```

Two notes for relations:

- A class literal such as `Post.class` does not run the class's static initialiser, and a relation
  needs the related class's `query` entry to exist. Touch every model's `query` entry at startup
  (for example, list them in one place), or make sure each model was used before a relation
  names it.
- The default foreign key is the snake-case class name plus `_id`. On Android, R8 renames classes,
  so pass foreign keys explicitly, as above, or override `getForeignKey()`.

Count, sum or check related rows without loading them. Each call adds one subselect to the main
query, named `<relation>_<function>_<column>` in snake case, or by `as`:

```java
users.query()
        .withCount("posts")                                        // posts_count
        .withCount("posts as total_posts")                         // total_posts, as written
        .withCount(Map.of("posts as published", q -> q.where("published", true)))
        .withMax("posts", "votes")                                 // posts_max_votes
        .withSum("orders", "total")                                // orders_sum_total
        .withExists("posts")                                       // posts_exists, a Boolean
        .withAggregate("posts", "title")                           // posts_title: one row's value
        .get();
// select "users".*, (select count(*) from "posts" where "users"."id" = "posts"."user_id") as "posts_count", ...
```

`withMin` and `withAvg` work like `withMax`; `withAggregate(relation, column, function)` takes
`count`, `max`, `min`, `sum`, `avg` or `exists` in any case and refuses other text, because the
function is written into the SQL. A core `Expression` can stand in for the column on the
single-relation forms. `withExists` reads as `Boolean` on every database; the other results keep
the type the driver returns (SQLite `Long` or `Double`, PostgreSQL `Long` or `BigDecimal`). An
unknown relation or a dot path such as `posts.comments` throws `RelationNotFoundException` when
the method is called. A constraint closure may add `where` clauses (kept inside one group, so an
`orWhere` cannot widen the match), `limit`, `offset` and `groupBy`; `select` and `orderBy` in it
are ignored, and `distinct`, `join`, `having` or `union` throw `IllegalStateException`. A self
relation gets its own table alias, and an aliased `from("users as u")` correlates on `u`. As in
Laravel, `select(...)` after `withCount` replaces the columns and drops the aggregate, while
`addSelect` keeps it; `as` is an alias only between single spaces.

The same aggregates fill models already read, with one query per key chunk:

```java
all.loadCount("posts");                     // select "id", (select count(*) ...) as "posts_count" from "users" where "users"."id" in (1, 2, 3)
all.loadSum("orders", "total");
user.loadExists(Map.of("posts", q -> q.where("published", true)));
```

Only the key and the aggregates are selected. The models stay clean, and other unsaved changes
stay dirty. Keys are split like eager keys, so 2,000 string keys run three queries on SQLite and
one on PostgreSQL. A model whose row was deleted in the meantime, or whose key is null, is skipped;
Laravel fails there. An empty collection runs no query.

`withExists` and `loadExists` read as booleans through a per-instance cast. You can add your own:
`model.mergeCasts(Map.of("score", "int"))` changes that instance only, and
`query.withCasts(Map.of("last_login", "datetime"))` gives the casts to every model the query
returns. The class's `casts()` never changes.

Name relations in `touches()` to keep a parent's `updated_at` current:

```java
public final class Comment extends Model {
    @Override
    protected Set<String> touches() {
        return Collections.singleton("post");      // a belongsTo, hasOne or hasMany relation
    }
}

comment.setAttribute("body", "Edited");
comment.save();     // ...then: update "posts" set "updated_at" = ? where "posts"."id" = ?
```

A save that changed something, and a delete, touch each named relation: one raw `UPDATE` with no
events and no `SELECT`. Owners already loaded on the model have their `saved()` hook run and
touch their own owners in turn; an owner that is not loaded is never loaded, so
`preventLazyLoading` does not fire. Each model is walked once per call, so many
children sharing one loaded owner cost one `UPDATE`, and a cycle ends. The owner object in memory
keeps its old `updated_at`; `refresh()` it when you need the new value. A bad name in `touches()`
throws `InvalidRelationException` on first use. Call `touchOwners()` to touch without saving.

Turn touching off for named classes, and their subclasses, for one call:

```java
Model.withoutTouching(Post.class, () -> importComments(rows));
Model.withoutTouchingOn(List.of(Post.class, User.class), () -> importAll(rows));
```

The scope holds on the calling thread only and is restored when the call returns or throws, so
another request, or a thread started inside the call, still touches. A model's own `updated_at`
is still set on save.

`push()` saves a model, then every loaded relation under it, depth first in load order:

```java
post.setAttribute("title", "New title");
post.relatedMany("comments").get(0).setAttribute("body", "Fixed");
boolean saved = post.push();      // the post, then the changed comment
```

It returns `false` at the first save that fails; rows saved before it stay, since there is no
transaction (wrap it in one if you need all or nothing). It sets no foreign key, so a new child
placed with `setRelation` is inserted with whatever it holds, and a `withDefault` placeholder among
the loaded relations is inserted, as in Laravel. Each model is saved once per call, also on a cycle
or a shared model. Every save runs its own touches.

`chaperone()` on a `hasOne` or `hasMany` sets the parent on each child the relation returns or
writes, as the same object and with no query:

```java
public HasMany<Comment> comments() {
    return hasMany(Comment.class, "post_id").chaperone("post");
}

Comment first = post.relatedMany("comments").get(0);
boolean same = first.related("post") == post;      // true, no query
```

The link is set on lazy reads, on eager loads (to each child's own parent), on `make`, `save`,
`create`, `saveMany` and `createMany`, and on a `withDefault` model. `inverse` is an alias;
`withoutChaperone()` turns it off for one relation. Without a name, `chaperone()` tries the foreign
key without its key suffix (`post_id` gives `post`), the parent's foreign key, the parent's class
name, `owner`, and `parent` for a self relation, and throws `RelationNotFoundException` when none
is a relation of the child. On Android R8 renames classes, so pass the name. `toJson()` of a
parent and its linked children ends: a model already being written is written again with its
attributes only.

`touchOwners()`, `push()` and `toMap()` recurse once per level of the loaded graph. A very deep
self chain, such as thousands of nested categories loaded one under another, can overflow a small
thread stack, such as the default on Android; load such chains in pieces.

Cost at scale: index each child foreign key that relations, `has`/`whereHas`, `withCount`,
`withExists` and `load*` use, since they run a correlated subquery per outer row. A `hasMany` or
`hasOne` touch rewrites every child row of the parent, and many children touching one owner make
it a hot row (lock waits on PostgreSQL, `SQLITE_BUSY` on SQLite and Android); `push()` sends one
owner `UPDATE` per touching child. Wrap bulk imports in `withoutTouching`.

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
