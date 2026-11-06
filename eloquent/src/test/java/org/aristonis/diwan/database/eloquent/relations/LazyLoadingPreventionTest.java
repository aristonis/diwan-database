package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.LazyLoadingViolationException;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Profile;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code ModelQuery.preventLazyLoading}. */
class LazyLoadingPreventionTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("profiles", Map.of("id", 7, "user_id", 1, "bio", "hi"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "p1"));
    }

    @AfterEach
    void close() throws SQLException {
        ModelQuery.preventLazyLoading(false);
        db.close();
    }

    @Test
    void switchIsOffByDefaultAndReadsBack() {
        assertFalse(ModelQuery.preventsLazyLoading());

        ModelQuery.preventLazyLoading(true);

        assertTrue(ModelQuery.preventsLazyLoading());
    }

    @Test
    void multiRowModelBlocked() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();
        db.clearLog();

        LazyLoadingViolationException e = assertThrows(LazyLoadingViolationException.class,
                () -> users.get(0).related("profile"));

        assertEquals(User.class, e.getModelClass());
        assertEquals("profile", e.getRelation());
        assertEquals(Collections.emptyList(), db.sql());
        assertFalse(users.get(0).relationLoaded("profile"));
    }

    @Test
    void unknownRelationStillReportsNotFound() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();

        assertThrows(RelationNotFoundException.class, () -> users.get(0).getRelationValue("nope"));
    }

    @Test
    void switchOffAtReadTimeAllowsMarkedModel() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();
        ModelQuery.preventLazyLoading(false);

        Profile profile = users.get(0).related("profile");

        assertEquals(7, profile.getKey());
    }

    @Test
    void multiRowWithSwitchOffNotMarked() {
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();
        ModelQuery.preventLazyLoading(true);

        Profile profile = users.get(0).related("profile");

        assertNotNull(profile);
    }

    @Test
    void singleRowAndNewAllowed() {
        ModelQuery.preventLazyLoading(true);
        User found = User.QUERY.on(db.db).findOrFail(1);
        User first = User.QUERY.on(db.db).query().where("id", 1).get().get(0);
        User fresh = User.QUERY.on(db.db).newModel();
        User created = User.QUERY.on(db.db).newModel();
        created.setAttribute("name", "Cid");
        assertTrue(created.save());

        assertEquals(7, found.<Profile>related("profile").getKey());
        assertEquals(7, first.<Profile>related("profile").getKey());
        assertEquals(0, fresh.relatedMany("posts").size());
        assertTrue(created.wasRecentlyCreated());
        assertEquals(0, created.relatedMany("posts").size());
    }

    @Test
    void eagerLoadedNotBlocked() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<User> users = User.QUERY.on(db.db).with("profile").get();
        db.clearLog();

        Profile profile = users.get(0).related("profile");

        assertEquals(7, profile.getKey());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void explicitQueryAllowed() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();

        assertEquals(1, users.get(0).posts().get().size());
        assertEquals(7, users.get(0).profile().getResults().getKey());
    }

    @Test
    void loadAndLoadMissingAllowed() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();

        users.get(0).load("posts");
        users.get(1).loadMissing("profile");

        assertEquals(1, users.get(0).relatedMany("posts").size());
        assertTrue(users.get(1).relationLoaded("profile"));
    }

    @Test
    void lazyPagesAreMarked() {
        ModelQuery.preventLazyLoading(true);
        List<User> paged = new ArrayList<>();
        User.QUERY.on(db.db).query().lazy(2).forEach(paged::add);
        db.clearLog();

        LazyLoadingViolationException e = assertThrows(LazyLoadingViolationException.class,
                () -> paged.get(0).related("profile"));

        assertEquals(2, paged.size());
        assertEquals("profile", e.getRelation());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void lazySinglePagesNotMarked() {
        ModelQuery.preventLazyLoading(true);
        List<User> paged = new ArrayList<>();
        User.QUERY.on(db.db).query().lazy(1).forEach(paged::add);

        Profile profile = paged.get(0).related("profile");

        assertEquals(2, paged.size());
        assertEquals(7, profile.getKey());
    }
}
