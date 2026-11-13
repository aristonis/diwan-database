# Factories

A factory builds models for tests and seeding from a definition the caller writes. It lives in the
exported package `org.aristonis.diwan.database.eloquent.factory` with two public types, `Factory`
and `Sequence`. Diwan holds no fake or random data and no dependency for it: the definition is the
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
| a null argument, key or sequence entry | `NullPointerException` |

Messages name a class, a position or an attribute and never a value.

## Not included

`has`, `for` and `recycle` (related and shared parent models) come later. There are no name-guessing
helpers, no fake data and no dependency.
