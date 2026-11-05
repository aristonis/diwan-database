package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.diwan.database.eloquent.TestModels.Person;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectionResolverTest {

    private final FakeDb main = new FakeDb();
    private final FakeDb crm = new FakeDb();

    @AfterEach
    void reset() {
        ConnectionResolver.resetDefault();
    }

    @Test
    void namesResolveToTheirFactoriesAndNullToTheDefault() {
        ConnectionResolver resolver = new ConnectionResolver().register("main", main.db).register("crm", crm.db);
        resolver.setDefaultConnection("main");

        assertSame(main.db, resolver.resolve(null));
        assertSame(crm.db, resolver.resolve("crm"));
        assertEquals("main", resolver.getDefaultConnection());
        assertTrue(resolver.hasConnection("crm"));
        assertFalse(resolver.hasConnection("other"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> resolver.resolve("other"));
        assertEquals("No connection is registered as [other]", e.getMessage());
    }

    @Test
    void modelsUseTheirConnectionNameThroughTheResolver() {
        ConnectionResolver resolver = new ConnectionResolver().register("default", main.db).register("crm", crm.db);

        Person person = Person.QUERY.using(resolver).newModel();
        person.forceFill(row("name", "x"));
        person.save();
        User user = User.QUERY.using(resolver).make(row("name", "y"));
        user.save();

        assertEquals(1, crm.calls.size());
        assertEquals(1, main.calls.size());
        assertSame(crm.db, person.getQueryFactory());
    }

    @Test
    void theProcessDefaultIsInstalledByAHandleThatRestoresThePrevious() {
        ConnectionResolver first = new ConnectionResolver().register("default", main.db);
        ConnectionResolver second = new ConnectionResolver().register("default", crm.db);

        ConnectionResolver.Installation outer = ConnectionResolver.setDefault(first);
        assertSame(first, ConnectionResolver.getDefault().get());
        ConnectionResolver.Installation inner = ConnectionResolver.setDefault(second);
        User user = new User();
        user.setAttribute("name", "x");
        user.save();
        assertEquals(1, crm.calls.size());
        inner.close();
        assertSame(first, ConnectionResolver.getDefault().get());
        assertSame(main.db, new User().getQueryFactory());
        inner.close();
        assertSame(first, ConnectionResolver.getDefault().get());
        outer.close();
        assertFalse(ConnectionResolver.getDefault().isPresent());
        assertThrows(IllegalStateException.class, () -> new User().getQueryFactory());
    }

    @Test
    void resetDefaultClearsIt() {
        ConnectionResolver.setDefault(new ConnectionResolver());

        ConnectionResolver.resetDefault();

        assertFalse(ConnectionResolver.getDefault().isPresent());
    }

    @Test
    void aManagerBackedResolverKnowsConfiguredNamesBeforeTheyAreBuilt() {
        org.aristonis.diwan.database.manager.DatabaseConfig config = org.aristonis.diwan.database.manager.DatabaseConfig
                .builder().defaultConnection("a")
                .connection(org.aristonis.diwan.database.manager.ConnectionConfig.builder("a").driver("sqlite")
                        .database(":memory:").build())
                .build();
        try (org.aristonis.diwan.database.manager.DatabaseManager manager =
                org.aristonis.diwan.database.manager.DatabaseManager.builder().config(config)
                        .connector(new org.aristonis.diwan.database.sqlite.SQLiteConnector()).build()) {
            ConnectionResolver resolver = ConnectionResolver.of(manager);

            assertTrue(manager.getConnections().isEmpty());
            assertTrue(resolver.hasConnection("a"));
            assertFalse(resolver.hasConnection("zzz"));
        }
    }
}
