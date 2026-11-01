/**
 * PostgreSQL support: the schema grammar that turns a blueprint into PostgreSQL SQL, the query
 * grammar and processor, and a migration lock built on advisory locks.
 *
 * <p>Use {@link org.aristonis.diwan.database.pgsql.PostgresSchema#of} to get a schema builder
 * and {@link org.aristonis.diwan.database.pgsql.PostgresQuery#of} to get a query factory for a
 * connection to PostgreSQL. The application brings the PostgreSQL JDBC driver.
 */
package org.aristonis.diwan.database.pgsql;
