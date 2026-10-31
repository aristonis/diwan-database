# 0001. Root connection with scoped transactions

## Status

Accepted

## Context

Laravel's `Connection` is one long-lived object that holds one PDO handle and a transaction
counter. That fits a single-threaded PHP request. Java callers share objects across threads, bring
a connection pool, and run async work on pooled threads. A Java port of that object would either
hold a pooled connection forever or mix the transactions of different threads on one counter.

## Decision

The connection a caller builds is a root that holds the driver and its settings and no session. It
is safe to share between threads. Each statement on the root borrows a session for that one call.
`transaction(callback)` and `beginTransaction()` open a session of their own and hand the caller a
separate connection bound to that session: the callback's argument, or a `TransactionScope`. Work
inside the transaction goes through that connection, and nested transactions use savepoints on
it. Pretend mode works the same way with a recording connection.

Later layers take the connection they work on per call or per unit of work. With strict
transactions on (the default), the root refuses a call from a thread that is inside a transaction
it opened, because that call would run outside the transaction.

## Consequences

- A pooled connection is held only for one call or one transaction.
- No `ThreadLocal` and no hidden current transaction; the transaction is the object in hand.
- Code must pass the transaction's connection on. Strict mode turns the common mistake into an
  immediate error instead of partial writes or a pool deadlock.
- Callback connections and scopes refuse use after they end, so a leaked reference fails loudly.

## Alternatives rejected

- One long-lived object as in Laravel: unsafe across threads and holds a connection forever.
- A handle the caller opens and closes for every unit of work: safe, but every call site carries
  open and close code.
- A connection bound to the current thread: hidden state that leaks across async work and pooled
  threads.
