package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.ids;
import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The inverse side of a morph many-to-many: a tag reads the posts or the videos linked to it. */
class MorphedByManyTest {

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

    private Tag tag(int id) {
        return Tag.QUERY.on(db.db).findOrFail(id);
    }

    @Test
    void eachInverseSeesOnlyItsType() {
        Tag php = tag(1);
        Tag java = tag(2);
        Tag go = tag(3);

        assertEquals(ids(1, 2), sortedKeys(php.posts().get()));
        assertEquals(ids(1), sortedKeys(php.videos().get()));
        assertEquals(ids(1), sortedKeys(java.posts().get()));
        assertEquals(ids(2), sortedKeys(java.videos().get()));
        assertTrue(go.posts().get().isEmpty());
        assertEquals(ids(1), sortedKeys(go.videos().get()));
    }

    @Test
    void inverseBindsTheRelatedModelsMorphClass() {
        Tag php = tag(1);
        db.clearLog();

        php.posts().get();
        php.videos().get();

        assertEquals("post", db.queries().get(0).bindings().get(0));
        assertEquals("video", db.queries().get(1).bindings().get(0));
        assertTrue(db.sql().get(0).contains("\"taggables\".\"taggable_type\" = ?"), db.sql().get(0));
    }

    @Test
    void inverseReportsItsSide() {
        Tag php = tag(1);

        MorphToMany<Post> posts = php.posts();
        MorphToMany<Video> videos = php.videos();

        assertTrue(posts.isInverse());
        assertEquals("post", posts.getMorphClass());
        assertEquals("video", videos.getMorphClass());
        assertEquals("taggable_type", posts.getMorphType());
        assertEquals("taggables.taggable_type", posts.getQualifiedMorphTypeName());
    }

    @Test
    void inverseCarriesTheTypeOnThePivot() {
        Tag php = tag(1);

        ModelCollection<Video> videos = php.videos().get();

        Pivot pivot = (Pivot) videos.get(0).getRelation("pivot");
        assertInstanceOf(MorphPivot.class, pivot);
        assertEquals("video", pivot.getAttribute("taggable_type"));
    }

    @Test
    void inverseEagerLoadKeepsTheTypes() {
        db.clearLog();

        ModelCollection<Tag> tags = Tag.QUERY.on(db.db).with("posts", "videos").get();

        assertEquals(3, db.sql().size());
        assertEquals(ids(1, 2), sortedKeys(tags.get(0).<Post>relatedMany("posts")));
        assertEquals(ids(1), sortedKeys(tags.get(0).<Video>relatedMany("videos")));
        assertTrue(tags.get(2).<Post>relatedMany("posts").isEmpty());
        assertEquals(ids(1), sortedKeys(tags.get(2).<Video>relatedMany("videos")));
    }

    @Test
    void inverseOfAnUnmappedRelatedModelThrows() {
        Tag php = tag(1);
        Map<String, Class<? extends Model>> onlyTags = new LinkedHashMap<>();
        onlyTags.put("tag", Tag.class);
        MorphMap.resetForTests();
        MorphMap.enforceMorphMap(onlyTags);

        assertThrows(ClassMorphViolationException.class, () -> php.posts().get());
    }

    @Test
    void inverseWritesStayRefused() {
        Tag go = tag(3);

        assertThrows(UnsupportedOperationException.class, () -> go.posts().attach(1));
        assertFalse(db.sql().stream().anyMatch(sql -> sql.startsWith("insert")));
    }
}
