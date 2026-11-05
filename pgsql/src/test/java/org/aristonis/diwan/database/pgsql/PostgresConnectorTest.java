package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.seeding.SeedContext;
import org.aristonis.diwan.database.seeding.Seeder;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class PostgresConnectorTest {

    private static ConnectionConfig.Builder pg() {
        return ConnectionConfig.builder("main").driver("pgsql");
    }

    @Test
    void theUrlComesFromHostPortAndDatabaseWithPort5432ByDefault() {
        assertEquals("jdbc:postgresql://db.local:5432/app",
                PostgresConnector.jdbcUrl(pg().host("db.local").database("app").build()));
        assertEquals("jdbc:postgresql://db.local:6543/app",
                PostgresConnector.jdbcUrl(pg().host("db.local").port(6543).database("app").build()));
    }

    @Test
    void anIpv6HostIsBracketedAndTheDatabaseIsEncoded() {
        assertEquals("jdbc:postgresql://[::1]:5432/my%20db",
                PostgresConnector.jdbcUrl(pg().host("::1").database("my db").build()));
    }

    @Test
    void sslmodeAndSearchPathBecomeUrlParameters() {
        String url = PostgresConnector.jdbcUrl(pg().host("h").database("d")
                .option("sslmode", "require").option("search_path", "tenant_1").build());

        assertEquals("jdbc:postgresql://h:5432/d?sslmode=require&currentSchema=tenant_1", url);
    }

    @Test
    void theSchemaOptionIsAnAliasOfSearchPath() {
        assertTrue(PostgresConnector.jdbcUrl(pg().host("h").database("d").option("schema", "s").build())
                .endsWith("?currentSchema=s"));
    }

    @Test
    void anExplicitUrlIsKeptAndKeepsItsOwnParameters() {
        String url = PostgresConnector.jdbcUrl(pg().url("jdbc:postgresql://x/y?sslmode=disable")
                .option("sslmode", "require").option("search_path", "s").build());

        assertEquals("jdbc:postgresql://x/y?sslmode=disable&currentSchema=s", url);
    }

    @Test
    void thePasswordNeverEntersTheUrl() {
        String url = PostgresConnector.jdbcUrl(pg().host("h").database("d").username("u").password("s3cret").build());

        assertFalse(url.contains("s3cret"), url);
        assertFalse(url.contains("password"), url);
    }

    @Test
    void aMissingHostOrDatabaseNamesTheConnection() {
        IllegalArgumentException noHost = assertThrows(IllegalArgumentException.class,
                () -> PostgresConnector.jdbcUrl(pg().database("d").build()));
        IllegalArgumentException noDatabase = assertThrows(IllegalArgumentException.class,
                () -> PostgresConnector.jdbcUrl(pg().host("h").build()));

        assertTrue(noHost.getMessage().contains("main") && noHost.getMessage().contains("host"));
        assertTrue(noDatabase.getMessage().contains("main") && noDatabase.getMessage().contains("database"));
    }

    @Test
    void theDriverNameIsPgsqlAndALockIsOffered() {
        PostgresConnector connector = new PostgresConnector();
        PGSimpleDataSource source = new PGSimpleDataSource();

        assertEquals("pgsql", connector.driver());
        assertTrue(connector.lock(pg().host("h").database("d").build(), source).get() instanceof PostgresMigrationLock);
    }

    @Test
    void liveManagerMigratesAndSeedsOverHostAndPort() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource server = PostgresServer.dataSource(database);
        ConnectionConfig config = pg().host(server.getServerNames()[0]).port(server.getPortNumbers()[0])
                .database(database).username("postgres").build();
        DatabaseConfig all = DatabaseConfig.builder().defaultConnection("main").connection(config).build();

        try (DatabaseManager manager = DatabaseManager.builder().config(all).connector(new PostgresConnector())
                .build()) {
            manager.migrator("main").add(users()).build().run();
            manager.seed(() -> seeder("Ann", "Bob"));

            assertEquals(2, manager.query().table("users").count());
        }
    }

    @Test
    void liveManagerUsesASuppliedDataSource() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource source = PostgresServer.dataSource(database);
        DatabaseConfig all = DatabaseConfig.builder().defaultConnection("main").connection(pg().build()).build();

        try (DatabaseManager manager = DatabaseManager.builder().config(all).connector(new PostgresConnector())
                .dataSource("main", () -> source).build()) {
            manager.migrator("main").add(users()).build().run();

            assertTrue(manager.schema().hasTable("users"));
        }
    }

    private static Migration users() {
        return new Migration() {
            @Override
            public String name() {
                return "2026_01_users";
            }

            @Override
            public void up(Schema schema) {
                schema.create("users", t -> {
                    t.id();
                    t.string("name");
                });
            }

            @Override
            public void down(Schema schema) {
                schema.drop("users");
            }
        };
    }

    private static Seeder seeder(String... names) {
        return new Seeder() {
            @Override
            protected void run(SeedContext context) {
                for (String name : names) {
                    context.query().table("users").insert(Map.of("name", name));
                }
            }
        };
    }
}
