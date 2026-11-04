package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that the belongsToMany fixtures seed the expected rows and that each relation
 * reads them. Every later belongsToMany test class relies on these fixtures.
 */
class BtmModelsTest {

    private static final int SEEDED_USER_COUNT = 5;
    private static final int ROLES_PER_SEEDED_USER = 2;
    private static final int FIXTURE_USER_TOTAL = 4;

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
    void modelsRegisterWithoutError() {
        BtmModels.register();
    }

    @Test
    void userRolesReadSeededPivotRows() {
        BtmModels.User user1 = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        BtmModels.User user2 = BtmModels.User.QUERY.on(db.db).findOrFail(2);
        BtmModels.User user3 = BtmModels.User.QUERY.on(db.db).findOrFail(3);

        assertEquals(2, user1.roles().get().size());
        assertEquals(1, user2.roles().get().size());
        assertEquals(0, user3.roles().get().size());
    }

    @Test
    void roleNamesComeFromRoleRows() {
        BtmModels.Role role1 = BtmModels.Role.QUERY.on(db.db).findOrFail(1);
        BtmModels.Role role2 = BtmModels.Role.QUERY.on(db.db).findOrFail(2);

        assertEquals("admin", role1.getAttribute("name"));
        assertEquals("editor", role2.getAttribute("name"));
    }

    @Test
    void userFriendsReadUserUserRows() {
        BtmModels.User user1 = BtmModels.User.QUERY.on(db.db).findOrFail(1);

        assertEquals(1, user1.friends().get().size());
    }

    @Test
    void membershipsAndDefaultAndAliasedRolesReadRoleUserRows() {
        BtmModels.User user1 = BtmModels.User.QUERY.on(db.db).findOrFail(1);

        assertEquals(2, user1.memberships().get().size());
        assertEquals(2, user1.rolesByDefault().get().size());
        assertEquals(2, user1.rolesAs().get().size());
    }

    @Test
    void roleReadsItsPermissions() {
        BtmModels.Role role1 = BtmModels.Role.QUERY.on(db.db).findOrFail(1);
        BtmModels.Role role2 = BtmModels.Role.QUERY.on(db.db).findOrFail(2);

        assertEquals(2, role1.permissions().get().size());
        assertEquals(1, role2.permissions().get().size());
    }

    @Test
    void postReadsItsTagsThroughPostTag() {
        BtmModels.Post post1 = BtmModels.Post.QUERY.on(db.db).findOrFail(1);
        BtmModels.Post post2 = BtmModels.Post.QUERY.on(db.db).findOrFail(2);

        assertEquals(2, post1.tags().get().size());
        assertEquals(1, post2.tags().get().size());
    }

    @Test
    void blogPostReadsItsTagsThroughBlogPostTag() {
        BtmModels.BlogPost blogPost1 = BtmModels.BlogPost.QUERY.on(db.db).findOrFail(1);

        assertEquals(1, blogPost1.tags().get().size());
    }

    @Test
    void countryReadsLanguagesWithCustomKeys() {
        BtmModels.Country us = BtmModels.Country.QUERY.on(db.db).findOrFail("US");

        assertEquals(1, us.languages().get().size());
        assertEquals("English", us.languages().get().get(0).getAttribute("name"));
    }

    @Test
    void membershipModelBuildsItsUserAndRoleRelations() {
        BtmModels.Membership membership = BtmModels.Membership.QUERY.on(db.db).newModel();

        assertNotNull(membership.user());
        assertNotNull(membership.role());
    }

    @Test
    void userFourExistsWithoutRoles() {
        BtmModels.User user4 = BtmModels.User.QUERY.on(db.db).findOrFail(4);

        assertEquals("User 4", user4.getAttribute("name"));
        assertEquals(0, user4.roles().get().size());
    }

    @Test
    void loggedFactoryRecordsSelects() {
        db.clearLog();

        BtmModels.User.QUERY.on(db.db).findOrFail(1);

        assertFalse(db.sql().isEmpty());
        assertTrue(db.sql().get(0).contains("\"users\""), db.sql().get(0));
    }

    @Test
    void seedManyAddsUsersWithRoles() {
        db.seedMany(SEEDED_USER_COUNT, ROLES_PER_SEEDED_USER);

        int lastUserId = FIXTURE_USER_TOTAL + SEEDED_USER_COUNT;
        assertEquals(lastUserId, BtmModels.User.QUERY.on(db.db).all().size());
        BtmModels.User lastSeeded = BtmModels.User.QUERY.on(db.db).findOrFail(lastUserId);
        assertEquals(ROLES_PER_SEEDED_USER, lastSeeded.roles().get().size());
    }
}
