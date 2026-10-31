# Connection requirements

What the connection layer must do, observed from outside. The structure that meets these
requirements is in [connection-design.md](../architecture/connection-design.md); the detailed
contract of each type is its Javadoc.

## Index

1. Problem
2. Goal
3. Actors
4. Functional requirements
5. Non-functional requirements
6. Constraints
7. Scope and non-goals
8. Acceptance criteria

## 1. Problem

Every part of Diwan Database that talks to a database (schema builder, migration repository, migrator,
query builder) sends SQL through one object. Laravel's `Connection` does this for PHP over one PDO
handle in a single-threaded request. Java callers bring a connection pool, share objects across
threads, run on Android without JDBC, and often run inside Spring transactions.

## 2. Goal

A `Connection` with Laravel's method names that runs SQL, manages nested transactions and records
SQL in pretend mode, safely on a shared pool, inside a transaction it does not own, and on Java 17
desktop and server runtimes, Android 9 and later, and Spring.

## 3. Actors

| Actor | Role |
|---|---|
| Application developer | Calls the connection from desktop, server or Android code |
| Diwan Database internals | Schema builder, migration repository and migrator call it |
| Outer transaction owner | Spring or the application, owning a transaction Diwan Database joins |
| Driver adapter author | Implements the driver interface for a platform without JDBC |

## 4. Functional requirements

### Creating a connection

FR1. A connection is created from a JDBC data source and a name.

FR2. A connection can be created from a connection provider (acquire and release a physical
connection) instead of a data source.

FR3. A connection can be created over any driver implementation, so a driver that is not JDBC can
be plugged in.

FR4. The connection reports its name and its table prefix (empty by default). The prefix holds
only ASCII letters, digits and underscores, is kept for later grammars and is never applied to
raw SQL.

### Running SQL

FR5. `statement` runs any statement and reports that it ran.

FR6. `affectingStatement`, `update` and `delete` return the number of affected rows.

FR7. `insert` runs an insert and reports that it ran.

FR8. `select` returns every row in result order; each row keeps the selected column order, uses
the labels exactly as the database reports them, and cannot be modified.

FR9. `selectOne` returns the first row as an optional value, empty when there is no row.

FR10. `scalar` returns the only column of the first row as an optional value, empty when there is
no row or the value is SQL `NULL`, and fails when the row has more than one column.

FR11. `unprepared` runs SQL without bindings.

FR12. Bindings are positional and accept null, Boolean, Byte, Short, Integer, Long, Float, Double,
BigDecimal, BigInteger, String, byte[], LocalDate, LocalTime, LocalDateTime, OffsetDateTime and
Instant. Any other type, including a subclass of a listed type, is refused before anything is sent
to the database.

FR13. Every SQL method accepts its bindings as varargs or as a list. A null bindings array or list
is refused.

FR14. A read whose result has two columns with the same label fails with an error naming the
label.

FR15. Large-object, XML and array values come back as text, bytes and lists, read before the
physical connection is given back; no `java.sql` type reaches the caller.

### Transactions

FR16. `transaction(callback)` runs the callback on one physical connection, commits when it
returns and gives back its result. When it throws, the transaction is rolled back and the same
exception reaches the caller.

FR17. A transaction inside a transaction uses a savepoint: a failure inside rolls back only the
inner work and rethrows; the outer callback decides what happens next.

FR18. When the database may have lost the whole transaction (a concurrency failure, or a failed
rollback to a savepoint), the transaction becomes rollback-only: later statements and the commit
fail, and the outermost level rolls back.

FR19. `transaction(callback, attempts)` runs the whole callback again, up to `attempts` times, only
when a statement on the transaction's own connection raised a concurrency failure (deadlock,
serialization failure, busy or locked database), also when the callback caught it. It never
retries a failed commit, a failure from another connection, an inner level, or a transaction
inside an external one. `attempts` below 1 is refused.

FR20. `beginTransaction()` returns a transaction scope for code that cannot use a callback:
`commit()` commits, closing without a successful commit rolls back.

FR21. A transaction scope: after a failed commit, closing it rolls back and gives the connection
back; closing it twice does nothing; using it after it ended fails; closing an outer scope while an
inner one is open ends the inner one first.

FR22. `transactionLevel()` reports the current nesting depth inside a transaction, 0 outside.

FR23. Savepoint names are generated by Diwan Database, unique within a physical connection, and start
with `diwan_`. Savepoints are released after the inner work succeeds on databases that support
release.

FR24. A connection handed to a transaction callback, a transaction scope and a pretend recorder
refuse any use after they end.

### Connection handling

FR25. Outside a transaction each call borrows a physical connection and gives it back before
returning.

FR26. On every exit from a transaction, success or failure, Diwan Database ends the transaction it
started, restores the connection's auto-commit setting, and gives the connection back.

FR27. A connection that a pool hands out with auto-commit off still commits work done outside a
transaction, and goes back to the pool with auto-commit off.

FR28. A failure while restoring or giving back a connection after the work succeeded never turns
the result into a failure. It goes to the connection's cleanup-failure handler (by default a
warning through `java.util.logging`), and the result is returned.

FR29. With strict transactions on (the default), a call on the root connection from a thread
inside a transaction that root opened fails at once. Strict mode can be switched off.

### Transactions Diwan Database does not own

FR30. When the provider reports that a connection is already inside a transaction owned by someone
else, Diwan Database never begins, commits, fully rolls back, closes or changes auto-commit on it. Its
transaction works through a savepoint and rethrows failures to the outer owner.

### Pretend

FR31. `pretend(callback)` runs the callback against a recorder and returns the statements it would
have run, each with its SQL and bindings; no physical connection is borrowed.

FR32. Inside pretend, reads return no rows, row counts are 0, `statement`, `insert` and
`unprepared` report that they ran, and `selectOne` and `scalar` return empty.

### Errors

FR33. Any database failure, including a failure to obtain a connection, is raised as an unchecked
`QueryException` carrying the connection name, the SQL, the bindings, the SQL state, the vendor
code, whether it is a concurrency failure, and the original cause.

FR34. A unique-key violation is raised as `UniqueConstraintViolationException`, a subtype of
`QueryException`.

FR35. The `QueryException` message shows the SQL, the connection name, the SQL state and the vendor
code. The database's own reason is added with the values. Binding
values appear in it only when showing them is switched on; by default they are hidden. The
database's own error text never appears in the message.

FR36. Error messages are one line: line breaks and control characters are escaped, and long SQL and
values are shortened.

FR37. The serialized form of a `QueryException` leaves out hidden binding values.

### Driver interface

FR38. A driver reports its database name, version and capabilities (savepoint release,
transactional schema changes).

FR39. A driver classifies its own failures as unique violations or concurrency failures. The JDBC
driver reads vendor codes only for the database that owns them and never classifies a lost
connection as a concurrency failure.

## Android

- `AndroidSqliteDriver.of(SQLiteDatabase)` or `of(SQLiteOpenHelper)` plugs Android's own SQLite
  into `Connection.builder().driver(...)` on Android 9 (API 28) and later.
- Sessions share the database and never close it; transactions use Android's transaction calls and
  savepoints; discarding a session rolls back a transaction it began.
- Values and failure kinds match the JDBC driver on SQLite.

## 5. Non-functional requirements

NFR1. The test suite passes on Java 17, 21 and 25.

NFR2. The core compiles to Java 17 bytecode and uses only JDK methods available on Android API 28;
the build fails otherwise.

NFR3. The core has no runtime dependency outside the JDK.

NFR4. No connection leak: after any operation, successful or failed, no borrowed connection is
left unreturned.

NFR5. One connection is safe to share between threads: 8 threads running 200 statements each
finish with correct results.

NFR6. No lock is held around a database call in the core.

NFR7. Line coverage of the core is 80% or more, measured by the build.

NFR8. The public API has no records and no sealed types.

NFR9. Every public type and member has Javadoc, and Javadoc builds clean under `-Xdoclint:all`
with warnings as errors.

NFR10. Every package has a `package-info.java`.

## 6. Constraints

C1. Java 17 language level and bytecode; one plain JAR without a multi-release overlay.

C2. Idiomatic Java: `Optional`, `java.time`, functional interfaces, try-with-resources, unchecked
exceptions. Laravel's method names are kept where they read well in Java; PHP-shaped returns are
not copied.

C3. No `ThreadLocal` and no static mutable state. Explicit per-connection state that is safe
across threads is allowed.

C4. The only test dependency is `org.xerial:sqlite-jdbc`. Build-only tools: Animal Sniffer with
the Android 28 signature, and JaCoCo.

C5. Package `org.aristonis.diwan.database`, module `core`, artifact
`org.aristonis:diwan-database`.

## 7. Scope and non-goals

In scope: FR1 to FR39, the driver interface, and its JDBC adapter.

Non-goals:

- Query builder, `table()`, `query()`, query and schema grammars.
- Per-database driver modules (SQLite, PostgreSQL, MySQL); SQLite is only the test database.
- The Android `SQLiteDatabase` adapter and a Spring module; the interfaces make them possible.
- Read and write connection split, sticky writes, reconnecting after a lost connection.
- Query log, events, slow-query handlers.
- Cursors, multiple result sets, named bindings (`:name`).
- A connection factory or database manager for several named connections.
- Connection pooling; the caller brings the pool.
- Offset-aware timestamp reads; the JDBC adapter reads timestamps in the JVM's time zone.

## 8. Acceptance criteria

| AC | Checks | Requirement |
|---|---|---|
| AC1 | A table created with `statement`, filled with `insert` and read with `select` gives the rows in insert order with columns in select order | FR1, FR5, FR7, FR8 |
| AC2 | `update` and `delete` return the number of changed rows | FR6 |
| AC3 | `selectOne` and `scalar` give the first row and value; an empty result gives empty; two columns in `scalar` fail | FR9, FR10 |
| AC4 | Each supported binding type round-trips through an insert and a select; an unsupported type fails before any SQL runs | FR12 |
| AC5 | A transaction whose callback returns is committed; one that throws leaves no rows and the same exception reaches the caller | FR16 |
| AC6 | An inner transaction that throws undoes only the inner rows; the outer commit keeps the outer rows | FR17 |
| AC7 | A callback that fails with a concurrency failure and then succeeds runs again; it stops after the last attempt; other failures and failed commits are not retried | FR19 |
| AC8 | A transaction scope closed without commit leaves no rows; one committed keeps them | FR20 |
| AC9 | `transactionLevel` reads 1 inside one transaction, 2 inside a nested one, 0 outside | FR22 |
| AC10 | After every scenario, success or failure, no connection is left borrowed and auto-commit is restored | FR25, FR26, NFR4 |
| AC11 | With a provider that reports an outer transaction, Diwan Database's transaction commits nothing, never closes the connection, and a failure undoes only Diwan Database's part | FR30 |
| AC12 | `pretend` returns the SQL and bindings of each statement and borrows no connection | FR31, FR32 |
| AC13 | A failing statement raises `QueryException` with name, SQL, bindings, SQL state and cause; a duplicate key raises `UniqueConstraintViolationException` | FR33, FR34 |
| AC14 | With default settings an error message shows no values and no database text; with showing on it shows the values | FR35 |
| AC15 | A connection built over a fake driver runs statements through it | FR3 |
| AC16 | The build passes on JDK 17, 21 and 25 and fails on a JDK method missing from Android 28 | NFR1, NFR2 |
| AC17 | 8 threads x 200 statements on one connection give correct results | NFR5 |
| AC18 | The coverage report shows 80% or more lines in core | NFR7 |
| AC19 | Name and table prefix read back as configured; an unsafe prefix is refused | FR4 |
| AC20 | Javadoc passes with `-Xdoclint:all` and warnings as errors; every package has `package-info.java` | NFR9, NFR10 |
| AC21 | A driver that loses the transaction on a deadlock: later statements and the commit fail and nothing is kept | FR18 |
| AC22 | A concurrency failure inside an external transaction is not retried | FR19, FR30 |
| AC23 | Strict mode: a root call inside its own transaction fails; with strict off it runs | FR29 |
| AC24 | Savepoint names are unique within a session and released on success where supported | FR23 |
| AC25 | A large-object value comes back as text or bytes after the connection was returned | FR15 |
| AC26 | Two columns with the same label fail with the label in the message | FR14 |
| AC27 | List bindings work like varargs; a null bindings array is refused | FR13 |
| AC28 | Scope rules: failed commit then close, double close, use after end, outer closed before inner | FR21, FR24 |
| AC29 | The JDBC driver reports database name, version and capabilities | FR38 |
| AC30 | A provider whose release fails after commit: the result is returned and the failure goes to the handler | FR28 |
| AC31 | A pool that hands out auto-commit off: work outside a transaction is kept and the connection goes back with auto-commit off | FR27 |
| AC32 | Messages escape line breaks and control characters; hidden values are absent from the serialized form | FR36, FR37 |
| AC33 | Vendor codes are read per database; a lost connection is never a concurrency failure | FR39 |
