# 0028. One of many through aggregate subqueries

## Status

Accepted

## Context

`latestOfMany`, `oldestOfMany` and `ofMany` turn a has-one style relation into "the one related row
per parent chosen by a rule". Laravel joins the related table to a chain of `max`/`min` subqueries
grouped by the parent key, registered lazily so later constraints still apply. Window functions
(`row_number() over (...)`) would be simpler, but Android API 28 ships SQLite 3.22, which has none.

## Decision

1. Keep Laravel's aggregate subquery chain. The primary key is always the last column, so exactly
   one row per parent is picked.
2. Build the join at query time, after every clause the user added: after the replayed lazy
   clauses, after an eager constraint, after the existence wheres. Diwan relations already add
   their constraints lazily, so no `beforeQuery` hook is needed in the query builder. Removed
   global scopes such as `withTrashed()` therefore reach the subqueries.
3. On lazy and eager reads, the innermost subquery is filtered by the loaded parents' keys, so it
   reads only their rows. Existence queries keep Laravel's correlated shape.
4. With one-of-many set, an eager chunk binds each key twice, so the chunk budget halves.
5. The key tie-break follows the first column's aggregate, so `oldestOfMany` and any column list
   that starts with `min` return the lowest key among equal rows (Laravel always uses `max`).
6. `hasMany(...).one()` and `hasManyThrough(...).one()` are the entry points from many relations.

## Consequences

- `has`, `whereHas` and `withCount` on a one-of-many relation group the whole related table; an
  index on (foreign key, column, primary key) keeps that fast.
- The relation name is explicit (`ofManyAs`) or comes from the model's relation map; Laravel
  guesses it from the call stack.
