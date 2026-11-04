package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.UnregisteredModelException;
import org.junit.jupiter.api.Test;

class EloquentAccessBridgeTest {

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db);

    /** A model that counts how often its retrieved hook runs. */
    static final class Watched extends Model {
        int retrievedCalls;

        @Override
        protected void retrieved() {
            retrievedCalls++;
        }
    }

    /** A model whose registry entry the test makes itself. */
    static final class Registered extends Model {
    }

    /** A model that registers itself in its static initialiser; only one test names it. */
    static final class SelfRegistering extends Model {
        static {
            ModelQuery.of(SelfRegistering.class, SelfRegistering::new);
        }
    }

    /** A model that never gets a registry entry. */
    static final class Unregistered extends Model {
    }

    @Test
    void takeAttributesRemovesFromAttributesOnly() {
        User user = users.hydrate(Arrays.asList(
                row("id", 1L, "name", "Ann", "pivot_user_id", 1L, "pivot_role_id", 2L))).get(0);

        Map<String, Object> taken = Access.eloquent().takeAttributes(user, "pivot_");

        assertEquals(2, taken.size());
        assertEquals(1L, taken.get("user_id"));
        assertEquals(2L, taken.get("role_id"));
        assertFalse(user.hasAttribute("pivot_user_id"));
        assertFalse(user.hasAttribute("pivot_role_id"));
        assertEquals("Ann", user.getAttribute("name"));
        assertEquals(1L, user.getOriginal("pivot_user_id"));
        assertEquals(2L, user.getOriginal("pivot_role_id"));
    }

    @Test
    void takeAttributesKeepsOrderAndStripsPrefix() {
        User user = users.newModel();
        user.setAttribute("pivot_a", 1);
        user.setAttribute("name", "Ann");
        user.setAttribute("pivot_b", 2);
        user.setAttribute("pivot_c", 3);

        Map<String, Object> taken = Access.eloquent().takeAttributes(user, "pivot_");

        assertEquals(Arrays.asList("a", "b", "c"), new ArrayList<>(taken.keySet()));
        assertEquals(1, taken.get("a"));
        assertEquals(2, taken.get("b"));
        assertEquals(3, taken.get("c"));
    }

    @Test
    void markExistingSetsExistsWithoutRetrievedEvent() {
        Watched model = new Watched();
        assertFalse(model.exists());

        Access.eloquent().markExisting(model);

        assertTrue(model.exists());
        assertFalse(model.wasRecentlyCreated());
        assertEquals(0, model.retrievedCalls);
    }

    @Test
    void newRegisteredReturnsRegisteredType() {
        ModelQuery.of(Registered.class, Registered::new);

        Registered model = Access.eloquent().newRegistered(Registered.class, null);

        assertSame(Registered.class, model.getClass());
        assertFalse(model.exists());
    }

    @Test
    void newRegisteredUnregisteredThrows() {
        assertThrows(UnregisteredModelException.class,
                () -> Access.eloquent().newRegistered(Unregistered.class, null));
    }

    @Test
    void newRegisteredBindsConnectionOfModel() {
        ModelQuery.of(Registered.class, Registered::new);

        Registered model = Access.eloquent().newRegistered(Registered.class, users.newModel());

        assertSame(fake.db, model.getQueryFactory());
    }

    @Test
    void newRegisteredNullConnectionOf() {
        ModelQuery.of(Registered.class, Registered::new);

        Registered model = Access.eloquent().newRegistered(Registered.class, null);

        assertSame(Registered.class, model.getClass());
        assertFalse(model.isBound());
    }

    @Test
    void registryMissLoadsOnce() {
        Class<SelfRegistering> type = SelfRegistering.class;

        ModelQuery<?> found = ModelQuery.registeredOrLoad(type);

        assertSame(SelfRegistering.class, found.getModelClass());
    }

    @Test
    void stillMissingThrowsUnregistered() {
        UnregisteredModelException error = assertThrows(UnregisteredModelException.class,
                () -> ModelQuery.registeredOrLoad(Unregistered.class));

        assertEquals(Unregistered.class.getName(), error.getModelClassName());
    }

    @Test
    void useRowHookReachesBuilder() {
        List<User> hooked = new ArrayList<>();
        Consumer<User> hook = hooked::add;
        fake.returns("select * from \"users\"", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        EloquentBuilder<User> query = users.query();

        Access.eloquent().useRowHook(query, hook);
        query.get();

        assertEquals(2, hooked.size());
        assertEquals("Ann", hooked.get(0).getAttribute("name"));
        assertEquals("Bob", hooked.get(1).getAttribute("name"));
    }
}
