# 0019. Relations: belongsToMany read side

## Status

Accepted

Superseded in part: attach, detach and sync arrived later (ADR 0020).

## Context

On top of the relations in [ADR 0017](0017-relations.md) and [ADR 0018](0018-relations-s2.md),
Laravel developers expect a many-to-many relation: a user has many roles through a joining table
that carries its own columns, such as `status` or `created_at`. They expect to read, filter, sort,
count and eager load through that table. A straight port of Laravel's `belongsToMany` would copy
four things that break on Diwan's constraints. Laravel overrides `get()`, but Diwan's
`Relation.get()` is `final` so the other relation kinds stay unchanged, so the select and hydration
steps need hooks. Laravel's `paginate`, `chunk` and the other iterators add select columns to the
relation's own query, so a second call repeats them. Laravel's `migratePivotAttributes` moves every
attribute whose name starts with `pivot_` into the pivot, so a related column with that name
disappears from the model without a warning. Laravel's `chaperone` does nothing until a pivot class
is set, and the call order decides it. The self-join also changes the table of the related model
object.

On Android, SQLite on API 28 allows 999 bound parameters, which caps an eager key list, and R8
renames simple class names, which makes the default table and key names unstable.

## Decision

1. `BelongsToMany<R>` is public and not final, with a package-private constructor, so only classes
   in its package can extend it. Its chaining methods return `BelongsToMany<R>`, so a subclass keeps
   the full type. `Pivot` is a public model class that an application extends to give the joining
   row its own methods, casts and hidden columns.

2. The join is added on each path that builds a query: the lazy query, the eager query for each key
   chunk, and the existence query. It is never added when the relation is built. The existence query
   adds the join after it has chosen the self-join alias, so the join names that alias. Each eager
   chunk starts from a copy without the join, so each chunk gets its own.

3. Every related row passes through one step once its attributes are set. The step moves each
   attribute whose name starts with `pivot_` out of the model's attributes into the joining row,
   under the name without the prefix, and links the joining row as the `pivot` relation, or as the
   name given by `as`. Only the attributes change. The original values stay, so the model is clean,
   a save writes none of them, and `toMap()` leaves them out. `getOriginal()` still lists them, and
   `discardChanges()` copies them back into the attributes, as Laravel does. `replicate()` copies
   the attributes only, so the copy does not get them.

4. Select semantics. A read on a relation without a select uses the related table's columns, or the
   columns passed to `get`. A read on a relation with a select keeps the selected columns and
   ignores the columns passed to `get`. `chunk`, `chunkById`, `each`, `lazy`, `lazyById`, `cursor`
   and the page methods append the related columns to whatever is selected, as Laravel does. The
   pivot aliases are added last on every path and are always present. Each read works on a copy of
   the relation's query, so the same call twice sends the same SQL.

5. `cursorPaginate` refuses an order on a column of the joining table when the column is qualified
   with that table's name, with an `IllegalStateException` that names the column. It accepts an
   order on the alias `pivot_<column>`, which is in the row the cursor reads. An order on a
   joining-table column would read a missing or wrong value for each page. An unqualified name is
   read as a related column, and a name that exists only in the joining table reads as null, so
   qualify it or use the alias.

6. `chaperone()` links each joining row to its parent and to its related row only when a pivot class
   was set with `using` before the call. Without one it does nothing and reports nothing, as Laravel
   does. Explicit names are checked against the pivot class's relations at the call. Without names,
   the inverse is guessed from the pivot keys and the model names, and a side with no match stays
   unlinked without an error. After an eager match, the parent link points at each row's own parent.

7. A joining row with no primary key value refuses `update`, `delete`, `increment`, `decrement`,
   `refresh` and `fresh` with an `IllegalStateException` that names the class and the table. The
   refusal comes before any statement, because a statement would filter on a key the joining table
   may not have. [ADR 0020](0020-relations-s3b.md) adds the key-less save and delete, so the refusal
   stays only for `increment`, `decrement`, `refresh` and `fresh`.

8. On a registry miss, the one `Class.forName` in the module loads the class by its own name,
   through its own class loader, and the lookup runs once more. A class literal does not run the
   static initialiser, so a pivot class named with `using(Membership.class)` may be unregistered in
   a fresh JVM. A class with no registry entry still throws `UnregisteredModelException`. R8 keeps a
   class used as a literal, so no keep rule is needed.

9. Without a table name, the joining table is the two class names in snake case, sorted, lower-cased
   and joined with an underscore. `User` and `Role` give `role_user`, `BlogPost` and `Tag` give
   `blog_post_tag`, and `Zeta` and `alpha` give `alpha_zeta`. The pivot keys default to the two
   models' foreign keys, and the parent and related keys default to their primary keys. A self
   relation without a table or keys gets the same pivot key on both sides, so the caller passes
   both.

10. On Android, the default table, the default keys and the default inverse names depend on simple
    class names, which R8 renames. Pass the table, the two pivot keys and the chaperone's relation
    names as strings there.

11. Index advice for the joining table: a unique index on (foreign pivot key, related pivot key) and
    an index on the related pivot key. `withCount` and `has` run one correlated subquery per outer
    row, and each looks up the joining rows by the foreign pivot key. The leading column of the
    unique index serves that lookup. The index on the related pivot key serves lookups from the
    related side.

## Consequences

- The read side uses no reflection, annotations or keep rules, and runs the same way on SQLite,
  PostgreSQL and Android. - Finders, iteration and pages exist on `BelongsToMany` only. `hasOne`,
  `hasMany` and `belongsTo` gain no method. - A chain of `or` clauses that a caller adds to the
  relation is applied in call order on direct reads, as in Laravel. Its second clause can match rows
  of other parents. Existence and aggregate subqueries group the clauses that the relation method
  declares, so they cannot widen the parent match. A caller groups its own chain with the relation's
  `where(Consumer)` method. The behaviour page shows the form. - Writes through the relation
  (attach, detach, sync, the find-or-create family, and touches through a joining table) are not
  part of this read side; [ADR 0020](0020-relations-s3b.md) covers them. - A related column whose name starts with `pivot_` moves into the joining
  row, so such a column must be renamed. - Hydration does not cast values. A cast applies when a
  joining value is read. - A relation instance is single-use and single-threaded, as for the other
  kinds. The read side keeps no static mutable state.

## Alternatives rejected

- Adding the join in the relation's constructor, as Laravel does: the existence query chooses its
  alias later, and each eager chunk starts from a copy without the join. - Overriding `get()` in a
  subclass, as Laravel does: `Relation.get()` is final for the other kinds, so two hooks replace the
  override and leave those kinds unchanged. - Adding the select to the relation's own query in the
  iterator and page methods, as Laravel does: a second call would repeat the columns. - Keeping the
  `pivot_*` values in the attributes: the model would be dirty after every read, and a save could
  write them. - An identity map of related rows: two joining rows for one parent and one related row
  give two instances, each with its own joining row. - A closure form of `wherePivot`: the core
  where clauses are immutable and cannot be requalified, so the closure form waits for a core
  change. - Grouping every `or` on a direct read, as existence queries do: it changes the SQL of the
  same Laravel calls. The caller can group the chain itself. - Reading a joining-table order value
  from the row for `cursorPaginate`: it needs a cursor hook in the core. The refusal and the
  `pivot_` alias keep the cursor correct without that hook. - Looking up the pivot class by name on
  every call: the one `Class.forName` runs only on a registry miss.
