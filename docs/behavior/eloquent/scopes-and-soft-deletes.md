# Scopes and soft deletes

Global scopes add a constraint to every query of a model. Local scopes are reusable query steps a
caller applies. Soft deletes keep a deleted row and hide it from reads. Each follows Laravel's
names and SQL shape, with the differences listed at the end of this page.

## Global scopes

A global scope is registered on the model's `ModelQuery`, once, at start-up:

```java
ModelQuery<Post> posts = Post.query.on(SQLiteQuery.of(db));

posts.addGlobalScope("tenant", q -> q.where("tenant_id", 7));
```

Every query of `Post` now carries `where "tenant_id" = ?`, including eager loads, lazy relation
reads, `has`, `whereHas`, `withCount` and the aggregates. A class scope is registered with
`addGlobalScope(Scope)` and keyed by its class name:

```java
public final class TenantScope implements Scope {
    @Override
    public void apply(EloquentBuilder<?> builder, Model model) {
        builder.where("tenant_id", 7);
    }
}

posts.addGlobalScope(new TenantScope());
```

A lambda, an anonymous class or a synthetic class has no stable class name, so
`addGlobalScope(Scope)` refuses it with an `IllegalArgumentException`. Give such a scope a name
with `addGlobalScope(String, Scope)` or `addGlobalScope(String, Consumer)`. Register it under the
same name wherever the name is stored or logged.

Registering a name that is already present replaces the scope and keeps its position.
`getGlobalScopes()` returns an unmodifiable map in registration order. `hasGlobalScope`,
`getGlobalScope`, `removeGlobalScope` and `clearGlobalScopes` read and change the same registry.
`clearGlobalScopes` removes every scope of the model class, which lets a host release the lambdas
it registered.

A class has exactly the scopes registered on its own `ModelQuery`. Scopes are not inherited from a
superclass.

### Removing a scope from one query

```java
posts.query().withoutGlobalScope("tenant")                    // one scope, by name
posts.query().withoutGlobalScope(TenantScope.class)           // one scope, by class
posts.query().withoutGlobalScopes()                           // every scope
posts.query().withoutGlobalScopesExcept(Arrays.asList("tenant"))  // all but the named ones
```

The removal applies to that builder only. `removedScopes()` returns the names removed so far, in
call order, with duplicates and names that were never attached still listed.

### Order

Scopes apply in a fixed order: the soft-deleting scope first, when the model soft deletes, and then
the registered scopes in registration order. A scope may rely on the clauses of the scopes before
it.

### Rules for scope code

- A scope may add query clauses only, such as `where`, `orderBy` or `whereNull`. Eager loads
  (`with`), `withCasts`, `withCount` and row hooks set by a scope are refused: the terminal throws
  `IllegalStateException` that names the scope and the model class.
- A scope is shared by every builder and every thread. It must be stateless or thread safe, pure
  and cheap, and it must not do I/O.
- A scope must not run a terminal, `toBase()`, `toSql()` or `getBindings()` on the builder it
  receives. Those calls apply the scopes again and recurse until the stack overflows.
- A scope's clauses are joined with `and`. If the scope adds an `or`, its clauses are wrapped in
  parentheses, so a scope can only narrow the result.

## Local scopes

A local scope is a reusable step the caller applies to a builder:

```java
posts.query().scope(q -> q.where("published", true).orWhere("editor_id", 3))
             .get();
```

`scopes(List)` applies several in order. A local scope keeps Laravel's connector rule: when either
the clauses before it or the clauses it adds contain an `or`, the added clauses are grouped in
parentheses, and the group joins the clauses before it with the connector of its first clause.

Local scopes are not registered on the model. They run once, when they are applied, and they are
not removed by `withoutGlobalScopes`.

## Soft deletes

A model opts in by overriding two settings:

```java
public final class Post extends Model {
    public static final ModelQuery<Post> query = ModelQuery.of(Post.class, Post::new);

    @Override
    protected boolean softDeletes() {
        return true;
    }

    @Override
    protected String deletedAtColumn() {
        return "deleted_at";        // the default
    }
}
```

With the setting on:

| You want to | Use |
|---|---|
| Soft delete a model | `delete()`: sets `deleted_at` and, when the model writes timestamps, `updated_at`, in one UPDATE |
| Remove the row for good | `forceDelete()` |
| Restore a trashed model | `restore()`: clears `deleted_at` and saves the model |
| Check whether a model is trashed | `trashed()` |
| Read trashed rows with live ones | `withTrashed()`, or `withTrashed(boolean)` |
| Read only trashed rows | `onlyTrashed()` |
| Read live rows again after `withTrashed()` | `withoutTrashed()` |
| Soft delete or force delete many rows | `delete()` and `forceDelete()` on a builder |
| Restore many rows | `restore()` on a builder |
| Find a trashed row or restore it | `restoreOrCreate(attributes)`, `createOrRestore(attributes)`, and their forms with values |
| Delete by key, with the model's hooks | `ModelQuery.destroy(ids)` (live rows only), `ModelQuery.forceDestroy(ids)` (trashed rows included) |

`delete()` on a model that does not soft delete removes the row, as before. `forceDelete()` and
`restore()` on a model that does not soft delete throw `IllegalStateException`. Pivot models do not
support soft deletes.

`restore()` on a model that is not trashed runs no statement. A soft delete that fails on the
database leaves `deleted_at` and `updated_at` as they were before the call.

A lifecycle method on the model can cancel an operation. Override `restoring()` or
`forceDeleting()` and return `false` to stop the restore or the force delete. `restored()`,
`softDeleted()` and `forceDeleted()` run after the operation.

### Unscoped reads

Some calls read without the model's scopes:

- `Model.fresh()` and `Model.refresh()` reload the row by its key. A trashed model reloads.
- `ModelCollection.fresh()` reloads each model by key, so a trashed or out-of-scope model stays in
  the result.
- `loadCount`, `loadSum` and the other `load` aggregates on a loaded model read the model by its
  key. A trashed model still gets its count. The counted sub-query is scoped.
- `Model.newBaseQuery()` and `query(Consumer<Builder>)` on a model give the core builder with no
  scopes. The clauses a caller adds are the only constraints.

`ModelCollection.load` applies the related model's scopes, as a lazy read does.

Each of these is a deliberate choice of the caller. Code that filters tenant data should read
through the model's `query()`, not through one of these calls.

### The unique index

A unique index on a soft-deleting table also covers trashed rows. A plain `create` that reuses a key
held by a trashed row fails on the index. `restoreOrCreate` and `createOrRestore` find the trashed
row, because they include trashed rows in the lookup, and restore it.

## Differences from Laravel

| Area | Laravel | Here |
|---|---|---|
| Builder `forceDelete()` | Skips every global scope | Skips only the soft-deleting scope, so a tenant scope still applies |
| `withTrashedParents()` on a through relation | Has no effect on existence queries and on the relation's own branches | One flag is read by every path the relation builds |
| Macros and `onDelete` callbacks | Installed by `extend` | Typed methods on the builder; `extend` is a default method on `Scope` |
| Soft delete of a trashed model | Moves `deleted_at` | Same, as documented |
| `restore()` on a live model | Fires events and saves | Same result; a clean model runs no statement. `restoreOrCreate` and `createOrRestore` call `restore()` only when the row is trashed |
| `removedScopes()` | Keeps duplicates and unknown names | Same |
| Closure scopes | Keyed by the closure's object identity | Must be given a name |
| Scope discovery | `#[ScopedBy]`, `bootX` methods, the `SoftDeletes` trait, `scopeX` method lookup | Registration on `ModelQuery`, the `softDeletes()` setting, and `scope(Consumer)` for local scopes |
| Inheritance of scopes | Scopes of a parent class apply to its subclasses unless a subclass boots them again | A class has exactly the scopes registered on its own `ModelQuery` |
| Through-parent soft-delete filter | A removable global scope, so `withoutGlobalScopes()` removes it | A plain where clause; only `withTrashedParents()` removes it |
| Scope clauses that add eager loads or casts | Run on the real builder | Refused with an exception; a scope may add query clauses only |
| Connector of a global scope's clauses | Taken from the scope's first clause | Always `and` |
| Attributes after a failed soft delete | Left set | Restored to their previous values |
