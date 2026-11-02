package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Profile;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HasOneTest {

    private static final String LAZY = "select * from \"profiles\" where \"profiles\".\"user_id\" = ?"
            + " and \"profiles\".\"user_id\" is not null limit 1";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("profiles", Map.of("id", 7, "user_id", 1, "bio", "hi"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(int id) {
        User user = User.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return user;
    }

    @Test
    void lazyQueryReadsTheFirstRow() {
        User ann = user(1);

        Profile profile = ann.profile().getResults();

        assertEquals(7, profile.getKey());
        assertEquals(Collections.singletonList(LAZY), db.sql());
        assertEquals(Collections.singletonList(1), db.queries().get(0).bindings());
    }

    @Test
    void noRowGivesNull() {
        assertNull(user(2).profile().getResults());
    }

    @Test
    void firstReturnsAnOptional() {
        Optional<Profile> found = user(1).profile().first();
        Optional<Profile> missing = user(2).profile().first();

        assertTrue(found.isPresent());
        assertEquals("hi", found.get().getAttribute("bio"));
        assertFalse(missing.isPresent());
    }

    @Test
    void nullParentKeyRunsNoQuery() {
        User unsaved = User.QUERY.on(db.db).newModel();
        db.clearLog();

        assertNull(unsaved.profile().getResults());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void firstChildWinsOnDuplicates() {
        db.seed("profiles", Map.of("id", 8, "user_id", 1, "bio", "second"));
        List<User> users = User.QUERY.on(db.db).all();
        db.clearLog();

        new RelationAccessImpl().load(users, Collections.singletonMap("profile", q -> {
        }));

        assertEquals(1, db.sql().size());
        assertEquals(7, users.get(0).<Profile>related("profile").getKey());
        assertNull(users.get(1).related("profile"));
    }
}
