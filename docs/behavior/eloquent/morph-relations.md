# Morph relations

A morph relation lets one table belong to more than one parent table. A `comments` table can hold
comments on posts and on videos: `commentable_type` says which parent table, and `commentable_id`
holds that parent's key.

## Columns

Create them with `morphs` (or `nullableMorphs`, `uuidMorphs`, `ulidMorphs`) in a migration:

```java
schema.create("comments", t -> {
    t.id();
    t.text("body");
    t.morphs("commentable");   // commentable_type, commentable_id, and an index on both
});
```

## The morph map

The type column stores an alias, not a class name. Register every model that can be a parent:

```java
Map<String, Class<? extends Model>> map = new LinkedHashMap<>();
map.put("post", Post.class);
map.put("video", Video.class);
MorphMap.morphMap(map);
```

| Rule | Behavior |
|---|---|
| Required by default | A model with no alias throws `ClassMorphViolationException` when its morph class is needed |
| Opting out | `MorphMap.requireMorphMap(false)` stores and reads the class name, as Laravel does without a map |
| One alias per class | Binding an alias to a second class, or a class to a second alias, throws `IllegalArgumentException` |
| Exact strings | Aliases compare as exact strings; "0" is a valid alias |
| Lookups | `getMorphedModel(alias)`, `getMorphAlias(class)`, `morphMap()`, `Model.getMorphClass()` |
| Global | One map for the whole JVM; register it once at startup |

`getMorphClass()` can be overridden on a model.

When the map is not required, a stored class name is loaded without running its static code. It
must be a model class, or the read throws `ClassMorphViolationException`.

## Declaring

```java
public final class Post extends Model {
    public MorphMany<Comment> comments() {
        return morphMany(Comment.class, "commentable");
    }

    public MorphOne<Image> image() {
        return morphOne(Image.class, "imageable");
    }
}

public final class Comment extends Model {
    public MorphTo<Model> commentable() {
        return morphTo("commentable");
    }
}
```

The long forms take the type column, the id column and the local key (`morphOne`, `morphMany`) or
the owner key (`morphTo`). Register each relation in `relations()` like any other.

## morphOne and morphMany

They work like `hasOne` and `hasMany`, with the parent's alias added to every query: lazy and eager
loads, `has`, `whereHas`, `withCount` and the other aggregates. On a relation to the same table, as
with comments on comments, the type condition names the inner table's alias.

Writes (`make`, `save`, `saveMany`, `create`, `createMany`) set both the type and the id.
`MorphMany.one()` returns a `MorphOne` on the same columns, keeping the clauses added to the
`MorphMany`.

## morphTo

| Case | Result |
|---|---|
| Type and id set | Loads the parent of the class the alias names |
| Type null or empty, or id null | No query; null, or the `withDefault` model |
| Unknown alias | `ClassMorphViolationException` naming the alias |
| Eager `with("commentable")` | One query per parent class; keys stored as text match numeric keys |
| `with("commentable.author")` | Loads `author` per parent class; every class must have that relation |
| `associate(model)` | Sets the id and `model.getMorphClass()` |
| `dissociate()` | Sets both columns to null |
| `touch()` | Does nothing unless the type and id are set |
| `has` / `whereHas` | Refused; use `whereHasMorph` (below) |

An unknown type in an eager load throws before any query runs.

## Filtering by a morphTo's parent

`hasMorph` and its forms add one group with a branch per type. Each branch checks the type and
whether a row of that type exists:

```java
Comment.QUERY.query()
    .whereHasMorph("commentable", Arrays.asList(Post.class, "video"),
        (q, type) -> q.where(type == Post.class ? "title" : "name", "like", "Java%"))
    .get();
```

| Method | Matches |
|---|---|
| `hasMorph(relation, types)`, with an operator and count | Rows whose parent of a listed type exists, or whose count compares |
| `doesntHaveMorph` | Rows with no parent of a listed type, and rows whose type is null |
| `whereHasMorph(relation, types, constraint)` | As `hasMorph`, with a constraint on the parent query; it receives the parent class |
| `whereDoesntHaveMorph` | As `doesntHaveMorph`, with a constraint |
| `whereMorphRelation(relation, types, column, [operator,] value)` | A one-column `whereHasMorph` |
| `whereMorphDoesntHaveRelation` | A one-column `whereDoesntHaveMorph` |
| `or...` forms | The same, joined with `or` |

- Types are model classes or aliases. An unknown alias throws `ClassMorphViolationException` before anything is added.
- `"*"` stands for every type in the table. It runs a `select distinct` on the type column while the query is built, without global scopes, on the same connection. On an empty table the positive forms match nothing and the negative forms add nothing. The lookup
  scans the type column each time the query is built, so on a large table keep the index `morphs()`
  creates, or pass the list of types.
- A dotted relation name, or a relation that is not a `morphTo`, is refused.

## Filtering by what a morphTo points at

| Call | SQL |
|---|---|
| `whereMorphedTo("commentable", post)` | `(type = ? and id = ?)`; the value is the model's owner key when the `morphTo` declares one |
| `whereMorphedTo("commentable", Post.class)` or `"post"` | `type = ?` |
| `whereMorphedTo("commentable", collection)` | One `(type = ? and id in (...))` group per class, joined with `or` |
| `whereMorphedTo("commentable", null)` | `type is null` |
| `whereNotMorphedTo(...)` | `(not (...) or type is null or id is null)`; for null, `type is not null` |

An empty collection is refused. Every key is one bound value, so a very large collection can pass the
driver's parameter limit (999 on old SQLite, 65535 on PostgreSQL); split it, or filter with a subquery.

## Different eager loads per parent class

```java
Comment.QUERY.query()
    .withMorph("commentable", m -> m
        .morphWith(Post.class, "author")
        .morphWithCount(Video.class, "likes")
        .constrain(Post.class, q -> q.whereNotNull("published_at")))
    .get();
```

- Keys are classes or aliases and are resolved when they are registered. An unknown alias throws at once.
- Each class's options, and the loads its `constrain` sets, apply only to that class.
- `withMorph` and `with` on the same relation merge in any order without losing the options.
- On loaded models: `loadMorph(relation, Map<Class, List<String>>)` and `loadMorphCount`, on a model or a `ModelCollection`. The `morphTo` is loaded first when it is not loaded yet.

## Differences from Laravel

| Laravel | Diwan |
|---|---|
| The map is optional; the class name is stored without one | The map is required unless you opt out |
| An alias may be re-bound; a class may have two aliases | One alias per class, one class per alias |
| The alias "0" falls back to the class name in `getMorphAlias` | "0" is a real alias |
| `morphTo` guesses its name from the call stack | The name is passed explicitly |
| Builder calls on a `morphTo` are replayed per type | Not supported; use `withMorph` with `constrain` |
| `doesntHaveMorph` adds null-type rows only for `"*"` | Null-type rows match every negative form |
| `morphWith` and friends are called on the relation in the eager closure | `withMorph(name, options)` |
| `hasMorph` merges the `morphTo`'s own query constraints into each type | Each type gets a fresh query; put shared conditions in the constraint |
| The morph class is read when the relation is built | It is read when the query runs |

See [ADR 0027](../../adr/0027-morph-relations.md).
