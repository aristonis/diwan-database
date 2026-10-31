# 0005. Binding values hidden from errors

## Status

Accepted

## Context

Laravel's `QueryException` message includes the SQL with its binding values filled in. Binding
values are often personal data: email addresses, names, tokens. Exception messages end up in
logs, error trackers and support tickets, where that data should not be. Databases also echo the
offending value in their own error text, for example on a duplicate key.

## Decision

1. A `QueryException` message is `SQL: <sql> (Connection: <name>, SQL state <state>)`. Binding
   values are appended only when the connection was built with `showBindingsInErrors(true)`.
2. The exception always carries the values in `getBindings()`, for code that handles the error.
3. The database's own error text stays in the cause, never in the message. A driver builds its
   `DriverException` message from a fixed description, the SQL state and the vendor code only.
4. The serialized form leaves the values out when they were hidden. The cause is still serialized.
5. Message parts are escaped (backslash, line breaks, control characters, Unicode line separators)
   and cut to a fixed length, so a message stays one readable log line that a value cannot forge.
6. `LoggedQuery.toString()` shows the number of bindings, not their values.

## Consequences

- Logs carry no binding values unless a developer switches them on.
- Debugging a failed statement needs either the switch or `getBindings()`.
- A cause chain that is logged in full can still contain the database's own text; that is the
  driver's and the logger's concern, and the JDBC adapter keeps it out of its own messages.

## Alternatives rejected

- Values shown by default, as in Laravel: personal data in every log by default.
- Values never kept on the exception: code that handles the error loses what it needs.
