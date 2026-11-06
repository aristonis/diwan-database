package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Arrays;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.exception.UnregisteredModelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The belongsToMany factories on a model: the defaults a null argument takes, the class overloads
 * that read a table and a pivot class from the registry, and the failures for an unregistered or
 * null class.
 */
class BelongsToManyFactoryTest {

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void nullArgumentsTakeTheDefaults() {
        BelongsToMany<BtmModels.Role> roles = user().rolesByDefaults();

        assertEquals("role_user", roles.getTable());
        assertEquals("user_id", roles.getForeignPivotKeyName());
        assertEquals("role_id", roles.getRelatedPivotKeyName());
        assertEquals("id", roles.getParentKeyName());
        assertEquals("id", roles.getRelatedKeyName());
    }

    @Test
    void customParentAndRelatedKeysJoinOnTheGivenColumns() {
        BelongsToMany<BtmModels.Language> languages = BtmModels.Country.QUERY.on(db.db).findOrFail("US").languages();
        ModelCollection<BtmModels.Language> loaded = languages.get();

        assertEquals("code", languages.getParentKeyName());
        assertEquals("iso", languages.getRelatedKeyName());
        assertEquals(1, loaded.size());
        assertEquals("en", loaded.get(0).getAttribute("iso"));
    }

    @Test
    void pivotClassOverloadUsesItsTableAndPivotClass() {
        BelongsToMany<BtmModels.Role> memberships = user().memberships();

        assertEquals("role_user", memberships.getTable());
        assertSame(BtmModels.Membership.class, memberships.getPivotClass());
    }

    @Test
    void plainModelClassOverloadUsesItsTableOnly() {
        BelongsToMany<BtmModels.Role> relation = user().rolesByPlainModelTable();

        assertEquals("permissions", relation.getTable());
        assertSame(Pivot.class, relation.getPivotClass());
    }

    @Test
    void unregisteredTableClassThrowsNamingTheClass() {
        User user = user();

        UnregisteredModelException thrown = assertThrows(UnregisteredModelException.class, user::unregisteredTable);

        assertEquals(BtmModels.Unregistered.class.getName(), thrown.getModelClassName());
    }

    @Test
    void unregisteredRelatedClassThrowsUnregisteredModelException() {
        User user = user();

        assertThrows(UnregisteredModelException.class, user::unregisteredRelated);
    }

    @Test
    void nullRelatedClassThrowsNullPointerException() {
        User user = user();

        assertThrows(NullPointerException.class, user::nullRelated);
    }

    @Test
    void nullTableClassThrowsNullPointerException() {
        User user = user();

        assertThrows(NullPointerException.class, user::nullTableClass);
    }

    @Test
    void relationNameComesFromTheRelationMap() {
        Relation<?, ?> relation = user().relation("roles");

        assertEquals("roles", relation.getRelationName());
    }

    @Test
    void explicitRelationNameWinsOverTheRelationMap() {
        Relation<?, ?> relation = user().relation("labelled");

        assertEquals("explicit", relation.getRelationName());
    }

    @Test
    void gettersQualifyTheJoinAndReadTheAccessor() {
        BelongsToMany<BtmModels.Role> roles = user().rolesWithPivot();

        assertEquals("role_user", roles.getTable());
        assertEquals("role_user.user_id", roles.getQualifiedForeignPivotKeyName());
        assertEquals("role_user.role_id", roles.getQualifiedRelatedPivotKeyName());
        assertEquals("users.id", roles.getQualifiedParentKeyName());
        assertEquals("roles.id", roles.getQualifiedRelatedKeyName());
        assertEquals("pivot", roles.getPivotAccessor());
    }

    @Test
    void pivotColumnsListTheWithPivotAndTimestampColumns() {
        BelongsToMany<BtmModels.Role> roles = user().rolesWithPivot();

        assertEquals(Arrays.asList("status", "weight", "created_at", "updated_at"), roles.getPivotColumns());
    }

    @Test
    void pivotColumnsCannotBeModifiedByTheCaller() {
        BelongsToMany<BtmModels.Role> roles = user().rolesWithPivot();

        assertThrows(UnsupportedOperationException.class, () -> roles.getPivotColumns().add("extra"));
    }

    private User user() {
        return User.QUERY.on(db.db).findOrFail(1);
    }

    /** A user whose relations exercise each factory overload the tests name. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class)
                    .add("roles", User::rolesByDefaults)
                    .add("labelled", User::namedRoles);
        }

        public BelongsToMany<BtmModels.Role> rolesByDefaults() {
            return belongsToMany(BtmModels.Role.class, "role_user", null, null);
        }

        public BelongsToMany<BtmModels.Role> rolesWithPivot() {
            return belongsToMany(BtmModels.Role.class, "role_user", null, null)
                    .withPivot("status", "weight")
                    .withTimestamps();
        }

        public BelongsToMany<BtmModels.Role> memberships() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Membership.class, "user_id", "role_id");
        }

        public BelongsToMany<BtmModels.Role> rolesByPlainModelTable() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Permission.class);
        }

        public BelongsToMany<BtmModels.Role> unregisteredTable() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Unregistered.class);
        }

        public BelongsToMany<BtmModels.Unregistered> unregisteredRelated() {
            return belongsToMany(BtmModels.Unregistered.class);
        }

        public BelongsToMany<BtmModels.Role> nullRelated() {
            return belongsToMany((Class<BtmModels.Role>) null);
        }

        public BelongsToMany<BtmModels.Role> nullTableClass() {
            return belongsToMany(BtmModels.Role.class, (Class<? extends Model>) null);
        }

        public BelongsToMany<BtmModels.Role> namedRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", null, null, null, null, "explicit");
        }
    }
}
