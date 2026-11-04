package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.KeyedMembership;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pivot writes of a belongsToMany relation declared with a {@code using} class: the pivots are
 * saved and deleted as models, so their events fire, and nothing runs for an empty or unchanged
 * write. Each test opens its own in-memory database.
 */
class BelongsToManyCustomPivotTest {

    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private BtmWriteDb db;

    /** A user whose roles link through {@link KeyedMembership}, which records no events. */
    static final class KeyedUser extends Model {
        static final ModelQuery<KeyedUser> QUERY = ModelQuery.of(KeyedUser.class, KeyedUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(KeyedUser.class).add("roles", KeyedUser::roles);
        }

        public BelongsToMany<TsRole> roles() {
            return belongsToMany(TsRole.class, KeyedMembership.class, "user_id", "role_id")
                    .withPivot("status", "weight");
        }
    }

    /** A user whose roles link through {@link EventMembership}. */
    static final class EventUser extends Model {
        static final ModelQuery<EventUser> QUERY = ModelQuery.of(EventUser.class, EventUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(EventUser.class).add("roles", EventUser::roles);
        }

        public BelongsToMany<TsRole> roles() {
            return belongsToMany(TsRole.class, EventMembership.class, "user_id", "role_id")
                    .withPivot("status", "weight");
        }
    }

    /** The keyed pivot with its hooks recorded in {@link #EVENTS}. */
    static final class EventMembership extends Pivot {
        static final ModelQuery<EventMembership> QUERY = ModelQuery.of(EventMembership.class, EventMembership::new);

        public EventMembership() {
        }

        @Override
        protected String table() {
            return "role_user_keyed";
        }

        @Override
        protected boolean incrementing() {
            return true;
        }

        @Override
        protected boolean creating() {
            EVENTS.add("creating");
            return super.creating();
        }

        @Override
        protected void created() {
            EVENTS.add("created");
        }

        @Override
        protected void saved() {
            EVENTS.add("saved");
        }

        @Override
        protected boolean deleting() {
            EVENTS.add("deleting");
            return super.deleting();
        }

        @Override
        protected void deleted() {
            EVENTS.add("deleted");
        }
    }

    @BeforeEach
    void open() throws SQLException {
        db = BtmWriteDb.open();
        db.seed();
        assertNotNull(KeyedUser.QUERY);
        assertNotNull(EventUser.QUERY);
        assertNotNull(EventMembership.QUERY);
        EVENTS.clear();
    }

    @AfterEach
    void close() throws SQLException {
        EVENTS.clear();
        db.close();
    }

    @Test
    void detachWithEmptyListAndCustomPivotRunsNoQuery() {
        // Arrange
        BelongsToMany<TsRole> roles = KeyedUser.QUERY.on(db.db).findOrFail(1).roles();
        db.queryLog().clear();

        // Act
        int detached = roles.detach(Collections.emptyList());

        // Assert
        assertEquals(0, detached);
        assertTrue(db.queryLog().sql().isEmpty());
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(3, keyedCount("1 = 1"));
    }

    @Test
    void updateWithSeveralIdsUpdatesEachPivot() {
        // Arrange
        BelongsToMany<TsRole> roles = KeyedUser.QUERY.on(db.db).findOrFail(1).roles();

        // Act
        int updated = roles.updateExistingPivot(Arrays.asList(1, 2), status("done"));

        // Assert
        assertEquals(2, updated);
        assertEquals(2, keyedCount("user_id = 1 and status = 'done'"));
        assertEquals(1, keyedCount("user_id = 2 and status = 'active'"));
    }

    @Test
    void updateWithUnchangedValuesWritesNothing() {
        // Arrange
        BelongsToMany<TsRole> roles = KeyedUser.QUERY.on(db.db).findOrFail(1).roles();
        db.queryLog().clear();

        // Act
        int updated = roles.updateExistingPivot(1, status("active"));

        // Assert
        assertEquals(0, updated);
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(1, keyedCount("user_id = 1 and role_id = 1 and status = 'active'"));
    }

    @Test
    void attachFiresCreatingAndCreatedEvents() {
        // Arrange
        BelongsToMany<TsRole> roles = EventUser.QUERY.on(db.db).findOrFail(3).roles();

        // Act
        roles.attach(1, attributes("fresh", 7));

        // Assert
        assertEquals(Arrays.asList("creating", "created", "saved"), new ArrayList<>(EVENTS));
        assertEquals(1, keyedCount("user_id = 3 and role_id = 1 and status = 'fresh' and weight = 7"));
        assertTrue(db.real.scalar("select id from role_user_keyed where user_id = 3").isPresent());
    }

    @Test
    void detachFiresDeleteEventsPerPivot() {
        // Arrange
        BelongsToMany<TsRole> roles = EventUser.QUERY.on(db.db).findOrFail(1).roles();

        // Act
        int detached = roles.detach(Arrays.asList(1, 2));

        // Assert
        assertEquals(2, detached);
        assertEquals(Arrays.asList("deleting", "deleted", "deleting", "deleted"), new ArrayList<>(EVENTS));
        assertEquals(0, keyedCount("user_id = 1"));
        assertEquals(1, keyedCount("user_id = 2"));
    }

    @Test
    void plainPivotWritesFireNoEvents() {
        // Arrange
        BelongsToMany<TsRole> roles = TsUser.QUERY.on(db.db).findOrFail(3).roles();
        db.queryLog().clear();

        // Act
        roles.attach(2, attributes("fresh", 1));
        int detached = roles.detach(2);

        // Assert
        assertTrue(EVENTS.isEmpty());
        assertEquals(1, detached);
        List<String> writes = db.queryLog().writes();
        assertEquals(2, writes.size());
        assertTrue(writes.get(0).startsWith("insert into \"role_user\""));
        assertTrue(writes.get(1).startsWith("delete from \"role_user\""));
        assertTrue(db.queryLog().sql().isEmpty());
    }

    @Test
    void savedListenerInsideSyncRunsOnTransactionFactoryInStrictMode() {
        // Arrange
        BelongsToMany<TsRole> roles = EventUser.QUERY.on(db.db).findOrFail(2).roles();
        Map<Object, Object> target = new LinkedHashMap<>();
        target.put(2, attributes("fresh", 4));

        // Act
        SyncResult result = roles.sync(target);

        // Assert
        assertEquals(1, result.getAttached().size());
        assertEquals(1, result.getDetached().size());
        assertTrue(EVENTS.contains("saved"));
        assertTrue(EVENTS.contains("deleted"));
        assertEquals(0, keyedCount("user_id = 2 and role_id = 1"));
        assertEquals(1, keyedCount("user_id = 2 and role_id = 2 and status = 'fresh'"));
    }

    private int keyedCount(String condition) {
        Object count = db.real.scalar("select count(*) from role_user_keyed where " + condition).orElseThrow();
        return ((Number) count).intValue();
    }

    private static Map<String, Object> status(String value) {
        return Collections.<String, Object>singletonMap("status", value);
    }

    private static Map<String, Object> attributes(String status, int weight) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status);
        values.put("weight", weight);
        return values;
    }
}
