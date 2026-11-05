# 0022. Global scopes, local scopes and soft deletes

## Status

Accepted

## Context

A model query in Diwan repeats every constraint by hand. A tenant filter or a filter that hides
deleted rows must be written on each query, and a filter written on a builder is lost when the
builder is copied for an eager load or an existence sub-query. `delete()` removes the row for good,
so there is no way to keep a row that the application has retired.

Laravel solves these with global scopes, local scopes and the `SoftDeletes` trait. Its scopes are
found by reflection on method names and attributes, and its trait is a class mixin. Diwan must run
on Android with R8, which renames classes and methods and so breaks name lookups, and Java has no
traits. The Laravel names and the SQL shape are kept; the mechanism is not.

## Decision

1. A `Scope` is a non-generic interface with one method that adds clauses to a builder for a
   model, and one default method that runs once when a builder takes the scope.

2. Global scopes are registered per model class on `ModelQuery`. A scope registered without a
   name is keyed by its class name. A lambda, an anonymous class or a synthetic class has no
   stable class name, so it must be registered under an explicit name. The soft-deleting scope
   has a constant key that does not change when the class is renamed.

3. Scopes are applied lazily. Each terminal call, such as a read, a count, an update, a delete or
   a `toSql()`, first copies the builder, applies the scopes to the copy in order, and runs on the
   copy. The caller's builder is not changed by the terminal. A scope may add query clauses only.
   Eager loads, casts, aggregate metadata and row hooks set by a scope are not carried by the copy,
   so the terminal refuses them with an exception that names the scope.

4. The clauses a global scope adds are joined with `and` and grouped in parentheses when they
   contain an `or`. Clauses added by a local scope follow the connector rule Laravel uses.

5. Soft deletes are a model setting, `softDeletes()`, with the column named by `deletedAtColumn()`.
   A soft-deleting model gets the soft-deleting scope attached first to every builder, and that
   scope hides trashed rows. `delete()` sets the column and, when the model writes timestamps,
   the updated-at column to the same moment, in one keyed UPDATE. `forceDelete()` removes the row.

6. A builder's `forceDelete()` applies every global scope except the soft-deleting one, so a
   tenant filter still holds when trashed rows are deleted for good.

7. `Model.fresh()` and `Model.refresh()` read the row by its key without scopes, as Laravel does,
   so a trashed model can be reloaded. Eager loads and lazy relation reads apply the related
   model's scopes.

8. The soft-delete lifecycle has five protected hooks on `ModelSoftDeletes`: `restoring`,
   `restored`, `softDeleted`, `forceDeleting` and `forceDeleted`. The two before-hooks can cancel
   their operation. They are the attachment points for model events, which are not part of this
   decision.

9. The filter that hides trashed parents of a through relation is a plain where clause, not a
   removable global scope. Removing the related model's scopes therefore cannot expose trashed
   parents.

## Consequences

- A scope object is shared by every thread and every builder of its model, so it must be stateless
  or thread safe. A scope that runs I/O or a terminal on the builder it receives is a defect.
- Each terminal call on a builder with scopes makes one copy of the builder. A builder without
  scopes is not copied, so the cost is paid only where a scope is attached.
- Scopes registered as lambdas stay in memory until the host removes them with
  `removeGlobalScope` or `clearGlobalScopes`.
- A scope that silently fails to apply is a data leak. The unscoped escape hatches are named in the
  behavior documentation so that each one is a deliberate choice.
- A unique index on a soft-deleting table also covers trashed rows. `restoreOrCreate` finds such a
  row and restores it, while a plain `create` with the same key fails on the index.
- Behavior that differs from Laravel is listed in the behavior documentation.

## Alternatives rejected

- Annotations and reflection on method names (`#[ScopedBy]`, `scopeX`): R8 renames the methods and
  the annotations are not kept, so the lookup fails at run time on Android.
- A marker interface or a base class for soft deletes: a model has one superclass and Java has no
  mixin, so the setting is a method the model overrides instead.
- Builder macros: `EloquentBuilder` is final, so its extensions are typed methods.
- Applying scopes when a builder is created: a scope set fixed at construction cannot be changed
  by `withoutGlobalScope` on the same builder. Applying them at the terminal keeps the removal
  calls simple.
- Scope inheritance from a superclass: without a per-subclass boot step, each class registers its
  own scopes, so inheritance is not offered.
- Scopes that add eager loads or casts: that state is lost with the copy. Refusing it at the
  terminal is safer than losing it silently.
