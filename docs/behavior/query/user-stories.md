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

### Combine tables and queries

As an application developer, I want joins, subqueries and unions with Laravel's names, so that
reports and screens that read several tables do not fall back to raw SQL.

Done when: the same join, subquery or union chain returns the same rows on SQLite, PostgreSQL and
Android, and its values line up with the placeholders.

### Copy and change rows across tables

As an application developer, I want to insert rows from a query and update or delete rows through
a join, so that bulk changes run as one statement.

Done when: `insertUsing` copies rows, `insertOrIgnoreUsing` skips duplicates, and a joined update
or delete changes only the joined rows.

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

### Read a large table without loading it all

As an application developer, I want to process rows in batches or stream them, so that an export
or a recalculation over a big table does not run out of memory.

Done when: `chunk`, `chunkById`, `lazy` and `cursor` visit every row once on SQLite, PostgreSQL
and Android, and a closed cursor gives its connection back.

### Show a list one page at a time

As an application developer, I want numbered pages with a total, or pages that follow a cursor
token, so that a list screen shows a page and links to the next one.

Done when: `paginate`, `simplePaginate` and `cursorPaginate` return the right rows and totals,
and a cursor token walks forward and back through every row.
