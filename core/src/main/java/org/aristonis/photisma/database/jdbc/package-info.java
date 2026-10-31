/**
 * Adapts any JDBC data source to the Photisma driver interface.
 *
 * <p>{@link org.aristonis.photisma.database.jdbc.JdbcDriver} implements
 * {@link org.aristonis.photisma.database.driver.Driver} over a
 * {@link org.aristonis.photisma.database.jdbc.JdbcConnectionProvider}, and
 * {@link org.aristonis.photisma.database.jdbc.DataSourceConnectionProvider} is the provider for a
 * plain {@link javax.sql.DataSource}. This is the only package that uses {@code java.sql} or
 * {@code javax.sql}; values crossing out of it follow the value contract of the driver package, so
 * no JDBC type reaches the caller.
 */
package org.aristonis.photisma.database.jdbc;
