package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.eloquent.TestModels.Person;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.junit.jupiter.api.Test;

class TouchSlotTest {

    /** A model that names relations to touch, in a fixed order. */
    static final class Toucher extends Model {
        static final ModelQuery<Toucher> QUERY = ModelQuery.of(Toucher.class, Toucher::new);

        @Override
        protected Set<String> touches() {
            Set<String> names = new LinkedHashSet<>();
            names.add("owner");
            names.add("author");
            return names;
        }
    }

    /** A model used only for a throwaway entry, so no shared model's registration is touched. */
    private static final class Other extends Model {
    }

    @Test
    void everyDerivedEntrySharesTheTouchesSlot() {
        FakeDb fake = new FakeDb();
        ModelQuery<User> origin = User.QUERY;

        AtomicReference<Object> slot = origin.touchesSlot();

        assertSame(slot, origin.on(fake.db).touchesSlot());
        assertSame(slot, origin.on(fake.db).on(fake.connection).touchesSlot());
        assertSame(slot, origin.using(new ConnectionResolver().register("default", fake.db)).touchesSlot());
        assertSame(slot, origin.withClock(ModelClock.fixed(Instant.EPOCH)).touchesSlot());
    }

    @Test
    void touchesSlotIsSeparateFromTheRelationRegistrySlot() {
        assertNotSame(User.QUERY.relationRegistry(), User.QUERY.touchesSlot());
    }

    @Test
    void separateEntriesHaveSeparateSlots() {
        ModelQuery<Other> other = ModelQuery.of(Other.class, Other::new);

        assertNotSame(User.QUERY.touchesSlot(), other.touchesSlot());
        assertNotSame(User.QUERY.touchesSlot(), Person.QUERY.touchesSlot());
    }

    @Test
    void bridgeReturnsTheRegisteredEntrySlot() {
        FakeDb fake = new FakeDb();
        User user = User.QUERY.on(fake.db).newModel();

        assertSame(User.QUERY.touchesSlot(), Access.eloquent().touchesSlot(user));
    }

    @Test
    void bridgeReadsTheModelsTouches() {
        Toucher toucher = Toucher.QUERY.newModel();

        assertEquals(new ArrayList<>(toucher.touches()),
                new ArrayList<>(Access.eloquent().touches(toucher)));
        assertTrue(Access.eloquent().touches(new User()).isEmpty());
    }

    @Test
    void newQueryOnUsesTheModelsConnectionWhenBound() {
        FakeDb fake = new FakeDb();
        User user = User.QUERY.on(fake.db).newModel();

        EloquentBuilder<User> query = Access.eloquent().newQueryOn(user);

        assertSame(fake.db, query.getModel().getQueryFactory());
        assertSame(User.class, query.getModel().getClass());
    }

    @Test
    void newQueryOnUnboundModelUsesTheRegisteredEntry() {
        FakeDb fake = new FakeDb();
        ConnectionResolver.Installation installed =
                ConnectionResolver.setDefault(new ConnectionResolver().register("default", fake.db));
        try {
            EloquentBuilder<User> query = Access.eloquent().newQueryOn(new User());

            assertSame(fake.db, query.getModel().getQueryFactory());
        } finally {
            installed.close();
        }
    }

    @Test
    void slotStartsEmpty() {
        ModelQuery<Toucher> fresh = ModelQuery.of(Toucher.class, Toucher::new);

        assertNull(fresh.touchesSlot().get());
    }
}
