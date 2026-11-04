# 0020. Relations: belongsToMany write side

## Status

Accepted

## Context

[ADR 0019](0019-relations-s3a.md) added the read side of `belongsToMany`. A Laravel developer also
expects to write through it: link and unlink related rows, sync a list, toggle, change the columns of
a joining row, save a related model through the relation, and keep `updated_at` of the owners in
step. A straight port of Laravel's `InteractsWithPivotTable` would copy several things that go wrong
on Diwan's constraints. Laravel replays the recorded pivot wheres flat, so
`wherePivot(a)->orWherePivot(b)` becomes `a or b and parent_id = ?` and a detach can reach another
parent's rows. With a custom pivot class, `updateExistingPivot` over several ids updates only the
first one. `toggle` returns its attached ids uncast. `sync` attaches new ids with one statement each.
The id lists and the insert rows are unbounded, and SQLite on Android API 28 binds at most 999
parameters. Saving a related model and then attaching it leaves an orphan row when the attach fails.
A joining table with no primary key cannot be saved or deleted by the model's own key.

## Decision

1. The write API lives on `BelongsToMany<R>` through three package-private layers, so a subclass
   still gets the full type: the joining-table writes, the sync and toggle logic, and the
   related-side writes. `SyncResult` is a public immutable value with `getAttached()`,
   `getDetached()`, `getUpdated()` and `isEmpty()`; each list is unmodifiable and holds related keys
   cast to the related key's type.

2. `attach`, `detach`, `updateExistingPivot`, `sync`, `syncWithoutDetaching`, `syncWithPivotValues`
   and `toggle` take one `Object` for the ids: a scalar, a model, a collection or array of ids and
   models, or a map from id to the attributes for that id. A model counts as the value of its related
   key, not its primary key. A null entry is refused with `IllegalArgumentException`. Each write has
   an overload with a `touch` flag. `find`, `findMany` and `findOrFail` accept the same models.

3. Attach writes one row per id: the parent key, the related key, the shared attributes (which win
   over the per-id ones), the values set with `withPivotValue`, and the timestamps. A timestamp
   column is written only when it is registered and not switched off. Detach with no argument
   removes every row of the parent. An empty id list sends no statement.

4. The recorded pivot wheres apply to `detach`, `updateExistingPivot` and the key read that `sync`
   and `toggle` make. They are replayed in declared order inside one nested group, followed by
   `and parent_key = ?`, so an `or` among them cannot widen the match to another parent's rows.
   `newPivotQuery()` returns that query and `newPivotStatement()` returns a bare one on the joining
   table.

5. A caller list never binds more than 900 values in one statement. Attach is cut into statements of
   `900 / column count` rows, and the id lists of detach, update and touch are cut into chunks of the
   grammar's eager key size less the bindings the statement adds itself. Counts are summed. A write
   that needs more than one statement runs them in one transaction, which is a savepoint inside a
   caller's transaction. The whole call applies or none of it does.

6. `sync` reads the current related keys with one plain query, detaches the ones not listed, attaches
   the new ones in one batched insert, and updates the existing ones that carry attributes. Laravel
   makes one attach call per new id; the result is the same with fewer statements. Keys are compared
   after normalising them, so `"1"` and `1` are the same key. The whole sync is one transaction and
   touches once at the end if anything changed. It reads and then writes without a lock: two racing
   syncs on a unique index fail cleanly and roll back.

7. `toggle` detaches the ids that are linked and attaches the others. It returns the same
   `SyncResult` as `sync`, with `updated` always empty and the attached ids cast to the related key's
   type. Laravel returns the attached ids uncast.

8. With a custom pivot class set by `using`, attach builds and saves one pivot per record, and detach
   loads the pivots for the ids and deletes each, so the model events fire. A plain pivot fires none.
   `updateExistingPivot` updates every pivot of the given ids and returns the count of those that
   changed; Laravel updates only the first. A pivot with no primary key saves and deletes by its two
   pivot keys, using the original values, so a key change updates the right row. The model gets one
   protected hook for the keys of the save and delete query, and the pivot overrides it. `increment`,
   `decrement`, `refresh` and `fresh` still refuse such a pivot, since they need a single key.

9. `save`, `saveMany`, `create`, `createMany`, `firstOrCreate`, `createOrFirst` and `updateOrCreate`
   save the related model and attach it in one transaction, with the model rebound to that
   transaction's connection. If the attach fails, the saved row rolls back; Laravel leaves it
   behind. `createMany` that fails on its second record rolls back the whole call. `findOrNew` finds
   within the parent's related rows and otherwise returns a new unlinked instance. `firstOrNew` and
   the lookup inside `firstOrCreate` search the whole related table, as Laravel does. A unique
   violation on the attach step of `firstOrCreate` and `createOrFirst` is absorbed inside a
   savepoint, and the existing row is linked instead.

10. `touch()` on the relation updates the updated column of the related rows in chunks, and skips when
    the related class keeps no timestamps, has no updated column or is inside `withoutTouching`. After
    a write that changed rows, the parent is touched when the related class lists the guessed inverse
    in `touches()`, which is the plural camel case of the parent's class name. The related rows are
    touched when the parent's `touches()` names this relation. A relation built by calling the
    model's method directly has no name, so the parent's `touches()` entries are matched by the join
    they name: the same table, both pivot keys and the related class. `saveMany` and `createMany`
    touch once at the end. A touch runs after the commit, so a touch that fails leaves the link
    change committed.

11. A relation is single use and single threaded, as for the other kinds. The listeners of a pivot
    class set by `using` run inside the transaction. The listeners of any other model run on that
    model's own connection, outside it.

## Consequences

- Writes use no reflection, annotations or keep rules, and run the same way on SQLite, PostgreSQL and
  Android.
- Unique indexes on the joining table are the caller's guard against two threads linking the same
  pair. A racing sync fails with the database's unique violation and leaves no partial rows.
- A differently named inverse relation, or an irregular plural of the parent's class name, does not
  match the guessed name, so the parent's touch does not fire. This is Laravel's behaviour too.
- The `restoreOrCreate` family, `incrementOrCreate`, the `*Quietly` variants and the `*OrFail` sync
  variants are not added. A caller wraps a write in `connection.transaction` for atomicity beyond one
  call.
- Many-to-many through relations and polymorphic many-to-many are later work.

## Alternatives rejected

- Replaying the pivot wheres flat, as Laravel does: an `or` could match another parent's rows and a
  detach would delete them. Refusing `or` on write calls would break a chain that reads fine.
- One attach per new id in `sync`, as Laravel does: more statements for the same rows.
- Updating only the first pivot of a custom class, as Laravel does: the other ids would silently stay
  unchanged.
- Returning the raw ids from `toggle`: the key type would differ from `sync` for the same relation.
- Unbounded insert rows and id lists: they break the 999 binding limit on Android.
- An explicit name for the inverse relation to touch: no Laravel counterpart, so a guessed name is
  kept and documented.
- Saving the related model first and attaching without a transaction, as Laravel does: a failed attach
  leaves an unlinked row.
- Moving the key choice for a key-less pivot into the pivot's own save code: the save query is built
  in the model, so a small protected hook there keeps one code path.
