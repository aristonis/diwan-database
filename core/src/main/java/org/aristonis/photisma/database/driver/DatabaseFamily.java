package org.aristonis.photisma.database.driver;

import java.util.Locale;
import java.util.Objects;

/**
 * Groups databases whose SQL and error codes behave alike.
 *
 * <p>The family is read from the product name a driver reports. Matching ignores case and looks
 * for a known word inside the name, because drivers report names such as {@code "SQLite"},
 * {@code "PostgreSQL"}, {@code "MySQL"} or {@code "MariaDB"} with different spellings and
 * suffixes.
 */
public enum DatabaseFamily {
    /** SQLite. */
    SQLITE,
    /** PostgreSQL. */
    POSTGRESQL,
    /** MySQL and MariaDB. */
    MYSQL,
    /** Microsoft SQL Server. */
    SQLSERVER,
    /** Oracle Database. */
    ORACLE,
    /** Any other or unknown database. */
    OTHER;

    /**
     * Classifies a database product name.
     *
     * @param productName the product name, or {@code null} if unknown
     * @return the family, never {@code null}; {@link #OTHER} when the name is {@code null} or not
     *     recognised
     */
    public static DatabaseFamily of(String productName) {
        if (productName == null) {
            return OTHER;
        }
        String name = productName.toLowerCase(Locale.ROOT);
        if (name.contains("sqlite")) {
            return SQLITE;
        }
        if (name.contains("postgresql")) {
            return POSTGRESQL;
        }
        if (name.contains("mysql") || name.contains("mariadb")) {
            return MYSQL;
        }
        if (name.contains("microsoft sql server")) {
            return SQLSERVER;
        }
        if (name.contains("oracle")) {
            return ORACLE;
        }
        return OTHER;
    }

    /**
     * Classifies the database a driver talks to.
     *
     * @param info the driver's description, never {@code null}
     * @return the family, never {@code null}
     * @throws NullPointerException if {@code info} is {@code null}
     */
    public static DatabaseFamily of(DriverInfo info) {
        return of(Objects.requireNonNull(info, "info").databaseName());
    }
}
