package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Creates and drops real databases on the test server. */
class PostgresCreateDatabaseTest {

    private static final AtomicInteger NAMES = new AtomicInteger();

    private Connection admin;
    private Schema schema;
    private String name;

    @BeforeEach
    void connect() {
        admin = PostgresServer.newDatabase();
        schema = PostgresSchema.of(admin);
        name = "cd_" + System.nanoTime() + "_" + NAMES.incrementAndGet();
    }

    @AfterEach
    void cleanUp() {
        if (schema != null) {
            schema.dropDatabaseIfExists(name, true);
            schema.dropDatabaseIfExists(name + "_copy", true);
        }
    }

    private boolean exists(String database) {
        return admin.scalar("select 1 from pg_database where datname = ?", database).isPresent();
    }

    @Test
    void createAndDropRoundTrip() {
        schema.createDatabase(name);

        assertTrue(exists(name));
        assertTrue(schema.dropDatabaseIfExists(name));
        assertFalse(exists(name));
    }

    @Test
    void createFromTemplateCopiesTheTemplateContent() {
        schema.createDatabase(name);
        Connection source = Connection.builder().name("pgsql").dataSource(PostgresServer.dataSource(name)).build();
        source.statement("create table kept (id int)");
        source.statement("insert into kept values (7)");

        schema.createDatabase(name + "_copy", name);

        Connection copy = Connection.builder().name("pgsql").dataSource(PostgresServer.dataSource(name + "_copy"))
                .build();
        assertEquals(7, ((Number) copy.scalar("select id from kept").get()).intValue());
    }

    @Test
    void createWithWalLogStrategyWhenTheServerHasIt() {
        org.junit.jupiter.api.Assumptions.assumeTrue(schema.grammar().supportsCopyStrategy(admin),
                "copy strategy needs PostgreSQL 15");

        schema.createDatabase(name, "template1", CopyStrategy.WAL_LOG);

        assertTrue(exists(name));
    }

    @Test
    void dropMissingReturnsFalse() {
        assertFalse(schema.dropDatabaseIfExists(name));
        assertFalse(schema.dropDatabaseIfExists(name, false));
    }

    @Test
    void mixedCaseNameIsKeptAsGiven() {
        String mixed = "Mixed_" + name;
        try {
            schema.createDatabase(mixed);

            assertTrue(exists(mixed));
        } finally {
            schema.dropDatabaseIfExists(mixed, true);
        }
    }

    @Test
    void forceDropsWithOpenSession() throws SQLException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                PostgresDatabaseCommandsVersion.atLeast13(admin), "force needs PostgreSQL 13");
        schema.createDatabase(name);

        try (java.sql.Connection open = PostgresServer.dataSource(name).getConnection()) {
            assertTrue(open.isValid(1));
            assertTrue(schema.dropDatabaseIfExists(name, true));
            assertFalse(exists(name));
        }
    }

    @Test
    void dropWithoutForceFailsWhileASessionIsOpen() throws SQLException {
        schema.createDatabase(name);

        try (java.sql.Connection open = PostgresServer.dataSource(name).getConnection()) {
            assertTrue(open.isValid(1));
            assertThrows(QueryException.class, () -> schema.dropDatabaseIfExists(name));
        }
    }

    @Test
    void missingCreateDbPrivilegeSurfacesSqlState42501() {
        String role = "nocreate_" + NAMES.incrementAndGet();
        admin.statement("create role " + role + " login nocreatedb");
        PGSimpleDataSource restricted = PostgresServer.dataSource("postgres");
        restricted.setUser(role);
        Connection limited = Connection.builder().name("pgsql").dataSource(restricted).build();
        try {
            QueryException e = assertThrows(QueryException.class,
                    () -> PostgresSchema.of(limited).createDatabase(name));

            assertEquals("42501", e.sqlState().orElse(""));
        } finally {
            admin.statement("drop role " + role);
        }
    }

    /** Reads the version the same way the grammar does, for the skip condition. */
    private static final class PostgresDatabaseCommandsVersion {
        static boolean atLeast13(Connection connection) {
            return org.aristonis.diwan.database.schema.DatabaseVersion.atLeast(PostgresVersion.of(connection),
                    PostgresDatabaseCommands.FORCE_SINCE);
        }
    }
}
