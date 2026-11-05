/**
 * Builds named connections from configuration the first time they are used and hands out the
 * query, schema and migrator objects of each.
 *
 * <p>This package holds the typed configuration ({@link org.aristonis.diwan.database.manager.ConnectionConfig},
 * {@link org.aristonis.diwan.database.manager.DatabaseConfig}), the
 * {@link org.aristonis.diwan.database.manager.Connector} factory interface that each database module
 * implements, and the {@link org.aristonis.diwan.database.manager.DatabaseManager} that caches what
 * the connectors build. The raw configuration sources live in
 * {@link org.aristonis.diwan.database.config}.
 *
 * <p>This package does not hold a database driver, a JDBC URL builder or a connection pool: a
 * connector builds its own data source, and a pool is plugged in through
 * {@link org.aristonis.diwan.database.manager.DatabaseManager.Builder#dataSource(String, java.util.function.Supplier)}.
 */
package org.aristonis.diwan.database.manager;
