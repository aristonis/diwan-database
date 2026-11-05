package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.ids;
import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Eager loading of a morphToMany relation: one query, the type bound, rows matched to their parent. */
class MorphToManyEagerTest {

    private RelationDb db;

    private void open(boolean textOwnerIds) throws SQLException {
        db = new RelationDb();
        MorphToManyModels.register();
        MorphToManyModels.mapAliases();
        MorphToManyModels.createAndSeed(db, textOwnerIds);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void eagerLoadsByTypeAndMatches() throws SQLException {
        open(false);
        db.clearLog();

        ModelCollection<Post> posts = Post.QUERY.on(db.db).with("tags").get();

        assertEquals(2, db.sql().size());
        String eager = db.sql().get(1);
        assertTrue(eager.contains("\"taggables\".\"taggable_type\" = ?"), eager);
        assertEquals(java.util.Collections.singletonList("post"), db.queries().get(1).bindings());
        assertEquals(ids(1, 2), sortedKeys(posts.get(0).<Tag>relatedMany("tags")));
        assertEquals(ids(1), sortedKeys(posts.get(1).<Tag>relatedMany("tags")));
        assertTrue(posts.get(2).relationLoaded("tags"));
        assertTrue(posts.get(2).<Tag>relatedMany("tags").isEmpty());
    }

    @Test
    void eagerLoadOfVideosDoesNotPickUpPostRows() throws SQLException {
        open(false);
        db.clearLog();

        ModelCollection<Video> videos = Video.QUERY.on(db.db).with("tags").get();

        assertEquals(2, db.sql().size());
        assertEquals(java.util.Collections.singletonList("video"), db.queries().get(1).bindings());
        assertEquals(ids(1, 3), sortedKeys(videos.get(0).<Tag>relatedMany("tags")));
        assertEquals(ids(2), sortedKeys(videos.get(1).<Tag>relatedMany("tags")));
    }

    @Test
    void eagerLoadCarriesThePivotType() throws SQLException {
        open(false);

        ModelCollection<Post> posts = Post.QUERY.on(db.db).with("tags").get();

        Tag first = posts.get(0).<Tag>relatedMany("tags").get(0);
        Pivot pivot = (Pivot) first.getRelation("pivot");
        assertEquals("post", pivot.getAttribute("taggable_type"));
    }

    @Test
    void loadOnAnExistingCollectionKeepsTheType() throws SQLException {
        open(false);
        ModelCollection<Post> posts = Post.QUERY.on(db.db).all();
        db.clearLog();

        posts.load("tags");

        assertEquals(1, db.sql().size());
        assertEquals(ids(1, 2), sortedKeys(posts.get(0).<Tag>relatedMany("tags")));
    }

    @Test
    void matchesAcrossKeyTypes() throws SQLException {
        open(true);

        ModelCollection<Post> posts = Post.QUERY.on(db.db).with("tags").get();

        assertEquals(ids(1, 2), sortedKeys(posts.get(0).<Tag>relatedMany("tags")));
        assertEquals(ids(1), sortedKeys(posts.get(1).<Tag>relatedMany("tags")));
        assertTrue(posts.get(2).<Tag>relatedMany("tags").isEmpty());
    }

    @Test
    void lazyReadMatchesAcrossKeyTypes() throws SQLException {
        open(true);

        Post first = Post.QUERY.on(db.db).findOrFail(1);

        assertEquals(ids(1, 2), sortedKeys(first.tags().get()));
    }
}
