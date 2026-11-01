# Query builder: user stories

### Read rows without writing SQL

As an application developer, I want to filter, sort and page rows with Laravel's method names, so
that I do not write SQL strings for each database.

Done when: the same chain returns the same rows on SQLite, PostgreSQL and Android.

### Write rows and get the new id

As an application developer, I want to insert, update and delete rows and get a new row's id back,
so that I can follow an insert with related writes.

Done when: `insertGetId` returns the id on every supported database, including older Android
SQLite.

### Count without breaking my query

As an application developer, I want to count or sum the rows a query matches, so that I can show
totals next to a page of results.

Done when: an aggregate ignores the page's order and limit and the builder still works afterwards.

### Store money and moments safely

As an application developer, I want money and moments to read back exactly as written, so that
totals never drift and times never shift by a time zone.

Done when: a money amount and an `Instant` round-trip unchanged on every database.

### See the SQL

As an application developer, I want to see the SQL and its values, so that I can debug a query.

Done when: `toSql`, `getBindings` and `toRawSql` show what would run.

### Add a database later without touching the core

As a grammar author, I want each database's query grammar in its own module, so that a new
database can be added without changing the core.

Done when: the SQLite and PostgreSQL grammars live in their modules and the core does not depend on
them.
