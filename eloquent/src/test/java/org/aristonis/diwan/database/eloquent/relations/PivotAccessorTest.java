package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.exception.UnregisteredModelException;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.Membership;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.Role;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pivot accessor name and the pivot class of a belongsToMany relation, and where each
 * accessor puts the pivot on the related models.
 */
class PivotAccessorTest {

    private static final String DEFAULT_ACCESSOR = "pivot";

    /** A pivot class with no registry entry, so {@code using} must refuse it. */
    static final class NoEntryPivot extends Pivot {
    }

    /** A parent whose roles are stored under the permissions accessor. */
    static final class Holder extends Model {
        static final ModelQuery<Holder> QUERY = ModelQuery.of(Holder.class, Holder::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Holder.class).add("roles", Holder::roles);
        }

        public BelongsToMany<Role> roles() {
            return belongsToMany(Role.class, "role_user", "user_id", "role_id").as("permissions");
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
    void defaultAccessorIsPivot() {
        assertEquals(DEFAULT_ACCESSOR, user.roles().getPivotAccessor());
    }

    @Test
    void defaultClass() {
        assertEquals(Pivot.class, user.roles().getPivotClass());
    }

    @Test
    void usingClassIsNullWithoutUsing() {
        assertNull(user.roles().usingClass());
    }

    @Test
    void usingSetsThePivotClass() {
        BelongsToMany<Role> roles = user.roles().using(Membership.class);

        assertEquals(Membership.class, roles.getPivotClass());
        assertSame(Membership.class, roles.usingClass());
    }

    @Test
    void unregisteredUsing() {
        assertThrows(UnregisteredModelException.class, () -> user.friends().using(NoEntryPivot.class));
    }

    @Test
    void renamed() {
        assertEquals("membership", user.rolesAs().getPivotAccessor());

        ModelCollection<Role> roles = user.rolesAs().get();

        assertFalse(roles.isEmpty());
        for (Role role : roles) {
            assertTrue(role.relationLoaded("membership"));
            assertFalse(role.relationLoaded(DEFAULT_ACCESSOR));
        }
    }

    @Test
    void overwritesSameName() {
        Holder holder = Holder.QUERY.on(db.db).with("roles").get().get(0);
        ModelCollection<Role> roles = holder.relatedMany("roles");

        assertFalse(roles.isEmpty());
        for (Role role : roles) {
            assertTrue(role.getRelation("permissions") instanceof Pivot);
        }
    }

    @Test
    void setAfterRead() {
        BelongsToMany<Role> earlier = user.roles();
        ModelCollection<Role> before = earlier.get();

        earlier.as("membership");

        assertFalse(before.isEmpty());
        for (Role role : before) {
            assertTrue(role.relationLoaded(DEFAULT_ACCESSOR));
            assertFalse(role.relationLoaded("membership"));
        }
        ModelCollection<Role> after = user.roles().as("membership").get();
        for (Role role : after) {
            assertTrue(role.relationLoaded("membership"));
        }
    }

    @Test
    void emptyAccessorRejected() {
        assertThrows(IllegalArgumentException.class, () -> user.roles().as(""));
    }

    @Test
    void nullAccessorRejected() {
        assertThrows(NullPointerException.class, () -> user.roles().as(null));
    }
}
