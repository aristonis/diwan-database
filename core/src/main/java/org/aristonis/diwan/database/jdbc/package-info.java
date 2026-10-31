/**
 * Adapts any JDBC data source to the Diwan Database driver interface.
 *
 * <p>{@link org.aristonis.diwan.database.jdbc.JdbcDriver} implements
 * {@link org.aristonis.diwan.database.driver.Driver} over a
 * {@link org.aristonis.diwan.database.jdbc.JdbcConnectionProvider}, and
 * {@link org.aristonis.diwan.database.jdbc.DataSourceConnectionProvider} is the provider for a
 * plain {@link javax.sql.DataSource}. This is the only package that uses {@code java.sql} or
 * {@code javax.sql}; values crossing out of it follow the value contract of the driver package, so
 * no JDBC type reaches the caller.
 */
package org.aristonis.diwan.database.jdbc;
