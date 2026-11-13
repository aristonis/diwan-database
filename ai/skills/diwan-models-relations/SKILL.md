---
name: diwan-models-relations
description: Define Diwan models, relations, casts, scopes and eager loading in diwan-database-eloquent. Use when adding a model class, a hasMany or belongsTo relation, fixing an N+1, or setting fillable and casts.
---

# Diwan models and relations

## Steps

1. Extend `Model`. Add the one static entry: `public static final ModelQuery<T> query = ModelQuery.of(T.class, T::new);`.
2. List `fillable()`. Nothing is mass assignable until you do.
3. Override `table()` when the plural guess is wrong, and always on Android.
4. For a relation, add a typed method (`hasMany`, `belongsTo`, ...) and register its name in
   `relations()` with `RelationMap`.
5. Bind to a factory before use: `User.query.on(manager.query())`, or `.on(db.on(tx))` in a transaction.
6. Eager load with `with("posts.comments")` instead of reading a relation in a loop.

## Example

```java
public final class Post extends Model {
    public static final ModelQuery<Post> query = ModelQuery.of(Post.class, Post::new);

    @Override protected List<String> fillable() { return List.of("title", "user_id"); }
    @Override protected String table() { return "posts"; }

    @Override protected RelationMap<?> relations() {
        return RelationMap.of(Post.class).add("author", Post::author);
    }
    public BelongsTo<User> author() { return belongsTo(User.class, "user_id"); }
}

ModelQuery<Post> posts = Post.query.on(manager.query());
ModelCollection<Post> list = posts.with("author").get();
```

Kotlin:

```kotlin
class Post : Model() {
    override fun fillable(): List<String> = listOf("title", "user_id")
    override fun table(): String = "posts"

    override fun relations(): RelationMap<*> {
        return RelationMap.of(Post::class.java).add("author", Post::author)
    }
    fun author(): BelongsTo<User> = belongsTo(User::class.java, "user_id")

    companion object {
        val query: ModelQuery<Post> = ModelQuery.of(Post::class.java, ::Post)
    }
}

val posts = Post.query.on(manager.query())
val list = posts.with("author").get()
```

Keep a model package-private behind a repository that returns an immutable record:

```java
final class UnitRow extends Model {
    static final ModelQuery<UnitRow> query = ModelQuery.of(UnitRow.class, UnitRow::new);
    // Tables is the app's own naming helper
    @Override protected String table() { return Tables.name("units"); }
}
public Optional<Unit> find(long id) {
    return UnitRow.query.on(db).find(id)
            .map(r -> new Unit(r.getLong("id"), r.getString("name")));
}
```

Kotlin:

```kotlin
class UnitRow : Model() {
    // Tables is the app's own naming helper
    override fun table(): String = Tables.name("units")

    companion object {
        val query: ModelQuery<UnitRow> = ModelQuery.of(UnitRow::class.java, ::UnitRow)
    }
}

fun find(id: Long): Optional<UnitInfo> {
    return UnitRow.query.on(db).find(id)
        .map { r -> UnitInfo(r.getLong("id"), r.getString("name")) }
}
```

## Pitfalls

- No reflection and no annotations. Accessors, casts and relations are registered by name in
  `accessors()`, `casts()` and `relations()`. A relation not in the map throws
  `RelationNotFoundException`.
- Under R8, defaults taken from class names break. Override `table()`, give foreign keys, pivot
  tables and `chaperone` names explicitly, or keep the model names.
- `getAttribute("posts")` never loads a relation. Use `related`, `relatedMany` or `with`.
- `belongsTo` needs its foreign key. A constraint with `limit` or `offset` on an eager load is refused.
- `cursor()` with eager loads is refused. Use `lazy()`.
- `fill` drops keys outside `fillable()`. `forceFill` and `Model.unguarded` bypass the guard, so keep
  them away from request data.
- Date cast formats are Java patterns (`yyyy-MM-dd`), not PHP letters.
- An `instant` column needs `Casts.instant()`. Instant `created_at`, `updated_at` or `deleted_at` need `timestampFormat()` = `TimestampFormat.INSTANT`, since the framework writes them directly.
- `casts()` and `accessors()` are read once per class and must not use instance state.
- `collection` and `object` casts read a JSON array as a `List` and a JSON object as a `Map`; the other stored shape throws `InvalidCastException`; null stores SQL NULL. For typed elements, a typed map or a DTO, implement `Cast<T>` yourself.
- `encrypted` casts (`encrypted`, `encrypted:array|json|collection|object`) need a text column, a model `attributeEncrypter()` and no `where` on them; Diwan does no cryptography; an encrypter failure is an `InvalidCastException` with the original as cause.
- `hashed` casts need a text column and a model `attributeHasher()`, accept a `String` only, and belong in `hidden()`; Diwan does no hashing, so bring your own bcrypt or argon2 and verify with your own hasher.
- `binary:uuid` (a `UUID`) and `binary:ulid` (a `String`, read lower-case, written in any case) store 16 bytes in a `binary()` column, not `uuid()`; a `where` on them needs `BinaryIds.uuidBytes(uuid)` or `BinaryIds.ulidBytes(text)`; a stored value that is not 16 bytes is an `InvalidCastException`; binary keys are not generated.
- Money: use `Casts.decimal(precision, scale)` (or `decimal:P,S`) with the same numbers as the
  migration's decimal column. It refuses extra decimals; `decimal:N` rounds half up instead.
- On SQLite the strict decimal cast refuses a value with more than 15 significant digits
  (`InvalidCastException` names the column). For longer exact values use a text column with
  `Casts.decimalText(p, s)` (same strict rules, no 15-digit limit). PostgreSQL is unchanged.
- Model `upsert` and `insertOrIgnore` skip events and casts. `upsert` needs SQLite 3.24.
- `query().fillAndInsert(rows)` inserts many rows in one statement with casts and timestamps, but skips the fillable guard and events.

Docs: Models and Relations on https://aristonis.org/open-source/diwan-database/docs/main/home
