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
| `has` / `whereHas` | Refused; use `whereHasMorph` |

An unknown type in an eager load throws before any query runs.

## Differences from Laravel

| Laravel | Diwan |
|---|---|
| The map is optional; the class name is stored without one | The map is required unless you opt out |
| An alias may be re-bound; a class may have two aliases | One alias per class, one class per alias |
| The alias "0" falls back to the class name in `getMorphAlias` | "0" is a real alias |
| `morphTo` guesses its name from the call stack | The name is passed explicitly |
| Builder calls on a `morphTo` are replayed per type | Not supported; per-type constraints arrive with `constrain` |
| The morph class is read when the relation is built | It is read when the query runs |

See [ADR 0027](../../adr/0027-morph-relations.md).
