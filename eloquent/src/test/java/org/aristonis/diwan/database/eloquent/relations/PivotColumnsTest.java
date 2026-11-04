package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.Role;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pivot columns, the timestamp columns and the table and key getters of a belongsToMany
 * relation.
 */
class PivotColumnsTest {

    private static final List<String> ROLE_COLUMNS =
            Arrays.asList("status", "weight", "created_at", "updated_at");

    /** A parent whose timestamp columns are named by the model, not the defaults. */
    static final class RenamedClock extends Model {
        static final ModelQuery<RenamedClock> QUERY = ModelQuery.of(RenamedClock.class, RenamedClock::new);

        @Override
        protected String table() {
            return "events";
        }

        @Override
        protected String createdAtColumn() {
            return "made";
        }

        @Override
        protected String updatedAtColumn() {
            return "changed";
        }

        public BelongsToMany<Role> attendees() {
            return belongsToMany(Role.class, "event_role", "event_id", "role_id");
        }
    }

    /** A parent that keeps no creation timestamp column. */
    static final class NoCreatedClock extends Model {
        static final ModelQuery<NoCreatedClock> QUERY = ModelQuery.of(NoCreatedClock.class, NoCreatedClock::new);

        @Override
        protected String table() {
            return "ledgers";
        }

        @Override
        protected String createdAtColumn() {
            return null;
        }

        @Override
        protected String updatedAtColumn() {
            return "changed";
        }

        public BelongsToMany<Role> attendees() {
            return belongsToMany(Role.class, "ledger_role", "ledger_id", "role_id");
        }
    }

    private BtmDb db;
    private User user;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
        user = User.QUERY.on(db.db).findOrFail(1);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void accumulates() {
        BelongsToMany<User> friends = user.friends().withPivot("status").withPivot("weight");

        assertEquals(Arrays.asList("status", "weight"), friends.getPivotColumns());
    }

    @Test
    void unique() {
        BelongsToMany<User> friends = user.friends().withPivot("status", "status");

        assertEquals(Collections.singletonList("status"), friends.getPivotColumns());
    }

    @Test
    void emptyNoop() {
        BelongsToMany<Role> roles = user.roles().withPivot();

        assertEquals(ROLE_COLUMNS, roles.getPivotColumns());
    }

    @Test
    void timestampsDefault() {
        BelongsToMany<User> friends = user.friends().withTimestamps();

        assertEquals("created_at", friends.createdAt());
        assertEquals("updated_at", friends.updatedAt());
        assertEquals(Arrays.asList("created_at", "updated_at"), friends.getPivotColumns());
    }

    @Test
    void parentNamesWin() {
        BelongsToMany<Role> attendees = RenamedClock.QUERY.on(db.db).newModel().attendees().withTimestamps();

        assertEquals("made", attendees.createdAt());
        assertEquals("changed", attendees.updatedAt());
        assertEquals(Arrays.asList("made", "changed"), attendees.getPivotColumns());
    }

    @Test
    void customCreated() {
        BelongsToMany<User> friends = user.friends().withTimestamps("joined_at", null);

        assertEquals("joined_at", friends.createdAt());
        assertEquals("updated_at", friends.updatedAt());
        assertEquals(Arrays.asList("joined_at", "updated_at"), friends.getPivotColumns());
    }

    @Test
    void emptyStringDisables() {
        BelongsToMany<User> friends = user.friends().withTimestamps("", null);

        assertNull(friends.createdAt());
        assertEquals("updated_at", friends.updatedAt());
        assertEquals(Collections.singletonList("updated_at"), friends.getPivotColumns());
    }

    @Test
    void bothDisabled() {
        BelongsToMany<User> friends = user.friends().withTimestamps("", "");

        assertNull(friends.createdAt());
        assertNull(friends.updatedAt());
        assertEquals(Collections.emptyList(), friends.getPivotColumns());
    }

    @Test
    void parentWithoutTimestamps() {
        BelongsToMany<Role> attendees = NoCreatedClock.QUERY.on(db.db).newModel().attendees().withTimestamps();

        assertNull(attendees.createdAt());
        assertEquals("changed", attendees.updatedAt());
        assertEquals(Collections.singletonList("changed"), attendees.getPivotColumns());
    }

    @Test
    void keyNamesAndQualifiedNames() {
        BelongsToMany<Role> roles = user.roles();

        assertEquals("role_user", roles.getTable());
        assertEquals("user_id", roles.getForeignPivotKeyName());
        assertEquals("role_user.user_id", roles.getQualifiedForeignPivotKeyName());
        assertEquals("role_id", roles.getRelatedPivotKeyName());
        assertEquals("role_user.role_id", roles.getQualifiedRelatedPivotKeyName());
        assertEquals("id", roles.getParentKeyName());
        assertEquals("users.id", roles.getQualifiedParentKeyName());
        assertEquals("id", roles.getRelatedKeyName());
        assertEquals("roles.id", roles.getQualifiedRelatedKeyName());
    }

    @Test
    void aliasedColumnsUniqueInOrder() {
        BelongsToMany<User> friends = user.friends().withPivot("status", "user_id", "status");

        assertEquals(
                Arrays.asList(
                        "user_user.user_id as pivot_user_id",
                        "user_user.friend_id as pivot_friend_id",
                        "user_user.status as pivot_status"),
                Arrays.asList(friends.aliasedPivotColumns()));
    }

    @Test
    void pivotColumnsAreACopy() {
        BelongsToMany<Role> roles = user.roles();
        List<String> before = roles.getPivotColumns();

        roles.withPivot("extra");

        assertEquals(ROLE_COLUMNS, before);
        assertThrows(UnsupportedOperationException.class, () -> before.add("more"));
    }

    @Test
    void dottedPivotColumnRefusedWithoutRecording() {
        BelongsToMany<User> friends = user.friends();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> friends.withPivot("status", "user_user.weight"));

        assertTrue(error.getMessage().contains("[user_user.weight]"), error.getMessage());
        assertTrue(error.getMessage().contains("relation [user_user]"), error.getMessage());
        assertEquals(Collections.emptyList(), friends.getPivotColumns());
    }

    @Test
    void dottedTimestampNameRefusedWithoutChangingTimestamps() {
        BelongsToMany<User> friends = user.friends();

        assertThrows(IllegalArgumentException.class, () -> friends.withTimestamps("user_user.joined", null));

        assertNull(friends.createdAt());
        assertNull(friends.updatedAt());
        assertEquals(Collections.emptyList(), friends.getPivotColumns());
    }
}
