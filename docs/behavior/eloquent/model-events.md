# Model events and observers

Code outside a model class can react to its life cycle. Register a listener for an event, or an
observer for all of them, on the model's `ModelQuery`. The model's own protected hooks (`saving()`,
`created()` and the rest) stay as they are and run before any listener.

## Registering listeners and an observer

Register once, at start-up:

```java
ModelQuery<Post> posts = Post.query;

posts.events()
        .saving(post -> {                       // halting: return false to stop the save
            if (post.getString("slug") == null) {
                post.setAttribute("slug", slugOf(post.getString("title")));
            }
            return true;
        })
        .created(post -> auditLog.record("post created", post.getKey()))
        .deleted(post -> cache.forget("post:" + post.getKey()));

public final class PostObserver implements ModelObserver<Post> {
    @Override
    public boolean deleting(Post post) {
        return !post.getBoolean("locked");      // false halts the delete
    }

    @Override
    public void saved(Post post) {
        search.reindex(post.getKey());
    }
}

posts.events().observe(new PostObserver());
```

Listeners are static per model class and are never removed except by `forget()`. Register them
once at start-up, not per request, or every registration fires.

A registrar method returns the registrar, so calls chain. A null listener or observer is refused
with a `NullPointerException`. An observer overrides only the events it wants; every other method
does nothing, or returns `true` for a halting event.

## Events

| Event | Fires | Halts on `false` |
|---|---|---|
| `retrieved` | for each row loaded from the database, and for the model `fresh()` returns | no |
| `saving` | at the start of `save`, before `creating` or `updating` | yes |
| `saved` | after `save` wrote the row | no |
| `creating` | before the INSERT | yes |
| `created` | after the INSERT | no |
| `updating` | before the UPDATE of `save`, `increment` and `decrement` | yes |
| `updated` | after that UPDATE | no |
| `deleting` | at the start of `delete`, also during `forceDelete` | yes |
| `deleted` | after the row was deleted or soft-deleted | no |
| `softDeleted` | after the soft-delete UPDATE, even when it matched no row | no |
| `restoring` | at the start of `restore` | yes |
| `restored` | after `restore` saved | no |
| `forceDeleting` | at the start of `forceDelete` | yes |
| `forceDeleted` | after `forceDelete` removed the row | no |
| `replicating` | on the copy `replicate` returns, after its attributes are set | no |

A soft delete fires `deleting`, `softDeleted`, `deleted` in that order. A `forceDelete` fires
`forceDeleting`, `deleting`, `deleted`, `forceDeleted`. `retrieved` costs next to nothing when no
listener is registered.

## Order and halting

For every event the model's own hook runs first. Then the listeners run in registration order, and
an observer runs in the slot where it was registered, so a listener added after `observe` runs
after the whole observer.

On a halting event the first `false` stops everything: a `false` from the own hook skips the
listeners, a `false` from a listener skips the ones after it, and the write does not happen
(`save` and `delete` return `false`). A listener that returns `true` does not stop the rest. Only
the six halting events can be halted; a non-halting listener has no result to return.

A listener that throws stops the write at that point, and the exception reaches the caller, as an
exception from an own hook does. Inside `saveOrFail` the transaction rolls back.

A `saved` listener that throws during a plain `save()` leaves the row written but the model dirty:
the originals are not synced and the owners are not touched, so a retry sends the update again.
Laravel behaves the same. Use `saveOrFail()` to roll the write back.

When a `saving` or `updating` listener vetoes a `restore()`, the model's deleted-at value is
already cleared in memory, as in Laravel. Call `refresh()` to reload it.

## Running without events

```java
Model.withoutEvents(() -> post.save());                 // no listener or observer fires
String slug = Model.withoutEvents(() -> post.getString("slug"));
```

`withoutEvents` mutes listeners and observers of every model class, for the current thread only.
Calls nest, and the mute ends when the block throws. The mute does not cross threads: work handed
to an executor, a `CompletableFuture` or an Android handler from inside the block runs with events
on. The model's own hooks still run under `withoutEvents`, because they hold the model's
invariants.

### Quiet writes

| Method | Runs with events muted |
|---|---|
| `saveQuietly()` | `save()` |
| `updateQuietly(Map)` | `update(Map)` |
| `deleteQuietly()` | `delete()` |
| `touchQuietly()` | `touch()` |
| `touchQuietly(String)` | `touch(String)` |
| `replicateQuietly(String...)` | `replicate(String...)` |
| `restoreQuietly()` | `restore()`, soft-deleting models |
| `forceDeleteQuietly()` | `forceDelete()`, soft-deleting models |

## Writes made by a listener

A listener that writes another row runs on whatever connection that write uses. Inside
`saveOrFail`, which wraps the save in a transaction, a write joins the transaction only when it
goes through the saving model's own factory:

```java
posts.events().created(post ->
        AuditEntry.query.on(post.getQueryFactory())          // the transaction's factory
                .create(Map.of("event", "post created", "post_id", post.getKey())));
```

Through `getQueryFactory()` the audit row rolls back with the post. A write through any other
factory commits on its own and stays when the transaction rolls back.

A listener that saves the model it was called for runs the whole save again, so it calls the
listener again. It recurses until the stack overflows unless it guards itself or saves with
`saveQuietly()`:

```java
posts.events().saved(post -> {
    post.setAttribute("search_text", build(post));
    post.saveQuietly();                    // plain save() here would call this listener again
});
```

## Which class owns the listeners

Listeners belong to the class registered with `ModelQuery.of`. A model finds them through the
entry it was loaded or built through, and then through the registered entry of its own class. A
subclass or a custom pivot class that is not registered on its own therefore uses the listeners of
its source entry. A class registered separately has its own list: a `Post` listener does not fire
for a separately registered `Post` subclass. A model that no entry reaches fires only its own
hooks.

`forget()` removes every listener and observer of the class. Tests call it after each case,
because the registry is static. `hasListeners()` tells whether any is registered.

## Builder mass writes fire nothing

`query().update(...)`, `query().delete()` and the other builder writes run one statement and load
no model, so no event fires, as in Laravel. To fire events, load the model and call `save` or
`delete` on it.

## Differences from Laravel

| ID | Laravel | Diwan | Why |
|---|---|---|---|
| DL-1 | Listeners live on a global dispatcher keyed by event name strings | A registry per model class on `ModelQuery`, with typed registrar methods | No container, no string lookup, safe under R8 |
| DL-2 | The first non-null listener result stops the rest, so `true` silences later listeners | Only `false` halts; `true` continues | Laravel's rule is a trap |
| DL-3 | `withoutEvents` swaps the dispatcher for every thread | The mute is per thread | One request cannot silence another |
| DL-4 | `withoutEvents` also mutes the model's booted closures | Own hooks still run | They hold the model's invariants |
| DL-5 | The soft-delete event is `trashed` | `softDeleted` | `trashed()` is the state getter |
| DL-6 | No dispatcher set means no events | Events are always on | There is no dispatcher to set |
| DL-7 | `replicating` fires through the dispatcher on the copy | The same, on the copy after its attributes are set | Same result, a listener sees the copy |
| DL-8 | `restored` fires even when the save inside `restore` was vetoed | `restored` fires only when `restore()` saved | A restore that did not happen reports no restore |

## Not in this release

- After-commit listeners (`ShouldHandleEventsAfterCommit`, `afterCommit`). A listener runs inside
  the write, before the transaction commits.
- Custom event classes through `$dispatchesEvents`.
- The `ObservedBy` attribute. Register an observer with `observe`.
- The `booting` and `booted` events.
- String event names and wildcard listeners.
