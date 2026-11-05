package org.aristonis.diwan.database.sqlite;

import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.seeding.SeedContext;
import org.aristonis.diwan.database.seeding.Seeder;

/** Shared builders for the manager tests of this module. */
final class ManagerFixtures {

    private ManagerFixtures() {
    }

    /** Returns a SQLite connection setting for a name and a database path or {@code :memory:}. */
    static ConnectionConfig sqlite(String name, String database) {
        return ConnectionConfig.builder(name).driver("sqlite").database(database).build();
    }

    /** Returns a manager over the given connections, the first one being the default. */
    static DatabaseManager manager(ConnectionConfig first, ConnectionConfig... more) {
        DatabaseConfig.Builder config = DatabaseConfig.builder().defaultConnection(first.name()).connection(first);
        for (ConnectionConfig other : more) {
            config.connection(other);
        }
        return DatabaseManager.builder().config(config.build()).connector(new SQLiteConnector()).build();
    }

    /** Returns a migration that creates a {@code users} table with a name and an optional email. */
    static Migration createUsers() {
        return step("2026_01_create_users", schema -> schema.create("users", t -> {
            t.id();
            t.string("name");
        }), schema -> schema.drop("users"));
    }

    /** Returns a migration from a name and its two directions. */
    static Migration step(String name, Consumer<Schema> up, Consumer<Schema> down) {
        return new Migration() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void up(Schema schema) {
                up.accept(schema);
            }

            @Override
            public void down(Schema schema) {
                down.accept(schema);
            }
        };
    }

    /** Returns a seeder that inserts one user per name. */
    static Seeder users(String... names) {
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
