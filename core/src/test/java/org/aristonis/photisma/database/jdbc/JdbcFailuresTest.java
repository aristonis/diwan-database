package org.aristonis.photisma.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Set;
import org.aristonis.photisma.database.driver.DriverCapability;
import org.aristonis.photisma.database.driver.DriverException;
import org.junit.jupiter.api.Test;

class JdbcFailuresTest {

    private static DriverException translate(String state, int code, String product) {
        return JdbcFailures.translate(new SQLException("raw text with secret@x", state, code), product);
    }

    @Test
    void recognisesUniqueViolationsOfEveryDatabase() {
        assertTrue(translate("23505", 0, "PostgreSQL").isUniqueViolation());
        assertTrue(translate("23000", 1062, "MySQL").isUniqueViolation());
        assertTrue(translate("23000", 1062, "MariaDB").isUniqueViolation());
        assertTrue(translate(null, 2067, "SQLite").isUniqueViolation());
        assertTrue(translate("23000", 2627, "Microsoft SQL Server").isUniqueViolation());
        assertTrue(translate("23000", 2601, "Microsoft SQL Server").isUniqueViolation());
        assertTrue(translate("23000", 1, "Oracle").isUniqueViolation());
    }

    @Test
    void readsSqliteUniqueViolationsFromThePrimaryCodeAndTheText() {
        SQLException sqlite = new SQLException("[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed", null, 19);

        assertTrue(JdbcFailures.translate(sqlite, "SQLite").isUniqueViolation());
        assertFalse(JdbcFailures.translate(new SQLException("[SQLITE_CONSTRAINT_NOTNULL]", null, 19), "SQLite")
                .isUniqueViolation());
    }

    @Test
    void treatsVendorCodeOneAsUniqueOnlyOnOracle() {
        assertFalse(translate(null, 1, "SQLite").isUniqueViolation());
        assertFalse(translate("HY000", 1, "MySQL").isUniqueViolation());
    }

    @Test
    void recognisesConcurrencyFailuresOfEveryDatabase() {
        assertTrue(translate("40001", 0, "PostgreSQL").isConcurrencyFailure());
        assertTrue(translate("40P01", 0, "PostgreSQL").isConcurrencyFailure());
        assertTrue(translate("40001", 1213, "MySQL").isConcurrencyFailure());
        assertTrue(translate("40001", 1205, "Microsoft SQL Server").isConcurrencyFailure());
        assertTrue(translate(null, 5, "SQLite").isConcurrencyFailure());
        assertTrue(translate(null, 6, "SQLite").isConcurrencyFailure());
    }

    @Test
    void neverTreatsALostConnectionAsAConcurrencyFailure() {
        SQLException lost = new SQLException("connection reset", "08006", 0);
        lost.setNextException(new SQLException("deadlock", "40001", 1213));

        assertFalse(JdbcFailures.translate(lost, "PostgreSQL").isConcurrencyFailure());
    }

    @Test
    void findsTheStateDeeperInTheChain() {
        SQLException outer = new SQLException("batch failed", null, 0);
        outer.setNextException(new SQLException("dup", "23505", 0));

        DriverException failure = JdbcFailures.translate(outer, "PostgreSQL");

        assertTrue(failure.isUniqueViolation());
    }

    @Test
    void survivesAChainThatLoops() {
        SQLException first = new SQLException("a", "XX000", 0);
        SQLException second = new SQLException("b", "XX000", 0);
        first.setNextException(second);
        second.setNextException(first);

        assertFalse(JdbcFailures.translate(first, "PostgreSQL").isUniqueViolation());
    }

    @Test
    void buildsItsMessageFromCodesOnlyAndKeepsTheOriginalAsCause() {
        SQLException original = new SQLException("Key (email)=(secret@x) already exists", "23505", 0);

        DriverException failure = JdbcFailures.translate(original, "PostgreSQL");

        assertFalse(failure.getMessage().contains("secret@x"));
        assertTrue(failure.getMessage().contains("23505"));
        assertSame(original, failure.getCause());
        assertEquals("23505", failure.sqlState().orElseThrow());
    }

    @Test
    void reportsCapabilitiesPerDatabase() {
        Set<DriverCapability> postgres = JdbcDriver.capabilities("PostgreSQL");
        Set<DriverCapability> mysql = JdbcDriver.capabilities("MySQL");
        Set<DriverCapability> sqlServer = JdbcDriver.capabilities("Microsoft SQL Server");
        Set<DriverCapability> oracle = JdbcDriver.capabilities("Oracle");

        assertTrue(postgres.contains(DriverCapability.SAVEPOINT_RELEASE)
                && postgres.contains(DriverCapability.TRANSACTIONAL_DDL));
        assertTrue(mysql.contains(DriverCapability.SAVEPOINT_RELEASE)
                && !mysql.contains(DriverCapability.TRANSACTIONAL_DDL));
        assertTrue(!sqlServer.contains(DriverCapability.SAVEPOINT_RELEASE)
                && sqlServer.contains(DriverCapability.TRANSACTIONAL_DDL));
        assertTrue(oracle.isEmpty());
        assertTrue(JdbcDriver.capabilities("SomethingElse").isEmpty());
    }

    @Test
    void readsVendorCodesOnlyForTheDatabaseThatOwnsThem() {
        assertFalse(translate("72000", 1555, "Oracle").isUniqueViolation());
        assertFalse(translate("72000", 2067, "Oracle").isUniqueViolation());
        assertFalse(translate(null, 5, "Oracle").isConcurrencyFailure());
        assertFalse(translate("HY000", 1213, "PostgreSQL").isConcurrencyFailure());
        assertFalse(translate("HY000", 2627, "MySQL").isUniqueViolation());
    }

    @Test
    void recognisesOracleDeadlocksAndSerializationFailures() {
        assertTrue(translate("61000", 60, "Oracle").isConcurrencyFailure());
        assertTrue(translate("72000", 8177, "Oracle").isConcurrencyFailure());
    }
}
