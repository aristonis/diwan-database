/**
 * Defines the interface a database driver implements for Diwan Database.
 *
 * <p>This package holds {@link org.aristonis.diwan.database.driver.Driver}, the
 * {@link org.aristonis.diwan.database.driver.DriverSession} it opens, the
 * {@link org.aristonis.diwan.database.driver.DriverInfo} it reports, and the unchecked
 * {@link org.aristonis.diwan.database.driver.DriverException} every driver failure is reported
 * as. No signature here uses a {@code java.sql} or {@code javax.sql} type, so a driver that is not
 * built on JDBC, such as one over Android SQLite, can implement it.
 *
 * <p>This package does not hold any driver implementation, connection pooling, transaction nesting
 * logic or SQL grammar; those live in the core and in the adapter packages.
 *
 * <p>Values: this is the value contract every statement method of
 * {@link org.aristonis.diwan.database.driver.DriverSession} follows.
 *
 * <p>Bindings: a driver must accept {@code null} and any {@link java.lang.Boolean},
 * {@link java.lang.Byte}, {@link java.lang.Short}, {@link java.lang.Integer}, {@link java.lang.Long},
 * {@link java.lang.Float}, {@link java.lang.Double}, {@link java.math.BigDecimal},
 * {@link java.math.BigInteger}, {@link java.lang.String}, {@code byte[]},
 * {@link java.time.LocalDate}, {@link java.time.LocalTime}, {@link java.time.LocalDateTime},
 * {@link java.time.OffsetDateTime} and {@link java.time.Instant} as a binding. The bindings list is
 * read-only to the driver, and the driver must not keep a reference to it after the call returns.
 *
 * <p>Returned values: a driver may return {@code null} for SQL {@code NULL} and otherwise only a
 * {@link java.lang.Boolean}, {@link java.lang.Integer}, {@link java.lang.Long},
 * {@link java.lang.Double}, {@link java.lang.Float}, {@link java.math.BigDecimal},
 * {@link java.lang.String}, {@code byte[]}, {@link java.time.LocalDate}, {@link java.time.LocalTime},
 * {@link java.time.LocalDateTime}, {@link java.time.OffsetDateTime}, or a {@code List<Object>} for an
 * array. It never returns a {@code java.sql} or Android type: a character large object or XML value
 * becomes a {@code String}, a binary large object becomes a {@code byte[]}, and a date, time or
 * timestamp becomes the matching {@code java.time} type, all converted before the statement call
 * returns. The numeric width is the database's own; SQLite, for example, has no boolean type, so
 * integers come back where another database would return a {@code Boolean}.
 */
package org.aristonis.diwan.database.driver;
