# Schema builder

The schema builder creates and changes tables, the way Laravel's `Schema` facade does. You describe
the table in a callback; the grammar of your database turns it into SQL.

```java
Schema schema = SQLiteSchema.of(db);

schema.create("users", table -> {
    table.id();
    table.string("name");
    table.string("email").unique();
    table.booleanColumn("active").defaultValue(true);
    table.timestamps();
});

schema.table("users", table -> table.text("bio").nullable());

if (schema.hasColumn("users", "bio")) {
    schema.rename("users", "people");
}
schema.dropIfExists("people");
```

## What you can do

| You want to | Use |
|---|---|
| Create or extend a table | `create`, `table` |
| Remove or rename one | `drop`, `dropIfExists`, `rename` |
| Check what exists | `hasTable`, `hasColumn` |
| Declare columns | `id`, `string`, `text`, `integer`, `bigInteger`, `booleanColumn`, `decimal`, `date`, `timestamp`, `json`, `timestamps` |
| Shape a column | `nullable`, `defaultValue`, `useCurrent`, `unique`, `autoIncrement`, `unsigned`, `comment` |

## How it behaves

- Laravel names that are Java keywords change: `boolean` is `booleanColumn`, `default` is
  `defaultValue`.
- The connection's table prefix goes in front of every table and generated index name.
- Any name is allowed except empty names and names with control characters; quotes inside a name
  are escaped, so a name cannot carry SQL.
- When one call runs several statements (a table and its unique index), they run in one
  transaction on SQLite, so a failure leaves nothing behind.
- In pretend mode the statements are listed and nothing changes; `hasTable` and `hasColumn` return
  `false` there.
- SQLite ignores `unsigned` and `comment`, and adds a not-null column without a default only while
  the table is empty.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md).
