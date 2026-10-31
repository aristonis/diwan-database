# 0003. Java idioms over Laravel return shapes

## Status

Accepted

## Context

Photisma ports Laravel's database layer. Laravel's API was shaped by PHP: `null` for a missing
row, `stdClass` rows, a transaction ended by separate begin and commit calls, and exceptions that
PHP does not force callers to declare. Copying those shapes literally would give Java callers
nullable returns, untyped rows and manual cleanup. Dropping Laravel's names would make the port
harder to learn for people who know Laravel.

## Decision

Keep Laravel's method names where they read well in Java (`statement`, `select`, `selectOne`,
`scalar`, `insert`, `update`, `delete`, `affectingStatement`, `unprepared`, `transaction`,
`beginTransaction`, `pretend`, `transactionLevel`, `getName`, `getTablePrefix`) and use Java's own
idioms for the shapes:

1. `selectOne` and `scalar` return `Optional`; empty means no row, or SQL `NULL` for `scalar`.
2. Rows are unmodifiable `Map<String, Object>` in select order inside an unmodifiable list.
3. `beginTransaction()` returns a `TransactionScope` that is `AutoCloseable`, so
   try-with-resources rolls back whatever was not committed.
4. Every exception is unchecked (`QueryException` and its subtypes), as in Laravel, so callbacks
   and lambdas stay plain.
5. Bindings are checked against `java.time` and the standard number types before any SQL runs.

## Consequences

- Absence is in the type; callers cannot forget a null check.
- A forgotten commit in a scope is rolled back at the end of the `try` block.
- Laravel users find the same names, with Java returns where the meaning would otherwise be
  hidden.

## Alternatives rejected

- Laravel's return shapes as they are: `null` returns and untyped rows hide the contract.
- Checked `SQLException` in signatures: every lambda passed to `transaction` would need a wrapper.
- A new Java naming scheme: harder to move from Laravel, with no gain in clarity.
