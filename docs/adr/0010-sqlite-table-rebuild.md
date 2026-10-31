# 0010. SQLite table rebuild for key changes

## Status

Accepted

## Context

SQLite cannot add or drop a primary or foreign key on an existing table, and before 3.25 and 3.35
it cannot rename or drop a column; Android 9 ships 3.22. Laravel rebuilds the table: a new table,
the rows copied, the old one dropped, the new one renamed. Dropping a table that other tables point
at applies their delete actions while enforcement is on, and `PRAGMA foreign_keys` is ignored inside
a transaction, which is where migrations run.

## Decision

1. The SQLite grammar reads the table once per blueprint and applies each command to that picture
   in order, so a rebuild carries columns added or renamed earlier in the same call. Columns,
   primary key, unique constraints, foreign keys, indexes and the auto-increment counter are
   carried over.
2. `Connection.session` holds one physical connection; `Schema.withoutForeignKeyConstraints` uses it
   to switch enforcement off before the transaction and back on after. A session that fails is
   dropped, never returned to a pool, so a setting it could not restore does not leak.
3. The schema builder runs a rebuild outside a transaction through that switch.
4. Inside a transaction with enforcement on, a table other tables point at is not rebuilt:
   `ForeignKeysOffRequiredException`. The migrator catches it, and runs that one migration again
   with enforcement off and a whole-database key check before commit; every other migration runs
   with enforcement on, so cascades fire as in Laravel.
5. After a rebuild, `pragma_foreign_key_check` runs on the table and the tables that point at it;
   bad rows undo the change and raise `ForeignKeyCheckException` without row values.
6. Tables with features the rebuild cannot carry over (COLLATE, STRICT, WITHOUT ROWID, triggers,
   views, partial or expression indexes) are refused. CHECK constraints, named or not, on a column
   or the table, and generated columns are carried as the text the table was written with;
   generated columns are not copied, SQLite computes them again. Since that text is not rewritten,
   renaming or dropping a column it names is refused on the rebuild path, and a column SQLite
   renamed in place earlier in the same call is refused too.
7. Column rename and drop run in place where SQLite supports them. On older versions a column
   another table's key references is refused, since the rebuild would leave that key on the old name.
8. `change()` always rebuilds, since SQLite cannot alter a column. The new definition takes the
   column's place in the picture and rows are copied from the old column; a row that breaks it
   fails the copy and the whole change rolls back.

## Consequences

- Key changes on filled SQLite tables keep every row in every table.
- Every `change()` copies the whole table, so changes to one table belong in one `Schema.table`
  call, where they share a single rebuild.
- A migration that rebuilds a referenced table runs twice: once with enforcement on until the
  rebuild is refused and rolled back, then with it off. Its database work is undone between the
  two; outside effects of `up` are not, so migrations should keep to the database.
- Inside such a migration, cascades do not fire; rows they would have removed fail the
  whole-database check instead.
- Discarding a session drops the physical connection only where the driver can abort it; on
  Android a single unpooled connection is the expected setup.
- A table made earlier in the same pretend run cannot be rebuilt in pretend, since pretend reads
  the live schema.
