# One of many

A has-one style relation that picks one row per parent by a rule.

```java
public final class User extends Model {
    public HasOne<Order> latestOrder() {
        return hasOne(Order.class).latestOfMany();
    }

    public HasOne<Order> oldestOrder() {
        return hasOne(Order.class).oldestOfMany("created_at");
    }

    public HasOne<Order> largestPaidOrder() {
        return hasOne(Order.class).ofMany("total", "max", q -> q.where("status", "paid"));
    }

    public HasOne<Order> lastOrder() {
        return hasMany(Order.class).one().latestOfMany();
    }
}
```

- Available on `HasOne`, `MorphOne` and `HasOneThrough`; `hasMany(...).one()`,
  `hasManyThrough(...).one()` and `morphMany(...).one()` turn a many relation into one.
- `latestOfMany(columns...)` takes the largest values, `oldestOfMany(columns...)` the smallest.
  With no column the primary key is used.
- `ofMany(column, aggregate)` takes `min` or `max` (any case); anything else throws
  `IllegalArgumentException`. `ofMany(Map)` takes several columns in order.
- The optional constraint narrows the rows the choice is made from.
- Ties are broken by the primary key, with the aggregate of the first column: `oldestOfMany`
  returns the lowest key among equal rows, `latestOfMany` the highest.
- The model's global scopes and the `ofMany` constraint narrow the rows the choice is made from;
  `withTrashed()` or `withoutGlobalScope` added later, lazily, in an eager constraint or in a
  `whereHas` closure, lift a scope there too. The relation's own wheres filter the picked row
  afterwards, so `latestOrder().where("status", "paid")` is null when the latest order is unpaid;
  put the condition in the constraint to pick the latest paid order. A scope added with
  `withGlobalScope` on the builder does not reach the choice, as in Laravel.
- Lazy and eager reads, `with` with a column list, `has`, `whereHas`, `withCount`, the other
  aggregates and `withDefault` all work.
- The join alias is `ofManyAs(name)`, else the relation's name, else the related table; an alias
  equal to a table in the query gets `_of_many` appended.

## SQL

```sql
select "orders".* from "orders"
inner join (
  select max("orders"."id") as "id_aggregate", "orders"."user_id"
  from "orders" where "orders"."user_id" = ?
  group by "orders"."user_id"
) as "latest_order"
  on "latest_order"."id_aggregate" = "orders"."id"
 and "latest_order"."user_id" = "orders"."user_id"
where "orders"."user_id" = ? and "orders"."user_id" is not null
```

Add an index on (foreign key, type for a morph relation, the columns, primary key): `has`, `whereHas` and `withCount` group the
whole related table.

## Differences from Laravel

| Laravel | Diwan |
|---|---|
| The key always breaks ties with `max`, so `oldestOfMany` and any column list that starts with `min` return the highest key among equal rows | The key takes the first column's aggregate: the lowest key for those |
| The subqueries read the whole related table | Lazy and eager reads filter them by the loaded parents |
| The relation name is guessed from the call stack | `ofManyAs(name)` or the relation map |
| `ofMany('col', fn)` with a string column fails with a TypeError | Typed overloads |

See [ADR 0028](../../adr/0028-one-of-many.md).
