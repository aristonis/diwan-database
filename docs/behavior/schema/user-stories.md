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

## Turned down

| Story | Reason |
|---|---|
| Indexes, foreign keys, changing columns | Come in later releases |
| Every Laravel column type | Comes in a later release |
