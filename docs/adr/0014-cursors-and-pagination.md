# 0014. Cursors, chunking and pagination

## Status

Accepted

## Context

`get()` loads every row into memory. Laravel offers `chunk`, `chunkById`, `lazy`, `cursor`,
`paginate`, `simplePaginate` and `cursorPaginate` for large reads. In PHP a cursor is a generator
over a PDO statement that the garbage collector closes. In Java a cursor holds a statement, and on
the root connection a pooled session, until something closes it; Android's API 28 floor has no
`Cleaner`. Each driver streams differently: the PostgreSQL JDBC driver honors a fetch size only
with autocommit off, xerial SQLite steps lazily on its own, and Android's `SQLiteCursor` reads
through a window that it refills by running the query again. Android transactions belong to the
thread, not to a cursor.

## Decision

1. `cursor()` returns a `RowCursor`: an `AutoCloseable` iterator that iterates once, closes itself
   when exhausted, and must otherwise be closed, best with try-with-resources.
2. Ownership. On the root connection the cursor opens a dedicated session and gives it back after
   the cursor closes. In a session or transaction the cursor borrows the held session and never
   closes it.
3. One open cursor per held session. While it is open, any other statement, a second cursor, a
   savepoint, commit or rollback on that session is refused with `IllegalStateException`. The
   guard lives on the shared transaction state, so a cursor opened in a parent scope also blocks
   a child scope. Ending a scope closes the cursor it opened, and only that one, before the
   commit or rollback. Exhaustion releases the guard.
4. Close order: result set, statement, then commit or rollback if the cursor turned autocommit
   off, then autocommit back on, then the session release. Each step runs even if an earlier one
   failed; later failures are added as suppressed.
5. JDBC cursors are forward-only and read-only with a fetch size of 500. A driver that streams
   only inside a transaction declares `DriverCapability.STREAMS_IN_TRANSACTION` (PostgreSQL).
   Outside a transaction the cursor turns autocommit off, commits on close (rolls back after a
   failure) and restores autocommit. Inside a user or external transaction it reads in it and
   leaves it alone.
6. xerial SQLite needs no setup: the statement steps one row at a time until close or exhaustion.
7. Android opens no transaction for a cursor. A cursor transaction would cover every write the
   loop makes on the same thread and roll them back with the cursor. The `SQLiteCursor` stays open
   on its own; inside a user transaction it reads in it. The cursor is used only from the thread
   that opened it.
8. `chunk`, `each`, `chunkMap` and `lazy` run each page on a copy of the builder, so the caller's
   builder is unchanged (Laravel changes it). They need an order, refused with Laravel's message
   otherwise; the by-id forms add their own. `lazy` loads one page per step and holds no
   connection between pages.
9. Pages are plain final classes, `LengthAwarePage`, `SimplePage` and `CursorPage`, with no URL or
   HTML. A cursor token is base64url text of typed key and value lines: string, `Long`,
   `BigDecimal`, `Double`, boolean, null, `Instant`, `LocalDateTime`, `OffsetDateTime` and `UUID`.
   Decoding is strict and refuses any other form. Tokens are not signed.
10. The pagination count of a `distinct` query counts the distinct rows by wrapping the query.
    Laravel counts every row, which gives a wrong total.

## Consequences

- Large reads stay flat in memory on all three databases.
- A root cursor that is never closed and never exhausted keeps its pooled connection, and on
  PostgreSQL an open server transaction that blocks vacuum. There is no backstop yet:
  `java.lang.ref.Cleaner` needs Android API 33 and the floor is 28. The README tells callers to
  use try-with-resources; a backstop is to be decided before 0.1.
- One cursor per held session is stricter than some drivers allow.
- A forged token is accepted, but its values are bound as parameters, so it can select other rows
  the query already allows and cannot inject SQL.
- On Android a window refill can skip or repeat rows that changed during the loop; `chunkById` or
  `lazyById` is the safe choice for large or changing tables.

## Alternatives rejected

- A cursor that loads all rows: defeats the purpose.
- A transaction around the Android cursor: rolls back writes made inside the loop.
- A `Cleaner` to close forgotten cursors: needs API 33.
- Several open cursors per held session: statement interleaving differs per driver, and a commit
  under an open PostgreSQL cursor ends it.
- A new `DriverInfo` field for PostgreSQL streaming: a capability already expresses it.
- Signed tokens: need a key the library would have to manage; bound values already block injection.
- JSON tokens: need a dependency.
- Records for the page types: not available on Android 9.
