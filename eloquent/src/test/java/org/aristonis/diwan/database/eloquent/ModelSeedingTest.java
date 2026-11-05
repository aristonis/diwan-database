package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.seeding.SeedContext;
import org.aristonis.diwan.database.seeding.Seeder;
import org.aristonis.diwan.database.sqlite.SQLiteConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Model listeners stay silent while a wrapped seeder runs, and only then. */
class ModelSeedingTest {

    private DatabaseManager manager;
    private ConnectionResolver resolver;
    private Recorder recorder;

    @BeforeEach
    void open() {
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main")
                .connection(ConnectionConfig.builder("main").driver("sqlite").database(":memory:").build())
                .build();
        manager = DatabaseManager.builder().config(config).connector(new SQLiteConnector()).build();
        manager.connection().statement("create table users (id integer primary key autoincrement, name text,"
                + " email text, created_at text, updated_at text)");
        resolver = ConnectionResolver.of(manager);
        recorder = new Recorder();
        User.QUERY.events().observe(recorder);
    }

    @AfterEach
    void close() {
        User.QUERY.events().forget();
        assertFalse(EventMute.isMuted(), "the mute must end with the seeder");
        manager.close();
    }

    private Seeder userSeeder() {
        return new Seeder() {
            @Override
            protected void run(SeedContext context) {
                User.QUERY.using(resolver).make(row("name", "Ann", "email", "a@x")).save();
            }
        };
    }

    @Test
    void anUnwrappedSeederFiresModelEvents() {
        manager.seed(this::userSeeder);

        assertTrue(recorder.events().stream().anyMatch(e -> e.startsWith("saved")), recorder.events().toString());
    }

    @Test
    void aWrappedSeederFiresNoModelEventButStillWritesTheRow() {
        manager.seed(ModelSeeding.withoutModelEvents(this::userSeeder));

        assertEquals(java.util.Collections.emptyList(), recorder.events());
        assertEquals(1, manager.query().table("users").count());
    }

    @Test
    void eventsFireAgainAfterAWrappedSeeder() {
        manager.seed(ModelSeeding.withoutModelEvents(this::userSeeder));

        User.QUERY.using(resolver).make(row("name", "Bob", "email", "b@x")).save();

        assertFalse(recorder.events().isEmpty());
    }

    @Test
    void theMuteEndsWhenTheWrappedSeederThrows() {
        Supplier<Seeder> failing = ModelSeeding.withoutModelEvents(() -> new Seeder() {
            @Override
            protected void run(SeedContext context) {
                throw new IllegalStateException("boom");
            }
        });

        assertThrows(IllegalStateException.class, () -> manager.seed(failing));
        // The mute check runs in close().
    }

    @Test
    void aNullSeederSupplierIsRefused() {
        assertThrows(NullPointerException.class, () -> ModelSeeding.withoutModelEvents(null));
    }
}
