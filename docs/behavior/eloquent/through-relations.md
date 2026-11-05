# Through relations

A through relation reaches far rows across an intermediate table. `hasManyThrough` returns every
far row, and `hasOneThrough` returns the first one. Both take Laravel's names and SQL shape, with
the differences listed at the end of this page.

```java
public final class Country extends Model {
    public static final ModelQuery<Country> query = ModelQuery.of(Country.class, Country::new);

    @Override
    protected RelationMap<?> relations() {
        return RelationMap.of(Country.class).add("posts", Country::posts);
    }

    public HasManyThrough<Post> posts() {
        return hasManyThrough(Post.class, User.class);   // posts of the users of this country
    }
}

ModelQuery<Country> countries = Country.query.on(SQLiteQuery.of(db));
ModelCollection<Country> all = countries.with("posts").get();   // two queries in all
ModelCollection<Post> posts = countries.findOrFail(1).relatedMany("posts");   // one query, then kept
Optional<Country> withPosts = countries.query()
        .whereHas("posts", q -> q.where("title", "like", "Java%"))
        .first();
```

`hasOneThrough(Owner.class, Car.class)` declares the same shape for a single far row, such as the
owner of a mechanic's car. Its `withDefault()`, `withDefault(Map)` and `withDefault(BiConsumer)`
overloads supply a default model when no row is found.

## Keys

The shorter declarations fill in Laravel's defaults. For `Country` through `User` to `Post`:

| Key | Default | Meaning here |
|---|---|---|
| first key | `country_id` | the through table's column that holds this model's key |
| second key | `user_id` | the related table's column that holds the through model's key |
| local key | `id` | this model's key the first key points at |
| second local key | `id` | the through table's key the second key points at |

Each of the five overloads takes these keys in the order above, after the related and through
classes. A null key is refused. The through model's connection is the parent's connection.

## What each method does

| You want to | Use |
|---|---|
| Read the relation for one model | `related` (`hasOneThrough`) or `relatedMany` (`hasManyThrough`) |
| Load it for many models | `with`, `load`, `loadMissing`, `loadCount` |
| Filter by it | `has`, `doesntHave`, `whereHas`, `orHas`, `withCount`, `withExists`, `withSum`, `withMax` |
| Read rows through it | `get`, `first`, `firstOrFail`, `firstOr`, `firstWhere`, `sole`, `find`, `findMany`, `findOrFail`, `findOr`, `findSole` |
| Walk the rows | `chunk`, `chunkById`, `each`, `lazy`, `lazyById`, `cursor` |
| Page the rows | `paginate`, `simplePaginate`, `cursorPaginate` |
| Create a row through it | `findOrNew`, `firstOrNew`, `firstOrCreate`, `createOrFirst`, `updateOrCreate` |
| Limit the rows | `limit`, `take` |

Every read runs on a copy of the relation's query. Calling `first()` or `paginate()` leaves the
relation's own query as it was, so the next call on the same relation starts from the same
constraint.

A lazy read or one eager load per relation instance is the intended use. A relation instance is
single use, as for the other relation kinds; `Model.relation(name)` returns a fresh one each time.

## Reading the through key

A through relation needs the intermediate key to match eager results to their parents. The key
is read from each row and removed from the model's attributes, so it does not appear in
`toArray()` and does not make the model dirty. A model read through the relation is saved like
any other row.

## Eager loading

`with("posts")` runs one query per chunk of parent keys. The chunk size keeps the statement under
the database's parameter limit, and integer keys are written into the SQL. A constraint that sets
a limit or an offset is refused, because it would cut the rows of all parents together.

`with("posts:id,title")` qualifies the listed columns with the related table, so `id` names the
post's column, not the user's.

If a result has no recorded through key when `match` runs, the load throws
`IllegalStateException`.

## Creating rows through the relation

`firstOrNew`, `firstOrCreate`, `createOrFirst`, `updateOrCreate` and `findOrNew` look up the row
through the relation, so the lookup uses the join and the far parent's key. The row they create is
saved through the related model's own query. That row is not linked to the far parent: no
intermediate row is written. A caller that needs the link creates the intermediate row too.

## Refused shapes

A relation whose related table is the same as its through table is refused with
`IllegalStateException`, the first time it builds a query. The message names the relation and
the parent class. A self relation, where the related and the parent table are the same, works and
aliases the related table in the existence query.

`cursorPaginate` reads the cursor value from the order column. An order must name a column of the
related table, qualified with that table's name. An unqualified or through-table order is refused
with `IllegalStateException` that names the column. With no order, the cursor follows the related
table's key.

## Differences from Laravel

| Area | Laravel | Here |
|---|---|---|
| Through key | A visible attribute, so it reaches `toArray`, dirty tracking and `save` | Read by a row hook and kept out of the attributes |
| Soft-deleted through parents | A removable global scope; `withTrashedParents` drops it | A plain `where`, so `withoutGlobalScopes()` keeps it; only `withTrashedParents()` drops it |
| `limit` when the parent key is null | A per-parent group limit | A plain limit on the whole query |
| `limit` on an eager load | A per-parent group limit | Refused, as for every other relation kind |
| `firstOrCreate` and its siblings | Create a row not linked to the far parent | Same; the Javadoc says so |
| Bare columns in an eager `with("rel:a,b")` | Passed as given, so `id` is ambiguous on the join | Qualified with the related table |
| Unqualified columns in constraints or orders | Ambiguous on the joined query | Same; qualify the columns with the table name |
| `ofMany` | Available on `hasOne` and `hasOneThrough` | Not yet available |

## Not in this release

`ofMany`, `latestOfMany` and `oldestOfMany` are not available on `hasOne` or `hasOneThrough`.
The methods `chunkByIdDesc`, `eachById`,
`lazyByIdDesc`, `chunkMap`, `incrementOrCreate`, `one`, `is`, `through` and the relation's `touch`
are not added.
