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

schema.create("posts", table -> {
    table.id();
    table.foreignId("user_id").constrained().cascadeOnDelete();
    table.string("title");
    table.index("user_id", "title");
});

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
| Check what exists | `hasTable`, `hasColumn`, `indexes`, `foreignKeys` |
| Add or drop indexes | `index`, `unique`, `primary`, `dropIndex`, `dropUnique`, `dropPrimary`, `renameIndex` |
| Link tables | `foreignId(...).constrained()`, `foreign(...).references(...).on(...)`, `cascadeOnDelete` and the other actions, `dropForeign`, `dropConstrainedForeignId` |
| Change columns | `dropColumn`, `renameColumn` |
| Work around keys | `withoutForeignKeyConstraints` |
| Declare columns | `id`, `string`, `text`, `integer`, `bigInteger`, `booleanColumn`, `decimal`, `date`, `timestamp`, `json`, `timestamps` |
| Shape a column | `nullable`, `defaultValue`, `useCurrent`, `unique`, `index`, `primary`, `autoIncrement`, `unsigned`, `comment` |

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
- On SQLite, adding or dropping a primary or foreign key on an existing table rebuilds the table
  with its rows, indexes and auto-increment counter; if a row breaks the new key, nothing changes and
  `ForeignKeyCheckException` names the tables and the number of rows. Inside your own transaction,
  a table other tables point at is not rebuilt; run the change in a migration or in
  `withoutForeignKeyConstraints`.
- A column an index or key still uses cannot be dropped; drop the index or key first, in the same
  call if you like.
- `pretend` on the schema reads the live tables, so a rebuild lists its real statements; a table
  made earlier in the same pretend run does not exist yet for it.

See [user stories](user-stories.md) and [user scenarios](user-scenarios.md).
