package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.linkCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.EventTaggablePivot;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.TaggablePivot;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pivot writes of a morphToMany relation declared with a {@code using} MorphPivot class: the pivots
 * are saved and deleted as models with the relation's type, their events fire, and a different type
 * is refused on every path. Each test opens its own seeded in-memory database.
 */
class MorphToManyCustomPivotTest {

    private static final int ALL_LINKS = 8;

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToManyModels.register();
        MorphToManyModels.mapAliases();
        MorphToManyModels.createAndSeed(db);
        EventTaggablePivot.EVENTS.clear();
    }

    @AfterEach
    void close() throws SQLException {
        EventTaggablePivot.EVENTS.clear();
        db.close();
    }

    @Test
    void customPivotWritesAndEvents() {
        // Arrange: tag 3 is linked to video 1 but not to post 1
        MorphToMany<Tag> tags = post(1).tags().using(EventTaggablePivot.class);

        // Act
        tags.attach(3);

        // Assert: saved through the pivot, with the type
        assertEquals(Arrays.asList("creating", "created"), new ArrayList<>(EventTaggablePivot.EVENTS));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + 1, linkCount(db, "1 = 1"));

        // Act
        EventTaggablePivot.EVENTS.clear();
        int detached = tags.detach(3);

        // Assert: only the post row goes, the video row with the same ids stays
        assertEquals(1, detached);
        assertEquals(Arrays.asList("deleting", "deleted"), new ArrayList<>(EventTaggablePivot.EVENTS));
        assertEquals(0, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video' and weight = 9"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void customPivotAttachWithOtherTypeFiresNoEvent() {
        // Arrange
        MorphToMany<Tag> tags = post(1).tags().using(EventTaggablePivot.class);

        // Act
        assertThrows(IllegalArgumentException.class, () -> tags.attach(3, attrs("taggable_type", "video")));

        // Assert
        assertTrue(EventTaggablePivot.EVENTS.isEmpty());
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void createManyWithCustomPivotRollsBackWhenALaterRowIsRefused() {
        // Arrange
        MorphToMany<Tag> tags = post(3).tags().using(TaggablePivot.class);
        List<Map<String, Object>> records = Arrays.asList(attrs("name", "rust"), attrs("name", "zig"));
        List<Map<String, Object>> pivots = Arrays.asList(attrs("weight", 1), attrs("taggable_type", "video"));

        // Act
        assertThrows(IllegalArgumentException.class, () -> tags.createMany(records, pivots));

        // Assert: the first tag and its link are gone with the second
        assertEquals(3, ((Number) db.real.scalar("select count(*) from mm_tags").orElseThrow()).intValue());
        assertEquals(0, linkCount(db, "taggable_id = 3 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void updateExistingPivotWithOtherTypeRefusedOnCustomPivot() {
        // Arrange
        MorphToMany<Tag> tags = post(1).tags().using(TaggablePivot.class);

        // Act
        assertThrows(IllegalArgumentException.class,
                () -> tags.updateExistingPivot(1, attrs("taggable_type", "video")));
        int updated = tags.updateExistingPivot(1, attrs("weight", 50));

        // Assert
        assertEquals(1, updated);
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 50"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video' and weight = 3"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void loadedPivotCannotChangeItsType() {
        // Arrange
        Tag php = tagWithPivot(post(1).tags().withPivot("weight"), 1);
        Pivot pivot = (Pivot) php.getRelation("pivot");
        pivot.setAttribute("taggable_type", "video");

        // Act
        assertThrows(IllegalArgumentException.class, pivot::save);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 5"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video' and weight = 3"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void loadedCustomPivotCannotChangeItsType() {
        // Arrange
        Tag php = tagWithPivot(post(1).tags().using(TaggablePivot.class).withPivot("weight"), 1);
        Pivot pivot = (Pivot) php.getRelation("pivot");
        pivot.setAttribute("taggable_type", "video");

        // Act
        assertThrows(IllegalArgumentException.class, pivot::save);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 5"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void listenerCannotChangeType() {
        // Arrange: a creating listener moves the pivot to another type after the relation set it
        MorphToMany<Tag> tags = post(1).tags().using(TaggablePivot.class);
        TaggablePivot.QUERY.events().creating(pivot -> {
            pivot.setAttribute("taggable_type", "video");
            return true;
        });

        try {
            // Act
            assertThrows(IllegalArgumentException.class, () -> tags.attach(3));
        } finally {
            TaggablePivot.QUERY.events().forget();
        }

        // Assert
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video' and weight = 9"));
    }

    @Test
    void subclassSkippingSuperStillChecked() {
        // Arrange: a pivot class whose saving() never calls super
        SkippingPivot.QUERY.getClass();
        Tag php = tagWithPivot(post(1).tags().using(SkippingPivot.class).withPivot("weight"), 1);
        Pivot pivot = (Pivot) php.getRelation("pivot");
        pivot.setAttribute("taggable_type", "video");

        // Act
        assertThrows(IllegalArgumentException.class, pivot::save);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 5"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void syncWithOtherTypeAttributeRefused() {
        // Arrange
        MorphToMany<Tag> tags = post(1).tags();
        Map<Object, Object> ids = new LinkedHashMap<>();
        ids.put(3, attrs("taggable_type", "video"));

        // Act
        assertThrows(IllegalArgumentException.class, () -> tags.sync(ids));

        // Assert: nothing written, the video rows are as seeded
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video' and weight = 9"));
        assertEquals(0, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
    }

    @Test
    void toggleWithOtherTypeAttributeRefused() {
        // Arrange
        MorphToMany<Tag> tags = post(1).tags();
        Map<Object, Object> ids = new LinkedHashMap<>();
        ids.put(3, attrs("taggable_type", "video"));

        // Act
        assertThrows(IllegalArgumentException.class, () -> tags.toggle(ids));

        // Assert
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video' and weight = 9"));
        assertEquals(0, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
    }

    @Test
    void differentlyCasedTypeOnPivotRefused() {
        // Arrange
        Tag php = tagWithPivot(post(1).tags().withPivot("weight"), 1);
        Pivot pivot = (Pivot) php.getRelation("pivot");
        pivot.setAttribute("TAGGABLE_TYPE", "post");

        // Act
        assertThrows(IllegalArgumentException.class, pivot::save);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 5"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    private Post post(int id) {
        return Post.QUERY.on(db.db).findOrFail(id);
    }

    private static Tag tagWithPivot(MorphToMany<Tag> relation, long tagId) {
        for (Tag tag : relation.get()) {
            if (((Number) tag.getKey()).longValue() == tagId) {
                return tag;
            }
        }
        throw new AssertionError("tag " + tagId + " is not in the relation");
    }

    private static Map<String, Object> attrs(String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    /** A morph pivot class on {@code taggables} whose {@code saving()} never calls super. */
    static final class SkippingPivot extends MorphPivot {
        static final ModelQuery<SkippingPivot> QUERY = ModelQuery.of(SkippingPivot.class, SkippingPivot::new);

        public SkippingPivot() {
        }

        @Override
        protected String table() {
            return "taggables";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean saving() {
            return true;
        }
    }
}
