# 0027. Morph relations and an alias-first morph map

## Status

Accepted

## Context

Laravel's polymorphic relations (`morphOne`, `morphMany`, `morphTo`) store the parent in two
columns, `<name>_type` and `<name>_id`. Without a morph map Laravel writes the parent's full class
name into the type column, so renaming or moving a class breaks every stored row. The map is
optional, `enforceMorphMap` turns the fallback into an error, and the map is one static array.

Among the Java libraries we studied, only javalite (ActiveJDBC) has real polymorphic associations.
It fixes the column names to `parent_id` and `parent_type`, stores the class name unless the child
lists labels, and keeps the labels on each child, so they can drift apart. Ebean and MyBatis map a
discriminator value to a subclass, which is inheritance, not this. Ebean throws on an unknown value;
MyBatis falls back silently.

Java has no backtrace to guess a relation's name and no `__call` to defer builder calls, and a row's
type must become a class without trusting the database to name one.

## Decision

1. One global morph map, alias to model class, published as an immutable snapshot. Writes are
   synchronized and copy-on-write, so a reader never sees half of a change.

2. The map is required by default. A model with no alias throws `ClassMorphViolationException` when
   its morph class is needed. `MorphMap.requireMorphMap(false)` restores Laravel's fallback to the
   class name.

3. The map is a strict bimap: an alias names one class and a class has one alias. Re-binding either
   throws. Aliases are compared as exact strings, so "0" is a real alias.

4. In the fallback mode a stored class name is loaded without running its initializer, checked to be
   a model, and only then registered. A type that names any other class is refused before its code
   runs.

5. `morphTo` takes its name explicitly. The class comes from the map, never from reflection on the
   name, except in the fallback mode of point 4.

6. The type value is always a bound parameter, read when the query runs, so a map changed after a
   relation was built is honored.

7. Eager `morphTo` groups the children by type, resolves every type before the first query, and runs
   one chunked `in` query per class. Matching uses the resolved class and the normalized key, so
   Post 5 and Video 5 stay apart and a key stored as text still matches a number.

8. `has` and `whereHas` on a `morphTo` are refused and point to `whereHasMorph`, which needs the list
   of types.

## Consequences

- Applications must register aliases before they use morph relations, or opt out.
- The map is shared by every connection and manager in the JVM. Tests that touch it reset it, and
  parallel tests need a map per manager first (backlog).
- The map holds strong class references, so a container that redeploys pins the old class loader.
- A user constraint on an eager `morphTo` runs on every type, so it may name only columns that every
  type has. Per-type constraints come with `constrain` and `morphWith`.

## Addendum: many-to-many morph

- `MorphToMany` extends `BelongsToMany`. The type filter is added where the pivot join is built,
  the one step shared by lazy reads, eager loads and existence queries.
- Every query on the pivot table built by the relation (detach, update, touch, the sync diff)
  carries the type, through a hook on the pivot query.
- Pivots are `MorphPivot`, whose save and delete keys include the type, so a pivot read for one
  parent type can never change another type's row. A `using` class must extend `MorphPivot`.
- Fluent pivot methods are overridden with a `MorphToMany` return type, so chaining keeps the morph
  getters; changing the return type after release would break source.
- Inserts add the type through the shared row builder, so bulk inserts and per-row pivot saves
  carry it alike. A different type value in attributes, pivot values or a pivot's own save is
  refused, because it would move a link to another parent type.
