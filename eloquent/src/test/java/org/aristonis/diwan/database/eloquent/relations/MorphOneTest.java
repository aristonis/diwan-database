package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Image;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphOneTest {

    private static final String LAZY = "select * from \"morph_images\" where"
            + " \"morph_images\".\"imageable_type\" = ? and \"morph_images\".\"imageable_id\" = ?"
            + " and \"morph_images\".\"imageable_id\" is not null limit 1";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphModels.createTables(db);
        MorphModels.register();
        MorphModels.mapAliases();
        db.seed("morph_posts", Map.of("id", 1, "title", "a"));
        db.seed("morph_posts", Map.of("id", 2, "title", "b"));
        db.seed("morph_videos", Map.of("id", 1, "title", "v"));
        db.seed("morph_images", Map.of("id", 10, "url", "first", "imageable_type", "post", "imageable_id", 1));
        db.seed("morph_images", Map.of("id", 11, "url", "second", "imageable_type", "post", "imageable_id", 1));
        db.seed("morph_images", Map.of("id", 12, "url", "clip", "imageable_type", "video", "imageable_id", 1));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Post post(int id) {
        Post post = Post.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return post;
    }

    @Test
    void returnsTheFirstMatchingRow() {
        Post first = post(1);

        Image image = first.image().getResults();

        assertEquals(10, image.getKey());
        assertEquals(Collections.singletonList(LAZY), db.sql());
        assertEquals(Arrays.asList("post", 1), db.queries().get(0).bindings());
    }

    @Test
    void sameIdOnAnotherTypeGetsItsOwnRow() {
        Video video = Video.QUERY.on(db.db).findOrFail(1);

        assertEquals(12, video.image().getResults().getKey());
    }

    @Test
    void noRowIsNullWithoutADefault() {
        Post second = post(2);

        assertNull(second.image().getResults());
        assertEquals(Optional.empty(), second.image().first());
    }

    @Test
    void defaultCarriesTheIdAndTheType() {
        Post second = post(2);

        Image image = second.image().withDefault().getResults();

        assertFalse(image.exists());
        assertEquals("post", image.getAttribute("imageable_type"));
        assertEquals(2, ((Number) image.getAttribute("imageable_id")).intValue());
    }

    @Test
    void defaultAttributesAreFilled() {
        Post second = post(2);

        Image image = second.image().withDefault(Map.of("url", "none")).getResults();

        assertEquals("none", image.getAttribute("url"));
        assertEquals("post", image.getAttribute("imageable_type"));
    }

    @Test
    void defaultCallbackSeesTheParent() {
        Post second = post(2);

        Image image = second.image().withDefault((made, parent) -> made.setAttribute("url", "for-" + parent.getKey()))
                .getResults();

        assertEquals("for-2", image.getAttribute("url"));
    }

    @Test
    void eagerLoadTakesTheFirstRowPerParent() {
        db.clearLog();

        ModelCollection<Post> posts = Post.QUERY.on(db.db).with("image").get();

        assertEquals(2, db.sql().size());
        assertTrue(db.sql().get(1).contains("\"morph_images\".\"imageable_type\" = ?"));
        assertEquals(10, posts.get(0).<Image>related("image").getKey());
        assertNull(posts.get(1).getRelation("image"));
    }
}
