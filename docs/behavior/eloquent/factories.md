# Factories

A factory builds models for tests and seeding from a definition the caller writes. It lives in the
exported package `org.aristonis.diwan.database.eloquent.factory` with three public types, `Factory`,
`Sequence` and `RecycleMode`. Diwan holds no fake or random data and no dependency for it: the definition is the
caller's own lambda, a fixed value or any library.

```java
Factory<User> users = Factory.of(User.query.on(SQLiteQuery.of(db)), () -> Map.of(
    "name", "Ada", "email", "ada@example.com"));

User ada = users.state(Map.of("role", "admin")).create();
```

## Shape

- `Factory.of(query, supplier)` builds a factory. A subclass passes its `ModelQuery` to the
  constructor and implements `definition()`. A subclass names its states in methods that return
  `Factory<M>`.
- A factory is immutable. Every state, setting and hook method returns a new factory and the
  receiver never changes, so one factory is safe to share. The caller's lambdas decide the rest.
- Building a factory runs no query and no model. Everything happens in a terminal method.

## Values and states

The definition is layer zero. States are layers added in call order: `state(Map)`, `set(name, value)`,
`state(UnaryOperator)`, `sequence`, `forEachSequence`, `trashed`. A later layer replaces an earlier
key and the key keeps its position.

A value is resolved once, in key order of its layer:

1. a `Supplier` is called with no argument;
2. otherwise a `Function` is called with a read-only copy of the attributes resolved so far;
3. otherwise the value is used as is.

A resolved value is never resolved again. A `ClassCastException` raised while resolving a value is
rethrown as `IllegalArgumentException` naming the attribute and the model class, never the value,
with the original as cause.

## Terminals

| Method | Result | Count |
|---|---|---|
| `make()`, `makeMany()`, `makeMany(n)` | unsaved models | one, `count`, `n` |
| `create()`, `createMany()`, `createMany(n)` | saved models | one, `count`, `n` |
| `createQuietly()`, `createManyQuietly()`, `createManyQuietly(n)` | saved models, no model events | one, `count`, `n` |
| `raw()`, `rawMany()`, `rawMany(n)` | attribute maps | one, `count`, `n` |
| `insert()`, `insert(n)` | nothing | `count`, `n` |

The singular forms ignore `count`. The forms that take a number ignore `count`. A count below one
builds nothing. The plural forms without a number use `count`, or one when it is unset.

Models are filled without the fillable guard. `afterMaking` hooks run for each model after it is
built. `create` saves each model and throws `IllegalStateException` when a model event hook stops
the save or no row is stored; a failure from the save propagates and `afterCreating` does not run
for that model. `afterCreating` hooks run after a successful save. The quiet forms mute events once
for the whole batch.

`insert` builds the rows and writes them in one statement through the model's insert path, so casts
and timestamps apply. It builds no model, so it runs no `afterMaking` or `afterCreating` hook and
fires no event, though `beforeWrite()` still runs on each row. One statement has a binding limit (999 on SQLite before 3.32), and every row must
have the same keys, or `IllegalArgumentException` follows.

## Settings

- `count(n)`: the last call wins; `forEachSequence(entries...)` is `sequence(entries)` plus
  `count(entries.length)`.
- `afterMaking(hook)`, `afterCreating(hook)`: hooks run in the order added.
  `withoutAfterMaking()` and `withoutAfterCreating()` drop the hooks added so far.
- `connection(connection)`: build and save on another connection. It may be added in any position.
- `trashed()`, `trashed(Instant)`: sets the model's deleted-at column, by default one day before
  now on the model's clock. It reads the column from the model at build time, so an overridden
  column name works. A model without soft deletes throws `IllegalStateException` when a row is
  built.

## Sequences

`Sequence.of(maps...)` and `Sequence.byIndex(functions...)` build a sequence; `and` adds an entry
and returns a new sequence; `crossJoin(lists...)` builds every combination, the first list changing
slowest, a later key winning. A `Sequence` is immutable and safe to share.

Entry `i` of the models picks entry `i % size`. An `IntFunction` entry receives `i` itself, the
model position. A sequence restarts on each call: nothing is stored between calls, so two calls on
one factory give the same entries. `make`, `create` and `raw` use position zero.

## Exceptions

| When | Exception |
|---|---|
| `create` vetoed by a hook, or no row stored | `IllegalStateException` |
| `trashed` on a model without soft deletes | `IllegalStateException` |
| `ClassCastException` while resolving a value | `IllegalArgumentException`, cause kept |
| `Sequence.of()` or `byIndex()` with no entry | `IllegalArgumentException` |
| `crossJoin` with no list, an empty list, or a result too large | `IllegalArgumentException` |
| `insert` with rows of different key sets | `IllegalArgumentException` |
| `has` on a relation that is not HasOne, HasMany, MorphOne or MorphMany | `IllegalArgumentException` |
| `hasAttached` on a relation that is not BelongsToMany | `IllegalArgumentException` |
| `forParent` on a relation that is not BelongsTo or MorphTo | `IllegalArgumentException` |
| `hasAttached` with an empty pivot list or an unsaved model | `IllegalArgumentException` |
| unknown relation name, or a bad `relations()` map | `RelationNotFoundException`, `InvalidRelationException` |
| `forParent(Model)` not saved, a recycled model not saved | `IllegalStateException` |
| `has` when the parent has no value for the local key | `IllegalStateException` |
| `raw` with a `Factory` value or a `forParent(Factory)` | `IllegalStateException` |
| nesting deeper than 32 levels | `IllegalStateException` |
| a null argument, key or sequence entry | `NullPointerException` |

Messages name a class, a position or an attribute and never a value.

## Related models

`has(child, relation)` creates the children of each created model, for HasOne, HasMany, MorphOne
and MorphMany. The child factory's count is the number of children per parent. The parent is saved
first, then each child is created with the foreign key (and the morph type) set; those columns win
over the child's own states. Every parent gets its own batch, and a child runs its own create steps.

```java
Author ada = authors.has(posts.count(3), "posts").create();
```

`hasAttached` fills a BelongsToMany or MorphToMany with five forms: a factory; a factory and a pivot
`Map`; a factory and a `Function<Model, Map>` that receives the saved parent; a factory and a
`List<Map>` that cycles (an empty list throws); a list of saved models and a pivot `Map`, creating
nothing. Pivot values are written as given.

```java
Post post = posts.hasAttached(tags.count(2), Map.of("weight", 1), "tags").create();
```

## Owners

`forParent(parent, relation)` sets the owner of a BelongsTo or MorphTo. The parent is a factory or
a saved model; the method is not named `for` because that is a Java keyword. A factory parent is
created once per terminal call, so `count(3)` shares it. The key and type columns come before the
states, so a state can replace them.

```java
List<Post> hers = posts.forParent(ada, "author").createMany(3);
```

## Parent-aware states and hooks

`state(BiFunction)`, `afterMaking(BiConsumer)` and `afterCreating(BiConsumer)` receive the parent
as a second argument: the saved parent for a child made by `has` or `hasAttached`, otherwise `null`.
A `Sequence` still receives the index only.

```java
Factory<Post> titled = posts.state((soFar, parent) -> parent == null
    ? Map.<String, Object>of() : Map.<String, Object>of("title", "By " + parent.getKey()));
```

## Nested values

A `Factory` or `Model` value in a definition, a state or a returned value is replaced by a key after
all layers. A `Factory` creates a model (or takes a recycled one), a `Model` gives its key, `null`
stays `null`. A `Factory` value that a later layer replaces creates no row.

```java
Factory<Post> posts = Factory.of(Post.query.on(db), () -> Map.of("author_id", authors));
```

## Recycle

`recycle(models...)` and `recycle(List)` give one call a pool of saved models, grouped by exact
class. A nested factory takes a model of its class from the pool instead of creating one; a class
with no pooled model creates. `RecycleMode` picks: `IN_TURN` (default, in order and around),
`RANDOM`, `FIRST`. `recycleMode(mode)` sets it; `recycleRandom()` is `RANDOM` without a seed and
clears a set seed; `recycleRandom(long seed)` is `RANDOM` with a seed, so the same seed and pool
repeat the same picks on every call. `recycleMode(RANDOM)` keeps a seed already set.

```java
posts.recycle(ada, grace).recycleRandom(42L).count(5).createMany();
```

The pool lasts one terminal call and nothing is stored in the factory, so `IN_TURN` and a seed
restart on the next call. Only the factory the terminal is called on supplies the pool; a nested
factory's own `recycle` is ignored. A picked model that is not saved throws.

## Without parents

`withoutParents()` turns every nested `Factory` value of that factory into `null`;
`withoutParents(Class...)` and `withoutParents(String...)` limit it to a model class or an attribute
name. Calls add together. It does not reach into a nested factory, which keeps its own settings.

```java
posts.withoutParents(Author.class).create();
```

## Terminals and relations

| Terminal | Parents (`forParent` factory, `Factory` values) | `has`, `hasAttached` | Hooks |
|---|---|---|---|
| `make`, `makeMany` | created and saved | never run | `afterMaking` |
| `create`, `createMany` and the quiet forms | created and saved | run after the save | both |
| `insert` | created and saved | skipped | none |
| `raw`, `rawMany` | never created | never run | none |

`raw` never touches the database: a `Model` value or `forParent(Model)` gives its key, a `Factory`
value or `forParent(Factory)` throws `IllegalStateException`, even when a recycle pool holds the
class. Relation names and kinds are checked before the first row is written.

## Connection and depth

A nested factory uses the connection of the factory that created it unless it set its own with
`connection`. A model given to `forParent` or `hasAttached(List, ...)` keeps its own. Creation stops
at 32 levels with `IllegalStateException`. There is no transaction around a tree: rows already
written stay.

## Load relations

No method is needed: `afterCreating(a -> a.load("posts"))` runs after the children exist.

## Not included

There are no name-guessing helpers, no fake data and no dependency.
