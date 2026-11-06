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
- `casts()` and `accessors()` are read once per class and must not use instance state.
- Model `upsert` and `insertOrIgnore` skip events and casts. `upsert` needs SQLite 3.24.

Docs: Models and Relations on https://aristonis.org/open-source/diwan-database/docs/main/home
