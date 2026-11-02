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

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md). The decisions are in
[ADR 0015](../../adr/0015-eloquent-models.md) and, for casts,
[ADR 0016](../../adr/0016-attributes-casts-and-the-json-codec.md).
