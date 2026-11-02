package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Profile;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WithDefaultTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("posts", Map.of("id", 1, "title", "orphan"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private long countProfiles() {
        return Profile.QUERY.on(db.db).query().count();
    }

    @Test
    void hasOneDefaultHasForeignKey() {
        User ann = User.QUERY.on(db.db).findOrFail(1);

        Profile profile = ann.profile().withDefault(Map.of("bio", "none")).getResults();

        assertFalse(profile.exists());
        assertEquals("none", profile.getAttribute("bio"));
        assertEquals(1L, ((Number) profile.getAttribute("user_id")).longValue());
        assertEquals(0L, countProfiles());
    }

    @Test
    void hasOneWithoutDefaultIsNull() {
        User ann = User.QUERY.on(db.db).findOrFail(1);

        assertNull(ann.profile().getResults());
    }

    @Test
    void belongsToDefaultIsBare() {
        Post orphan = Post.QUERY.on(db.db).findOrFail(1);

        User author = orphan.author().withDefault().getResults();

        assertFalse(author.exists());
        assertNull(author.getAttribute("id"));
        assertNull(author.getAttribute("name"));
    }

    @Test
    void belongsToDefaultFilledIgnoresKey() {
        Post orphan = Post.QUERY.on(db.db).findOrFail(1);

        User author = orphan.author().withDefault(Map.of("name", "Guest")).getResults();

        assertEquals("Guest", author.getAttribute("name"));
        assertNull(author.getAttribute("user_id"));
    }

    @Test
    void callbackReceivesDefaultAndParent() {
        Post orphan = Post.QUERY.on(db.db).findOrFail(1);
        Object[] seenParent = new Object[1];

        User author = orphan.author().withDefault((User user, Model parent) -> {
            seenParent[0] = parent;
            user.setAttribute("name", "From " + parent.getAttribute("title"));
        }).getResults();

        assertSame(orphan, seenParent[0]);
        assertEquals("From orphan", author.getAttribute("name"));
        assertFalse(author.exists());
    }

    @Test
    void hasOneCallbackSeesForeignKeySet() {
        User ann = User.QUERY.on(db.db).findOrFail(1);

        Profile profile = ann.profile().withDefault((Profile p, Model parent) ->
                p.setAttribute("bio", "for " + p.getAttribute("user_id"))).getResults();

        assertEquals("for 1", profile.getAttribute("bio"));
    }

    @Test
    void defaultIsNewOnEveryUse() {
        User ann = User.QUERY.on(db.db).findOrFail(1);
        HasOne<Profile> relation = ann.profile().withDefault();

        assertNotSame(relation.getResults(), relation.getResults());
    }
}
