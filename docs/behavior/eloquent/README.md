# Models

A model is a class per table with Eloquent's method names. It keeps its attributes, knows which
ones changed, and writes only those. Every query goes through the query builder, so the SQL is
the same SQL you would get from `table(...)`.

```java
public final class User extends Model {
    public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);

    @Override
    protected List<String> fillable() {
        return List.of("name", "email");
    }
}

ModelQuery<User> users = User.query.on(SQLiteQuery.of(db));
User ada = users.create(Map.of("name", "Ada", "email", "ada@example.com"));
User found = users.findOrFail(ada.getKey());
found.setAttribute("name", "Ada L.");
found.save();                     // update users set name = ?, updated_at = ? where id = ?
found.delete();
```

## What you can do

| You want to | Use |
|---|---|
| Configure a model | override `table`, `primaryKey`, `keyType`, `incrementing`, `connectionName`, `timestamps`, `createdAtColumn`, `updatedAtColumn`, `fillable`, `guarded`, `perPage`, `dateFormat`, `timestampFormat`, `uniqueIdKind`, `casts`, `accessors`, `hidden`, `visible`, `appends`, `jsonCodec` |
| Cast attributes | `Casts.of("decimal:2")`, `Casts.decimal`, `Casts.date`, `Casts.datetime`, `Casts.timestamp`, `Casts.json`, `Casts.enumOf`, `CastMap`, a user `Cast`; `getCasts`, `hasCast`, `getRawAttribute`, `getRawOriginal` |
| Compute or normalise attributes | `Attribute.of`, `Attribute.get`, `Attribute.set`, `shouldCache`, `withoutObjectCaching` |
| Serialize | `toMap`, `toJson`, `setHidden`, `makeHidden`, `setVisible`, `makeVisible`, `append`, `setAppends`, `withoutAppends` |
| Pick a connection | `ModelQuery.on(QueryFactory)`, `on(Connection)` inside a transaction, `using(ConnectionResolver)`, `ConnectionResolver.setDefault` |
| Read and change attributes | `getAttribute`, `setAttribute`, `getString`, `getLong`, `getInteger`, `getDouble`, `getBigDecimal`, `getBoolean`, `getInstant` |
| See what changed | `isDirty`, `isClean`, `wasChanged`, `getDirty`, `getChanges`, `getOriginal`, `getPrevious`, `syncOriginal`, `discardChanges` |
| Fill from input | `fill`, `forceFill`, `Model.unguarded`, `Model.preventSilentlyDiscardingAttributes` |
| Write | `save`, `saveOrFail`, `update`, `delete`, `destroy`, `increment`, `decrement`, `touch` |
| Reload or copy | `refresh`, `fresh`, `replicate`, `is`, `isNot` |
| Find | `find`, `findMany`, `findOrFail`, `first`, `firstOrFail`, `sole`, `firstWhere`, `value`, `all` |
| Find or make | `firstOrNew`, `firstOrCreate`, `updateOrCreate`, `create`, `forceCreate` |
| Read many | `get`, `chunk`, `chunkById`, `each`, `lazy`, `lazyById`, `cursor`, `paginate`, `simplePaginate`, `cursorPaginate` |
| Reach the query builder | `query(Consumer<Builder>)`, `toBase()` |
| Declare relations | override `relations()` with `RelationMap`; `hasOne`, `hasMany`, `belongsTo` in typed methods |
| Read relations | `getRelationValue`, `related`, `relatedMany`, `relation(name)`, `getRelation`, `relationLoaded`, `setRelation`, `unsetRelation`, `withoutRelations` |
| Eager load | `with`, `without`, `withOnly`, `load`, `loadMissing` |
| Filter by related rows | `has`, `orHas`, `doesntHave`, `orDoesntHave`, `whereHas`, `orWhereHas`, `whereDoesntHave`, `orWhereDoesntHave`, `whereRelation`, `orWhereRelation`, `whereGroup` |
| Write through a relation | `make`, `save`, `saveMany`, `create`, `createMany`, `associate`, `dissociate`, `withDefault` |
| Refuse lazy loading | `ModelQuery.preventLazyLoading` |
| Count or sum related rows in a query | `withCount`, `withMax`, `withMin`, `withSum`, `withAvg`, `withExists`, `withAggregate` |
| Count or sum related rows of loaded models | `loadCount`, `loadMax`, `loadMin`, `loadSum`, `loadAvg`, `loadExists`, `loadAggregate` on a model or a `ModelCollection` |
| Add casts to one instance or one query | `mergeCasts`, `withCasts` |
| Bump a parent's `updated_at` | override `touches()`; `touchOwners`, `Relation.touch`, `Model.withoutTouching`, `Model.withoutTouchingOn` |
| Save a model and its loaded relations | `push` |
| Point children back to their parent | `chaperone`, `inverse`, `withoutChaperone`, `withoutInverse` on `hasOne` and `hasMany` |

## Casts, accessors and serialization

```java
@Override
protected Map<String, Cast<?>> casts() {
    return CastMap.create()
            .put("price", "decimal:2")                    // BigDecimal, rounded half up
            .put("options", "json")                       // Map or List
            .put("paid_at", Casts.datetime())             // Instant
            .put("status", Casts.enumOf(Status.class))    // the constant
            .build();
}

@Override
protected Map<String, Attribute<?>> accessors() {
    Map<String, Attribute<?>> map = new LinkedHashMap<>();
    map.put("display_name", Attribute.get((model, value, attrs) -> attrs.get("first") + " " + attrs.get("last")));
    return map;
}

@Override
protected List<String> hidden() { return List.of("password"); }

@Override
protected List<String> appends() { return List.of("display_name"); }
```

Reading goes accessor, then cast, then the stored value; writing goes mutator, then cast, then
store, and always stores the storage form. The dirty check compares by meaning: JSON by structure,
floats within 4 ULP, moments as instants, dates as dates, decimals after rounding. Reading never
makes a model dirty; changing a map a JSON cast returned does. `toMap` applies `visible`, then
`hidden`, then `appends`; moments come out as ISO-8601 UTC with microseconds.

## Relations

```java
public final class User extends Model {
    public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);

    @Override
    protected RelationMap<?> relations() {
        return RelationMap.of(User.class).add("posts", User::posts);
    }

    public HasMany<Post> posts() {
        return hasMany(Post.class);   // posts.user_id = users.id
    }
}

ModelQuery<User> users = User.query.on(SQLiteQuery.of(db));
ModelCollection<User> all = users.with("posts").get();            // two queries in all
ModelCollection<Post> posts = all.get(0).relatedMany("posts");    // loaded: no query
Optional<User> writer = users.query()
        .whereHas("posts", q -> q.where("title", "like", "Java%"))
        .first();
```

A relation is read once and kept on the model. `with`, `load` and `loadMissing` take dot paths
(`posts.comments`), a column list (`posts:id,title`; the matching key is added) or a constraint.
Eager loading drops null keys and splits long key lists so no statement passes the database's
limits. `has` and
`whereHas` compile to `exists` subqueries; a relation to the same table gets an alias per level.
`toMap()` writes loaded relations after the attributes under snake-case keys and stops on cycles.

## Aggregates, touches, push and chaperone

```java
ModelCollection<User> list = users.query()
        .withCount("posts")                                       // posts_count
        .withCount(Map.of("posts as published", q -> q.where("published", true)))
        .withSum("orders", "total")                               // orders_sum_total
        .withExists("posts")                                      // posts_exists, a Boolean
        .get();

list.loadMax("posts", "votes");                                   // one query per key chunk

public final class Comment extends Model {
    @Override
    protected Set<String> touches() { return Collections.singleton("post"); }
}
comment.save();                       // update posts set updated_at = ? where posts.id = ?
Model.withoutTouching(Post.class, () -> comment.save());          // this thread only

post.push();                          // the post, then every loaded relation under it

public HasMany<Comment> comments() { return hasMany(Comment.class).chaperone("post"); }
```

Each aggregate is a subselect in the main query, named `<relation>_<function>_<column>` in snake
case unless the relation is written `posts as name`. The function is `count`, `max`, `min`, `sum`,
`avg` or `exists`; `exists` reads as a boolean, the others as the driver returns them. A constraint
closure may add `where`, `limit`, `offset` and `groupBy`; `select` and `orderBy` in it are
ignored and other clauses throw. `load*` selects only the key and the aggregates, chunks the keys
like eager loading, leaves the models clean and skips a model whose row is gone.

A model listed in `touches()` updates each owner's `updated_at` after a save that changed
something and before a delete: one raw `UPDATE`, no `SELECT`, no events. It then continues into
owners already loaded on the model, never loading one. Each model is walked once per call. The
owner object in memory keeps its old `updated_at`.

`push()` saves the model, then each loaded relation in load order, depth first, and returns
`false` at the first failed save. It sets no foreign key, runs no transaction, and inserts a
`withDefault` placeholder it finds. Each save runs its own touches.

`chaperone()` sets the parent on every child the relation returns or writes, as the same instance
and with no query. Without a name it guesses: the foreign key without its key suffix, the parent's
foreign key, the parent class name, `owner`, then `parent` for a self relation.

## Limits

- Models are not thread-safe. Share the `ModelQuery`; give each thread its own model instances.
- Without casts, a `Boolean` set on a column SQLite stores as the integer `1` reads back as `1`
  and stays dirty against `true`. A `bool` cast fixes it.
- Date formats in casts and `dateFormat` are Java patterns. A PHP format such as `Y-m-d` compiles
  with another meaning (`Y` is the week-based year, `m` the minute).
- Bad stored data under a cast throws `InvalidCastException`, where Laravel reads `null`, `0` or
  `true`.
- A value object returned by a user cast needs `equals`, or the cast a `CastComparator`, to stay
  clean after a read.
- `casts()` and `accessors()` are read once per class; they must not depend on instance state.
- Irregular plurals outside the short built-in list get a wrong table guess; override `table()`.
- UUID version 7 and ULID keys made in the same millisecond are not ordered among themselves.
- A `ModelCursor` that is neither exhausted nor closed keeps its connection. Use
  try-with-resources.
- `cursor()` refuses eager loads; use `lazy()`, which loads them per page.
- A related model's class must have run its static initialiser, so touch each model's `query`
  entry at startup. A class literal such as `Post.class` does not run it.
- A relation instance is single-use and not thread-safe. Call the relation method again for a new
  query.
- An eager constraint runs once per chunk plus once, and an `or` in it is not grouped, as in
  Laravel. It may not set `limit` or `offset`.
- `with` given the same relation twice runs both constraints; Laravel keeps the last.
- Writes through a relation run without a transaction, as in Laravel.
- Two-way relations loaded on both sides make `toMap()` output grow with the product of both
  sides.
- The default foreign key comes from the class name, which R8 renames on Android; pass keys or
  override `getForeignKey()`.
- `chaperone()` without a name guesses from class names, which R8 renames; on Android pass the
  name, as in `chaperone("post")`.
- `select` after `withCount` replaces the column list and drops the aggregate, as in Laravel; use
  `addSelect`.
- An `as` alias needs single spaces (`posts as total`); other spacing is read as a relation name
  and fails.
- A touched owner in memory keeps its old `updated_at`; call `refresh()` when you need it.
- A one-to-many touch updates every child row of the parent, which can be many rows.
- An owner's `saved()` hook that changes and saves the owner again starts a new touch walk; a hook
  that does so in a cycle does not end, as in Laravel.
- `save()` runs no transaction: if a touch `UPDATE` fails, the child row is already written, the
  model stays dirty and the exception reaches the caller. Use `saveOrFail()` to make the save and
  its touches one transaction.
- `withoutTouching` applies to the calling thread only; a thread started inside it touches.
- `push()` saves the same owner once per touching child that changed, because each save runs its
  own touch walk.
- `touchOwners()`, `push()` and `toMap()` recurse once per level of the loaded graph; a very deep
  self chain can overflow a small thread stack, such as the default on Android.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md). The decisions are in
[ADR 0015](../../adr/0015-eloquent-models.md), for casts
[ADR 0016](../../adr/0016-attributes-casts-and-the-json-codec.md), for relations
[ADR 0017](../../adr/0017-relations.md), and for aggregates, touches, push and chaperone
[ADR 0018](../../adr/0018-relations-s2.md).
