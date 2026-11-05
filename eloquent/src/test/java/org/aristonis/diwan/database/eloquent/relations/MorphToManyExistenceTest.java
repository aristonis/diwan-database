package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.ids;
import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** has, whereHas, doesntHave and withCount on a morphToMany relation count only rows of the parent's type. */
class MorphToManyExistenceTest {

    private static final String TYPE = "\"taggables\".\"taggable_type\" = ?";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToManyModels.register();
        MorphToManyModels.mapAliases();
        MorphToManyModels.createAndSeed(db);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<Post> posts() {
        return Post.QUERY.on(db.db).query();
    }

    private EloquentBuilder<Tag> tags() {
        return Tag.QUERY.on(db.db).query();
    }

    @Test
    void existenceFiltersByType() {
        EloquentBuilder<Post> has = posts().has("tags");
        EloquentBuilder<Post> named = posts().whereHas("tags", q -> q.where("name", "java"));
        EloquentBuilder<Post> none = posts().doesntHave("tags");

        assertTrue(has.toSql().contains(TYPE), has.toSql());
        assertEquals(ids(1, 2), sortedKeys(has.get()));
        assertEquals(ids(1), sortedKeys(named.get()));
        assertEquals(ids(3), sortedKeys(none.get()));
        assertEquals("post", has.getBindings().get(0));
        assertEquals("post", named.getBindings().get(0));
    }

    @Test
    void whereHasOnAVideoOnlyTagDoesNotSelectThePostWithTheSameId() {
        ModelCollection<Post> found = posts().whereHas("tags", q -> q.where("name", "go")).get();

        assertTrue(found.isEmpty());
    }

    @Test
    void withCountFiltersByType() {
        EloquentBuilder<Post> query = posts().withCount("tags");

        ModelCollection<Post> found = query.get();

        assertTrue(query.toSql().contains(TYPE), query.toSql());
        assertEquals(2, ((Number) found.get(0).getAttribute("tags_count")).intValue());
        assertEquals(1, ((Number) found.get(1).getAttribute("tags_count")).intValue());
        assertEquals(0, ((Number) found.get(2).getAttribute("tags_count")).intValue());
    }

    @Test
    void inverseExistenceFiltersByTheRelatedType() {
        ModelCollection<Tag> withPosts = tags().has("posts").get();
        ModelCollection<Tag> withoutPosts = tags().doesntHave("posts").get();
        ModelCollection<Tag> counted = tags().withCount("videos").get();

        assertEquals(ids(1, 2), sortedKeys(withPosts));
        assertEquals(ids(3), sortedKeys(withoutPosts));
        assertEquals(1, ((Number) counted.get(0).getAttribute("videos_count")).intValue());
        assertEquals(1, ((Number) counted.get(1).getAttribute("videos_count")).intValue());
        assertEquals(1, ((Number) counted.get(2).getAttribute("videos_count")).intValue());
    }

    @Test
    void selfReferenceExistence() {
        EloquentBuilder<Tag> named = tags().whereHas("related", q -> q.where("name", "java"));
        EloquentBuilder<Tag> any = tags().has("related");
        EloquentBuilder<Tag> none = tags().doesntHave("related");

        assertTrue(named.toSql().contains(TYPE), named.toSql());
        assertEquals("tag", named.getBindings().get(0));
        assertEquals(ids(1), sortedKeys(named.get()));
        assertEquals(ids(1), sortedKeys(any.get()));
        assertEquals(ids(2, 3), sortedKeys(none.get()));
    }

    @Test
    void selfReferenceCountsOnlyTagTypedLinks() {
        ModelCollection<Tag> counted = tags().withCount("related").get();

        assertEquals(1, ((Number) counted.get(0).getAttribute("related_count")).intValue());
        assertEquals(0, ((Number) counted.get(1).getAttribute("related_count")).intValue());
        assertEquals(0, ((Number) counted.get(2).getAttribute("related_count")).intValue());
    }
}
