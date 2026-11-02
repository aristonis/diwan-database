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
| Configure a model | override `table`, `primaryKey`, `keyType`, `incrementing`, `connectionName`, `timestamps`, `createdAtColumn`, `updatedAtColumn`, `fillable`, `guarded`, `perPage`, `dateFormat`, `timestampFormat`, `uniqueIdKind` |
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

## Limits

- Models are not thread-safe. Share the `ModelQuery`; give each thread its own model instances.
- Without casts, a `Boolean` set on a column SQLite stores as the integer `1` reads back as `1`
  and stays dirty against `true`.
- Irregular plurals outside the short built-in list get a wrong table guess; override `table()`.
- UUID version 7 and ULID keys made in the same millisecond are not ordered among themselves.
- A `ModelCursor` that is neither exhausted nor closed keeps its connection. Use
  try-with-resources.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md). The decisions are in
[ADR 0015](../../adr/0015-eloquent-models.md).
