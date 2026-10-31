# 0004. Strict transactions by default

## Status

Accepted

## Context

With a shared root connection (ADR 0001), code inside a transaction callback can still call the
root by mistake, for example a helper that captured the root earlier. That call borrows a second
connection from the pool. Its writes are outside the transaction and survive a rollback, and a
pool with one free connection deadlocks because the transaction holds the other. Both failures
are silent until production load.

## Decision

The builder setting `strictTransactions` defaults to true. The root keeps a map of the threads
currently inside a transaction it opened, with a nesting count per thread, and refuses any call
from such a thread with `IllegalStateException` naming the fix: use the connection passed to the
callback, or the scope. The map holds its threads weakly so dead threads never accumulate, and its
lock covers only small map operations, never a database call. A scope closed on another thread
still frees the thread that opened it. Other threads use the root as usual.

Code that writes outside the transaction on purpose, such as an audit log, builds its connection
with `strictTransactions(false)`.

## Consequences

- The mistake fails at the first call, in development, with a message that says what to do.
- The root carries one piece of mutable state, kept per connection and safe across threads; there
  is still no `ThreadLocal` and no static state.
- A deliberate write outside the transaction needs a second connection built with strict mode off.

## Alternatives rejected

- No check: partial writes and pool deadlocks show up only under load.
- Routing root calls into the current transaction through a thread binding: hidden state that
  breaks on async work, and it changes what the root means.
- Strict mode off by default: the safe behaviour would depend on every caller knowing to switch it
  on.
