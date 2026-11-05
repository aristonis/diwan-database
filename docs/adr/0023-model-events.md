# 0023. Model events and observers

## Status

Accepted

## Context

A model has protected hooks (`saving`, `created`, `deleting` and the rest) that its own class
overrides. Code outside the class, such as an audit log, a cache flush or a slug filler, had no way
to react to a save or a delete without editing the model.

Laravel offers listeners on a global dispatcher keyed by event name strings such as
`eloquent.saving: App\Post`, observer classes found by attribute, and `withoutEvents`, which swaps
the dispatcher for every thread. Diwan has no container and no dispatcher, runs on Android with R8
(which breaks name lookups), and serves concurrent threads. Laravel's `until` rule also lets any
non-null listener result stop the listeners after it, so a listener that returns `true` silences
the rest.

## Decision

1. Listeners are registered per model class through `ModelQuery.events()`, which returns a
   `ModelEvents<T>` registrar. A halting event (`saving`, `creating`, `updating`, `deleting`,
   `restoring`, `forceDeleting`) takes a `Predicate`; the others (`retrieved`, `saved`, `created`,
   `updated`, `deleted`, `softDeleted`, `restored`, `forceDeleted`, `replicating`) take a
   `Consumer`. Registration is typed and uses no reflection and no event name strings.

2. An observer is a `ModelObserver<T>` with a default method for every event, registered as one
   entry in the same list. Listeners and observers fire in registration order.

3. For each event the model's own hook runs first, then the listeners. Only `false` halts, and
   only on a halting event; the listeners after the one that said `false` do not run. A listener
   that returns `true` lets the rest run. A listener that throws stops the write at that point and
   the exception reaches the caller.

4. The registry is a copy-on-write snapshot held in an `AtomicReference`, as global scopes are.
   Registration is thread safe, a fire reads one snapshot, and an empty registry costs one
   volatile read.

5. `Model.withoutEvents(Supplier)` and `withoutEvents(Runnable)` mute listeners and observers of
   every model class for the current thread only. Calls nest and the mute ends when the block
   throws. The model's own hooks still run, because they carry the model's invariants (a pivot's
   key guard is one). The mute does not follow work handed to another thread.

6. `saveQuietly`, `updateQuietly`, `deleteQuietly`, `touchQuietly`, `replicateQuietly`,
   `restoreQuietly` and `forceDeleteQuietly` run the single write inside `withoutEvents`.

7. The listener list of a model is found from the entry the model was loaded or built through,
   then from the registered entry of its class. A class reachable by neither fires only its own
   hooks.

8. `replicating` fires on the copy, after its attributes are set. `softDeleted` (Laravel's
   `trashed`, renamed because `trashed()` is the state getter) fires even when the UPDATE matched
   no row.

9. A write a listener makes joins the surrounding `saveOrFail` transaction only when it goes
   through the saving model's `getQueryFactory()`, which is the transaction's factory inside the
   call. Nothing is bound to the thread.

## Consequences

- Listeners are type-safe and survive R8. A listener registered on a class does not fire for a
  different registered class, including a subclass registered on its own.
- A listener that saves the model it was called for runs the whole save again and recurses unless
  it guards itself, as in Laravel.
- A listener that writes through another connection or the default factory is not part of the
  saving transaction and stays committed if the transaction rolls back.
- Builder mass writes (`update`, `delete` on a query) load no model and fire nothing.
- The mute covers listeners only. Code that must silence an own hook overrides the hook.
- Listeners live in a static registry, so a test that registers one calls `forget()` afterwards.

## Alternatives rejected

- A global dispatcher keyed by strings: needs names that R8 renames, and cannot be type checked.
- Laravel's `until` rule: a stray `true` silences later listeners, which is a trap with no use.
- A process-wide `withoutEvents`: one request would mute every other thread's listeners.
- Muting own hooks under `withoutEvents`: it would let a quiet write skip a model's invariants.
- Tying listeners to the thread's transaction: it needs thread-bound transaction state that the
  library does not have.
