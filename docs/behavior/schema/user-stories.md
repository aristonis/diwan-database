# Schema builder: user stories

### Describe a table the Laravel way

As an application developer, I want to create a table by listing its columns with Laravel's method
names, so that I can write migrations without writing SQL for each database.

Done when: a users table with an id, a name, a unique email, a flag and timestamps can be created in
a few lines.

### Grow and tidy the schema

As an application developer, I want to add columns, rename and drop tables, and check what exists,
so that later migrations can build on earlier ones.

Done when: each operation works on SQLite and respects the table prefix.

### See the SQL first

As the migrator, I want schema calls to be recorded in pretend mode, so that a dry run can show them.

Done when: pretend returns the statements and the database is untouched.

### Add a database later without touching the core

As a grammar author, I want the SQL for each database in its own module, so that PostgreSQL and
MySQL can be added without changing the core.

Done when: the SQLite grammar lives in its own module and the core does not depend on it.

## Indexes and keys

As an application developer, I want indexes over several columns and foreign keys with delete
actions, so lookups stay fast and orphan rows never appear.

As an application developer on SQLite, I want to add or drop a key on a filled table without
losing rows, so a later release can tighten the schema.

As an application developer, I want to drop and rename columns and undo every index and key in
`down`, so rollbacks are complete.

Done when: each is created, enforced, listed and dropped on SQLite, rows survive every rebuild.

## Every column type

As an application developer porting a Laravel app, I want every common column method with its
Laravel default, and the shortcuts (`softDeletes`, `rememberToken`, `morphs`, `foreignUuid`), so
migrations port line by line.

Done when: each type is created on SQLite with Laravel's type, and enum values are enforced
through later table rebuilds.

## Turned down

| Story | Reason |
|---|---|
| Fulltext, spatial and partial indexes; changing a column's type | Come in later releases |
| Spatial, vector and computed columns; collation and generated-column modifiers | Come in a later release |
