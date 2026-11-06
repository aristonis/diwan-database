package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.InvalidRelationException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.BrokenTouchRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TouchingUser;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The touches a belongsToMany relation runs after a pivot write, and its own {@code touch()}: which
 * rows get a new {@code updated_at}, which statements run, and what stays when a touch fails.
 * Each test opens its own in-memory database.
 */
class BelongsToManyTouchTest {

    private static final int LARGE_ID_COUNT = 1000;
    private static final int FIRST_LARGE_ID = 101;
    private static final int MAX_BINDINGS_PER_STATEMENT = 900;

    private BtmWriteDb db;

    /** A user whose related role touches the guessed inverse name {@code owners}. */
    static final class Owner extends Model {
        static final ModelQuery<Owner> QUERY = ModelQuery.of(Owner.class, Owner::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Owner.class)
                    .add("roles", Owner::roles)
                    .add("memberRoles", Owner::memberRoles);
        }

        public BelongsToMany<OwnedRole> roles() {
            return belongsToMany(OwnedRole.class, "role_user", "user_id", "role_id").withPivot("status", "weight");
        }

        public BelongsToMany<MemberRole> memberRoles() {
            return belongsToMany(MemberRole.class, "role_user", "user_id", "role_id").withPivot("status", "weight");
        }
    }

    /** A role that touches its {@code owners}, the guessed inverse of {@link Owner}. */
    static final class OwnedRole extends Model {
        static final ModelQuery<OwnedRole> QUERY = ModelQuery.of(OwnedRole.class, OwnedRole::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(OwnedRole.class).add("owners", OwnedRole::owners);
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("owners");
        }

        public BelongsToMany<Owner> owners() {
            return belongsToMany(Owner.class, "role_user", "role_id", "user_id");
        }
    }

    /** A role that touches {@code members}, which is not the guessed inverse of {@link Owner}. */
    static final class MemberRole extends Model {
        static final ModelQuery<MemberRole> QUERY = ModelQuery.of(MemberRole.class, MemberRole::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(MemberRole.class).add("members", MemberRole::members);
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("members");
        }

        public BelongsToMany<Owner> members() {
            return belongsToMany(Owner.class, "role_user", "role_id", "user_id");
        }
    }

    @BeforeEach
    void open() throws SQLException {
        db = BtmWriteDb.open();
        assertNotNull(Owner.QUERY);
        assertNotNull(OwnedRole.QUERY);
        assertNotNull(MemberRole.QUERY);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void attachWithTouchOffTouchesNothing() {
        // Arrange
        BelongsToMany<OwnedRole> roles = Owner.QUERY.on(db.db).findOrFail(4).roles();

        // Act
        roles.attach(1, attributes(), false);

        // Assert
        assertNull(updatedAt("users", 4));
        assertNull(updatedAt("roles", 1));
        assertEquals(1, linkCount("user_id = 4 and role_id = 1"));
    }

    @Test
    void detachOfNothingDoesNotTouch() {
        // Arrange
        BelongsToMany<OwnedRole> roles = Owner.QUERY.on(db.db).findOrFail(4).roles();
        db.queryLog().clear();

        // Act
        int detached = roles.detach(1);

        // Assert
        assertEquals(0, detached);
        assertEquals(1, db.queryLog().writes().size());
        assertNull(updatedAt("users", 4));
    }

    @Test
    void syncWithNoChangeDoesNotTouch() {
        // Arrange
        BelongsToMany<OwnedRole> roles = Owner.QUERY.on(db.db).findOrFail(1).roles();

        // Act
        SyncResult result = roles.sync(java.util.Arrays.asList(1, 2));

        // Assert
        assertTrue(result.isEmpty());
        assertNull(updatedAt("users", 1));
        assertNull(updatedAt("roles", 1));
    }

    @Test
    void touchUpdatesRelatedRowsOfParent() {
        // Arrange
        BelongsToMany<TsRole> roles = TsUser.QUERY.on(db.db).findOrFail(2).roles();
        db.queryLog().clear();

        // Act
        roles.touch();

        // Assert
        assertNotNull(updatedAt("roles", 1));
        assertNull(updatedAt("roles", 2));
        List<String> writes = db.queryLog().writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).startsWith("update \"roles\" set \"updated_at\" = ?"));
        assertTrue(writes.get(0).contains("in ("));
        assertTrue(anySelectOn("role_user"));
    }

    @Test
    void touchSkipsRelatedWithoutTimestamps() {
        // Arrange
        BelongsToMany<BtmModels.Role> roles = User.QUERY.on(db.db).findOrFail(1).roles();
        db.queryLog().clear();

        // Act
        roles.touch();

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
        assertTrue(db.queryLog().sql().isEmpty());
    }

    @Test
    void touchSkipsClassInWithoutTouchingScope() {
        // Arrange
        BelongsToMany<TsRole> roles = TsUser.QUERY.on(db.db).findOrFail(1).roles();
        db.queryLog().clear();

        // Act
        Model.withoutTouching(TsRole.class, () -> {
            roles.touch();
        });

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
        assertNull(updatedAt("roles", 1));
        assertNull(updatedAt("roles", 2));
    }

    @Test
    void touchChunksLargeIdLists() {
        // Arrange
        for (int i = 0; i < LARGE_ID_COUNT; i++) {
            int id = FIRST_LARGE_ID + i;
            db.real.insert("insert into roles (id, name) values (?, ?)", id, "bulk " + id);
            db.real.insert("insert into role_user (user_id, role_id, status, weight) values (3, ?, 'x', 1)", id);
        }
        BelongsToMany<TsRole> roles = TsUser.QUERY.on(db.db).findOrFail(3).roles();
        db.queryLog().clear();

        // Act
        roles.touch();

        // Assert
        List<String> writes = db.queryLog().writes();
        List<Integer> counts = db.queryLog().writeBindingCounts();
        assertTrue(writes.size() >= 2);
        for (int i = 0; i < writes.size(); i++) {
            assertTrue(writes.get(i).startsWith("update \"roles\""));
            assertTrue(counts.get(i) <= MAX_BINDINGS_PER_STATEMENT);
        }
        assertEquals(0, ((Number) db.real.scalar(
                "select count(*) from roles where id >= ? and updated_at is null", FIRST_LARGE_ID).orElseThrow())
                .intValue());
    }

    @Test
    void touchWithNoRelatedRowsRunsNoUpdate() {
        // Arrange
        BelongsToMany<TsRole> roles = TsUser.QUERY.on(db.db).findOrFail(4).roles();
        db.queryLog().clear();

        // Act
        roles.touch();

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(1, db.queryLog().sql().size());
    }

    @Test
    void touchesMayNameBelongsToManyRelation() {
        // Arrange
        BelongsToMany<?> roles = (BelongsToMany<?>) TouchingUser.QUERY.on(db.db).findOrFail(4).relation("roles");

        // Act
        roles.attach(1, attributes());

        // Assert
        assertNotNull(updatedAt("roles", 1));
        assertNull(updatedAt("roles", 2));
        assertEquals(1, linkCount("user_id = 4 and role_id = 1"));
    }

    @Test
    void attachTouchesParentWhenRelatedTouchesInverse() {
        // Arrange
        BelongsToMany<OwnedRole> roles = Owner.QUERY.on(db.db).findOrFail(4).roles();

        // Act
        roles.attach(1, attributes());

        // Assert
        assertNotNull(updatedAt("users", 4));
        assertNull(updatedAt("users", 1));
    }

    @Test
    void differentlyNamedInverseDoesNotTouchParent() {
        // Arrange
        BelongsToMany<MemberRole> roles = Owner.QUERY.on(db.db).findOrFail(4).memberRoles();

        // Act
        roles.attach(1, attributes());

        // Assert
        assertNull(updatedAt("users", 4));
        assertEquals(1, linkCount("user_id = 4 and role_id = 1"));
    }

    @Test
    void touchesNamingMissingRelationIsRefused() {
        // Arrange
        BelongsToMany<?> users = (BelongsToMany<?>) BrokenTouchRole.QUERY.on(db.db).findOrFail(1).relation("users");

        // Act and Assert
        assertThrows(InvalidRelationException.class, () -> users.attach(4, attributes()));
    }

    @Test
    void touchFailureLeavesLinkChangesCommitted() {
        // Arrange
        BelongsToMany<?> users = (BelongsToMany<?>) BrokenTouchRole.QUERY.on(db.db).findOrFail(2).relation("users");

        // Act
        assertThrows(InvalidRelationException.class, () -> users.attach(4, attributes()));

        // Assert
        assertEquals(1, linkCount("user_id = 4 and role_id = 2"));
    }

    private boolean anySelectOn(String table) {
        List<String> selects = new ArrayList<>(db.queryLog().sql());
        for (String text : selects) {
            if (text.contains("\"" + table + "\"")) {
                return true;
            }
        }
        return false;
    }

    private Object updatedAt(String table, int id) {
        return db.real.scalar("select updated_at from " + table + " where id = ?", id).orElse(null);
    }

    private int linkCount(String condition) {
        Object count = db.real.scalar("select count(*) from role_user where " + condition).orElseThrow();
        return ((Number) count).intValue();
    }

    private static Map<String, Object> attributes() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", "fresh");
        values.put("weight", 2);
        return values;
    }
}
