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

## After-commit listeners and observers

A listener normally runs inside the write, before the transaction commits. When it must act only
on work that is really saved (send a mail, clear a cache, tell a search index), register it after
commit:

```java
posts.events().afterCommit()
        .created(post -> mailer.sendWelcome(post.getKey()))
        .deleted(post -> cache.forget("post:" + post.getKey()));

public final class PostIndexer implements ModelObserver<Post> {
    @Override
    public boolean afterCommit() {          // only the methods below that cannot halt wait
        return true;
    }

    @Override
    public void saved(Post post) {
        search.reindex(post.getKey());      // runs after the commit
    }

    @Override
    public boolean saving(Post post) {      // still runs inside the write and can halt
        return !post.getBoolean("locked");
    }
}

posts.events().observe(new PostIndexer());
```

### What waits

| Registered through | Waits for the commit |
|---|---|
| `events().afterCommit().saved/created/updated/deleted/softDeleted/restored/forceDeleted` | yes |
| `ModelObserver.afterCommit()` returning `true` | its seven methods above; every other method runs where it always did |

The `ModelObserver.afterCommit()` answer is read once, when the observer is registered. A listener
runs after the outermost commit of the connection that wrote the model, on the thread that
committed, once the transaction has fully ended.

### What is refused

The after-commit view offers only the seven events that run after a write. `saving`, `creating`,
`updating`, `deleting`, `restoring` and `forceDeleting` halt a write that has not happened yet, so
they cannot wait. `retrieved` and `replicating` are not writes. Calling any of these on the view
throws `UnsupportedOperationException` naming the event. An observer that opts in keeps its halting
methods, `retrieved` and `replicating` running at their usual place.

### When it runs

- **Outside a transaction** the write is already committed, so the listener runs at once.
- **A rollback drops it.** A listener queued by a write that rolled back never runs, and a
  savepoint that rolls back drops its own.
- **A retried transaction runs it once**, after the attempt that committed. Attempts that failed
  queue nothing that survives.
- **The callback sees the model as it is when it runs**, not as it was when the event fired. If
  the code changes the model after `save()` returns, the listener sees the change, as in Laravel.
- **`withoutEvents` queues nothing.** Muted events are skipped before anything is deferred, and
  the quiet writes behave the same.
- **A listener that throws** cannot undo the write; the transaction has ended. The failure follows
  the rules of `afterCommit` on the connection, see [Connection events](../connection/events.md).
- **A listener that throws inside `saveOrFail`** finds the row already committed, so the model
  keeps its key, `exists` and attributes, and the exception propagates. A later `save()` updates
  the row instead of inserting it again. The model is reset only when the transaction did not
  commit. In a transaction the caller owns, the commit is not known, and the old restore rule
  applies.
- **A model with no connection** runs its after-commit listeners at once and is not checked for
  a transaction it cannot have. Its write fails anyway.
- **Each deferred callback holds its model** until the outermost commit. In a long import, commit
  in batches so the models can be released.

### Transactions you did not open

When your own code, or a framework, opened the transaction on the JDBC connection, the library
cannot know when it commits. A `save`, `delete`, `restore`, `forceDelete`, `increment` or
`decrement` of a class that has after-commit listeners is then refused with
`IllegalStateException` naming the model class, before any statement runs, so nothing is written.
Remove the after-commit listeners, or bind the model to a connection that owns its transaction.
A class without after-commit listeners is not affected, and neither is a write under
`withoutEvents`.

### Models bound to the root connection

A model bound to the root connection while you run a transaction on that root writes on its own
session. In strict mode that save is refused, as for every write. In lenient mode the row is
committed on its own session, outside your transaction, so the listener runs at once, and that is
correct because the row is already committed. To make the write and the listener join the
transaction, bind the model to the transaction's factory, as for any other write (see "Writes made by a listener" above).

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
| DL-9 | After-commit handling lives in `illuminate/events` (`ShouldHandleEventsAfterCommit`, `$afterCommit`) and is queued in the transaction manager | `events().afterCommit()` and `ModelObserver.afterCommit()`, run through the connection that wrote the model | The database package owns the transaction, so the model layer asks it directly |
| DL-10 | A halting event can be marked after commit and its result is lost | The after-commit view does not offer halting events | A veto that cannot veto is a trap |
| DL-11 | A callback inside a transaction the framework did not open is queued by the manager | The write is refused before any row changes | The library cannot know when that commit happens |
| DL-12 | Ebean runs its after-commit persist listeners on a background thread | The caller's thread; no background thread is used | Thread policy belongs to the application |

## Not in this release

- Custom event classes through `$dispatchesEvents`.
- The `ObservedBy` attribute. Register an observer with `observe`.
- The `booting` and `booted` events.
- String event names and wildcard listeners.
