/**
 * PostgreSQL support: the schema grammar that turns a blueprint into PostgreSQL SQL, and a
 * migration lock built on advisory locks.
 *
 * <p>Use {@link org.aristonis.diwan.database.pgsql.PostgresSchema#of} to get a schema builder
 * for a connection to PostgreSQL. The application brings the PostgreSQL JDBC driver.
 */
package org.aristonis.diwan.database.pgsql;
