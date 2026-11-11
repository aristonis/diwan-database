package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.grammar.SchemaQuery;
import org.junit.jupiter.api.Test;

/** Checks the SQL the grammar compiles for create and drop database, and the version refusals. */
class PostgresSchemaGrammarDatabaseTest {

    private final PostgresSchemaGrammar grammar = new PostgresSchemaGrammar();

    private static Connection serverVersion(String version) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "scalar":
                            return Optional.of(version);
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "stub connection";
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @Test
    void createDatabaseSendsNoEncoding() {
        String sql = grammar.compileCreateDatabase(serverVersion("16.2"), "app", null, null);

        assertEquals("create database \"app\"", sql);
    }

    @Test
    void createDatabaseWithTemplate() {
        String sql = grammar.compileCreateDatabase(serverVersion("16.2"), "copy_1", "tpl", null);

        assertEquals("create database \"copy_1\" template \"tpl\"", sql);
    }

    @Test
    void mixedCaseNamesAreWrappedAndQuotesAreDoubled() {
        String sql = grammar.compileCreateDatabase(serverVersion("16.2"), "MyDb", "Tpl\"x", null);

        assertEquals("create database \"MyDb\" template \"Tpl\"\"x\"", sql);
    }

    @Test
    void strategyIsAppendedOnVersion15() {
        Connection connection = serverVersion("15.0");

        assertEquals("create database \"a\" template \"t\" strategy file_copy",
                grammar.compileCreateDatabase(connection, "a", "t", CopyStrategy.FILE_COPY));
        assertEquals("create database \"a\" template \"t\" strategy wal_log",
                grammar.compileCreateDatabase(connection, "a", "t", CopyStrategy.WAL_LOG));
    }

    @Test
    void defaultStrategyIsFileCopy() {
        assertTrue(grammar.supportsCopyStrategy(serverVersion("15.4")));
        assertTrue(grammar.supportsCopyStrategy(serverVersion("17.0 (Debian 17.0-1)")));
        assertFalse(grammar.supportsCopyStrategy(serverVersion("14.11")));
    }

    @Test
    void strategyBelow15IsRefused() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> grammar.compileCreateDatabase(serverVersion("14.11"), "a", "t", CopyStrategy.WAL_LOG));

        assertTrue(e.getMessage().contains("14.11"), e.getMessage());
    }

    @Test
    void noStrategyNeedsNoVersionRead() {
        Connection noReads = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });

        assertEquals("create database \"a\"", grammar.compileCreateDatabase(noReads, "a", null, null));
        assertEquals("drop database if exists \"a\"", grammar.compileDropDatabaseIfExists(noReads, "a", false));
    }

    @Test
    void dropIfExistsWrapsTheName() {
        assertEquals("drop database if exists \"My Db\"",
                grammar.compileDropDatabaseIfExists(serverVersion("12.0"), "My Db", false));
    }

    @Test
    void forceAddsWithForceOnVersion13() {
        assertEquals("drop database if exists \"a\" with (force)",
                grammar.compileDropDatabaseIfExists(serverVersion("13.0"), "a", true));
    }

    @Test
    void forceBelow13IsRefused() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> grammar.compileDropDatabaseIfExists(serverVersion("12.18"), "a", true));

        assertTrue(e.getMessage().contains("12.18"), e.getMessage());
    }

    @Test
    void existsBindsTheNameInsteadOfQuotingIt() {
        SchemaQuery query = grammar.compileDatabaseExists("Mixed\"Case");

        assertEquals("select 1 from pg_database where datname = ?", query.sql());
        assertEquals(java.util.Collections.singletonList("Mixed\"Case"), query.bindings());
    }
}
