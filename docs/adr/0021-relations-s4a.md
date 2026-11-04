# 0021. Relations: hasOneThrough and hasManyThrough

## Status

Accepted

## Context

[ADR 0019](0019-relations-s3a.md) and [ADR 0020](0020-relations-s3b.md) added `belongsTo`,
`hasOne`, `hasMany` and `belongsToMany`. A Laravel developer also expects a relation that reaches
the far side of an intermediate table: the posts of a country through its users, or the owner of a
mechanic's car through the car. Laravel offers these as `hasManyThrough` and `hasOneThrough`. Each
one joins the through table, filters the joined rows, and then reads, eager loads, checks existence
and counts through the usual relation machinery.

Two things stood in the way. The join and constraint code of `belongsToMany` and `hasOne` was not
shared, so a new join relation would have copied it. A through relation also needs a join in the
eager, lazy and existence paths, and the morph relations that come later need to add their own type
constraint to the same join. A single hook, called at the same three build points, lets all of them
share one order of joins and constraints.

Porting Laravel's shape raises four points that do not fit Diwan as they are. Laravel carries the
through key as a visible attribute, so it reaches `toArray`, dirty tracking and `save`. Diwan has no
soft deletes and no global scopes yet, so a through parent cannot be filtered by a deletion marker.
Laravel's `limit` on a through relation has a per-parent variant for eager loads, which Diwan's core
does not have. A through relation built with `firstOrCreate` and its siblings creates a row that is
not linked to the far parent.

## Decision

1. `Relation` declares a package-private hook, `performJoin(EloquentBuilder<R> target)`, with an
   empty body. The lazy constraint, each eager key chunk and the existence query call it before
   their own constraint. `BelongsToMany` overrides it with its pivot join. `HasOneOrMany` calls it
   in its three shared build methods, so a subclass added later, such as a morph relation, can
   override it and be reached from all three. For `HasMany` and `HasOne` the hook is empty and the
   SQL is unchanged.

2. `hasOneThrough` and `hasManyThrough` are built by the model through five overloads each. The
   full form takes the related class, the through class, the first key, the second key, the local
   key and the second local key. The shorter forms fill in Laravel's defaults: the first key is the
   model's foreign key, such as `country_id`; the second key is the through model's foreign key,
   such as `user_id`; the local key and the second local key are the two key names. A null key is
   refused. The through model is built once per call on the parent's connection.

3. The relation joins the through table on the through model's key, then constrains the joined rows
   to the far parent's key. Declared wheres are replayed after that constraint, as for `hasMany`.
   A far parent with a null key runs no query. A `hasManyThrough` then returns an empty
   collection, and a `hasOneThrough` returns its default model or `null`.

4. Each read works on a copy of the constrained query. `first`, `find`, `paginate`, `count` and the
   other reads never change the relation's own query. The copy selects the related columns and the
   through key. The finders qualify their key with the related table, so a bare `id` is never
   ambiguous. `chunk`, `chunkById`, `each`, `lazy`, `lazyById` and `cursor` share the same copy.
   `paginate`, `simplePaginate` and `cursorPaginate` count and page the joined rows.

5. The through key is read from each row by a row hook and removed from the model's attributes.
   `toArray`, `isDirty` and `save` never see it. During an eager load the relation keeps the key of
   each result in an identity map it owns. `match` reads that map and then clears it in a `finally`
   block. A result without a recorded key makes `match` throw `IllegalStateException`.

6. Eager loading adds the through key to the select of each chunk, and qualifies a bare column
   list from `with("posts:id,title")` with the related table. A limit or an offset on the eager
   constraint is refused, as for every other kind. A limit on a lazy read is a plain SQL limit.

7. `take(int)` is added to `RelationClauses` as an alias of `limit(int)`, so every relation kind
   has it. The through relations use it as a plain limit. They do not use Laravel's per-parent
   group limit, which Diwan does not have.

8. `firstOrNew`, `firstOrCreate`, `createOrFirst`, `updateOrCreate` and `findOrNew` look up the row
   through the relation, with the attribute names qualified by the related table. A row created
   by these methods is saved through the related model's own builder and is not linked to the far
   parent. Laravel behaves the same way. Each method's Javadoc says so.

9. A relation whose related table is the same as its through table is refused with
   `IllegalStateException` at the first query it builds. The message names the relation and the
   parent class. The check does not run in the constructor, because a relation map may build a
   relation that is never used.

10. The existence queries behind `has`, `doesntHave`, `whereHas`, `orHas`, `withCount`, `withExists`
    and the other aggregates have three branches. The normal branch joins the through table and
    correlates the far parent's key with the through key. When the outer table is the related table,
    the related table is aliased. When the outer table is the through table, the through table is
    aliased. The aliases keep the correlated names apart from the outer query.

11. The `ModelRelationActions` extends clause now names a new layer, `ModelThroughRelations`, which
    sits between `ModelManyRelations` and the actions. It holds only the builders. The read layer,
    the creator layer and the relation classes are separate files, so each stays under the line
    limit. Reads repeat the delegations of `PivotReads` rather than sharing a base class, because
    `PivotReads` extends the pivot clause layer and that layer would have to change.

12. `ofMany`, `latestOfMany` and `oldestOfMany` on `hasOne` and `hasOneThrough` are not part of this
    change. They need a sub-query joined on an aggregate, and their design is still open. They are
    the next relation work. The pieces built here, the join hook and the shared through layer, are
    what that work reuses.

13. Soft-deleted through parents are not handled. Laravel's `withTrashedParents` and the check for a
    soft-delete marker on the through model are not added. They wait for soft deletes and global
    scopes, which Diwan does not have.

14. Several Laravel methods are not added with this change: `chunkByIdDesc`, `eachById`,
    `lazyByIdDesc`, `chunkMap`, `incrementOrCreate`, `one`, `is`, `through` and the relation's
    `touch`. Each one is listed in the backlog for later work.

## Consequences

- `hasOneThrough` and `hasManyThrough` run the same way on SQLite, PostgreSQL and Android API 28.
  They add no dependency, and use no `List.of`, `Map.of` or records.
- The through key never reaches the model's attributes. A caller cannot save it by mistake, but
  the value is also not visible in `toArray` output.
- The eager path keeps one identity map per relation instance. The map is cleared on every `match`,
  so a relation that serves one eager load holds nothing after it. A relation is single use, as
  for the other kinds.
- Unqualified column names in a constraint or an order are ambiguous on the joined query. The
  caller qualifies them. Laravel behaves the same way.
- The creator methods can create a row that no relation reaches. A caller that needs the created
  row reached through the relation creates the intermediate row too.
- Soft-deleted rows of the through table are counted and read as live rows until soft deletes exist.
- A through relation shares the join and constraint order with `belongsToMany`, so a fix to that
  order reaches both kinds.

## Alternatives rejected

- Passing the through key as a visible attribute, as Laravel does: it would reach `toArray`, be
  seen as a dirty change and be written back by `save`. A row hook and a side map keep it out.
- Building a separate join path for each through relation: the lazy, eager and existence paths
  would then drift apart, and each later morph relation would copy the same join again.
- Porting Laravel's per-parent group limit: Diwan's core has no group limit, and an eager limit
  cuts the rows of every parent together, which is the case the existing refusal already covers.
- Creating the through relation's rows linked to the far parent: that changes the meaning of
  `firstOrCreate` from Laravel's. The method keeps Laravel's result, and the Javadoc states it.
- Sharing one read base between `belongsToMany` and the through relations: the shared base would
  need the pivot clause layer to change, so the delegations are repeated for now.
