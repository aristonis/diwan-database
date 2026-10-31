# Connection: user stories

## Actors

| Actor | Who they are | What matters to them |
|---|---|---|
| Application developer | Writes a desktop, server or Android app in Java | Laravel-like calls, no leaked connections, clear errors |
| Diwan Database internals | The schema builder and migrator | One reliable place to run SQL, and a dry run |
| Outer transaction owner | A Spring application or the developer's own code | Diwan Database never commits or closes what it did not open |
| Driver adapter author | Someone adding a platform without JDBC | A small interface to implement |

## Stories

### Run SQL the Laravel way

As an application developer, I want to run statements and read rows with the method names Laravel
uses, so that I can move from Laravel to Java without relearning the database layer.

Works when: a table can be created, filled, read, changed and emptied with `statement`, `insert`,
`select`, `update` and `delete`, and a missing row or value comes back as an empty `Optional`.

### Group work so it all happens or none of it does

As an application developer, I want to wrap several statements in a transaction, also inside
another one, so that a failure never leaves half-written data.

Works when: a failure anywhere inside undoes exactly the part it belongs to, and the database
keeps the rest only when the outer work finishes. A scope used with try-with-resources rolls back
whatever was not committed.

### Survive a deadlock without writing retry code

As an application developer, I want a transaction to run again a set number of times when the
database reports a deadlock, so that busy moments do not fail requests.

Works when: a deadlocked transaction succeeds on a later attempt, stops after the last attempt,
and is never retried after a failed commit.

### Share one connection in a busy app

As an application developer, I want to use one connection from many threads with a pool, so that
the app stays fast and never runs out of connections because Diwan Database held one.

Works when: many threads work at once with correct results and every borrowed connection goes
back, also when the pool hands out connections with auto-commit off.

### Work inside someone else's transaction

As an outer transaction owner, I want Diwan Database to join my transaction instead of committing or
closing it, so that its writes succeed or fail with mine.

Works when: inside an outer transaction, Diwan Database's work is kept only if the outer owner commits,
and Diwan Database never ends that transaction.

### See the SQL without running it

As Diwan Database internals (a future `migrate --pretend`), I want to collect the SQL a piece of work
would run without touching the database, so that a developer can review a migration before it
changes anything.

Works when: a dry run returns every statement with its values and no connection is borrowed.

### Understand what failed

As an application developer, I want a failed query to tell me which SQL failed and to recognise a
duplicate key, without personal data leaking into logs, so that I can fix the problem or show a
friendly message.

Works when: an error shows the SQL, the connection and the SQL state, carries the values and the
database's own reason for code that handles it, shows the values in the message only when
switched on, and a duplicate key can be caught on its own.

### Run Diwan Database where JDBC does not exist

As a driver adapter author, I want a small driver interface under the connection, so that Diwan Database
can run on Android's built-in SQLite.

Works when: a connection works over a driver that is not JDBC, and the core builds against Android
9's Java library.

## Stories turned down

| Story | Reason |
|---|---|
| Use several named connections from one configuration | A database manager is a later layer |
| Log every query with its time | Query events and a query log are a later layer |
| Bind values by name (`:name`) | Positional bindings cover the migrator |
