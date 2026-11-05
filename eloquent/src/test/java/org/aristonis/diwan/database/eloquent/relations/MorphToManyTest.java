package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.ids;
import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.linkCount;
import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.PlainPivot;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.TaggablePivot;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.TouchingPost;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reads, pivot access and pivot writes of a morphToMany relation: the type filter on the lazy query,
 * the morph map, the pivot object and what the writes may touch.
 */
class MorphToManyTest {

    private static final String TYPE_CONDITION = "\"taggables\".\"taggable_type\" = ?";
    private static final int ALL_LINKS = 8;

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

    private Post post(int id) {
        return Post.QUERY.on(db.db).findOrFail(id);
    }

    private Video video(int id) {
        return Video.QUERY.on(db.db).findOrFail(id);
    }

    private static Pivot pivotOf(Model model) {
        assertTrue(model.relationLoaded("pivot"), "the pivot relation is loaded");
        return (Pivot) model.getRelation("pivot");
    }

    private Tag tagWithPivot(MorphToMany<Tag> relation, long tagId) {
        for (Tag tag : relation.get()) {
            if (((Number) tag.getKey()).longValue() == tagId) {
                return tag;
            }
        }
        throw new AssertionError("tag " + tagId + " is not in the relation");
    }

    @Test
    void ignoresRowsOfOtherTypesWithSameId() {
        Post first = post(1);
        Video firstVideo = video(1);
        db.clearLog();

        ModelCollection<Tag> postTags = first.tags().get();
        ModelCollection<Tag> videoTags = firstVideo.tags().get();

        assertEquals(ids(1, 2), sortedKeys(postTags));
        assertEquals(ids(1, 3), sortedKeys(videoTags));
        assertEquals(2, db.sql().size());
        assertTrue(db.sql().get(0).contains(TYPE_CONDITION), db.sql().get(0));
        assertTrue(db.sql().get(1).contains(TYPE_CONDITION), db.sql().get(1));
    }

    @Test
    void postWithoutOwnLinksGetsNothingFromAVideoLinkWithItsId() {
        Post third = post(3);

        assertTrue(third.tags().get().isEmpty());
    }

    @Test
    void usesTheMorphMapAlias() {
        Post first = post(1);
        db.clearLog();

        MorphToMany<Tag> relation = first.tags();
        relation.get();

        assertEquals("post", relation.getMorphClass());
        List<Object> bindings = db.queries().get(0).bindings();
        assertTrue(bindings.contains("post"), bindings.toString());
        assertFalse(bindings.contains(Post.class.getName()), bindings.toString());
    }

    @Test
    void usesTheClassNameWhenNothingIsMapped() {
        MorphMap.resetForTests();
        MorphMap.requireMorphMap(false);
        Post first = post(1);

        MorphToMany<Tag> relation = first.tags();

        assertEquals(Post.class.getName(), relation.getMorphClass());
        assertTrue(relation.get().isEmpty());
    }

    @Test
    void unmappedParentThrows() {
        Post first = post(1);
        Map<String, Class<? extends Model>> onlyTags = new LinkedHashMap<>();
        onlyTags.put("tag", Tag.class);
        MorphMap.resetForTests();
        MorphMap.enforceMorphMap(onlyTags);

        assertThrows(ClassMorphViolationException.class, () -> first.tags().get());
    }

    @Test
    void pivotCarriesType() {
        Post first = post(1);

        Tag php = tagWithPivot(first.tags().withPivot("weight"), 1);

        Pivot pivot = pivotOf(php);
        assertEquals("post", pivot.getAttribute("taggable_type"));
        assertEquals(5, ((Number) pivot.getAttribute("weight")).intValue());
        assertEquals(1, ((Number) pivot.getAttribute("taggable_id")).intValue());
        assertEquals(1, ((Number) pivot.getAttribute("tag_id")).intValue());
        assertInstanceOf(MorphPivot.class, pivot);
    }

    @Test
    void pivotTypeIsPresentWithoutExtraColumns() {
        Post first = post(1);

        Tag php = tagWithPivot(first.tags(), 1);

        assertEquals("post", pivotOf(php).getAttribute("taggable_type"));
    }

    @Test
    void inheritedPivotClausesWork() {
        Post first = post(1);

        ModelCollection<Tag> heavy = first.tags().withPivot("weight").wherePivot("weight", ">", 5).get();
        ModelCollection<Tag> ordered = first.tags().withPivot("weight").orderByPivot("weight", "desc").get();
        ModelCollection<Tag> renamed = first.tags().as("tagging").get();

        assertEquals(ids(2), sortedKeys(heavy));
        assertEquals(ids(2, 1), Arrays.asList(((Number) ordered.get(0).getKey()).longValue(),
                ((Number) ordered.get(1).getKey()).longValue()));
        assertTrue(renamed.get(0).relationLoaded("tagging"));
        assertEquals("post", ((Pivot) renamed.get(0).getRelation("tagging")).getAttribute("taggable_type"));
    }

    @Test
    void insertsRefusedUntilSupported() {
        Post third = post(3);
        Post first = post(1);

        assertThrows(UnsupportedOperationException.class, () -> third.tags().attach(1));
        assertThrows(UnsupportedOperationException.class, () -> first.tags().sync(Arrays.asList(1, 2, 3)));

        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
        assertEquals(0, linkCount(db, "taggable_id = 3 and taggable_type = 'post'"));
    }

    @Test
    void emptyAttachDoesNothing() {
        Post third = post(3);
        db.clearLog();

        third.tags().attach(Collections.emptyList());

        assertEquals(0, db.sql().stream().filter(sql -> sql.toLowerCase().startsWith("insert")).count());
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void syncWithDetachAndAttachRollsBack() {
        Post first = post(1);

        assertThrows(UnsupportedOperationException.class, () -> first.tags().sync(Arrays.asList(2, 3)));

        assertEquals(2, linkCount(db, "taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "taggable_id = 1 and taggable_type = 'post' and tag_id = 1"));
        assertEquals(1, linkCount(db, "taggable_id = 1 and taggable_type = 'post' and tag_id = 2"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void createRefusedLeavesNoTag() {
        Post first = post(1);
        int before = tagCount();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "kotlin");

        assertThrows(UnsupportedOperationException.class, () -> first.tags().create(attributes));

        assertEquals(before, tagCount());
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void firstOrCreateRefusedLeavesNoTag() {
        Post first = post(1);
        int before = tagCount();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "kotlin");

        assertThrows(UnsupportedOperationException.class, () -> first.tags().firstOrCreate(attributes));

        assertEquals(before, tagCount());
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    private int tagCount() {
        return ((Number) db.real.scalar("select count(*) from mm_tags").orElseThrow()).intValue();
    }

    @Test
    void pivotDeleteStaysInType() {
        Tag php = tagWithPivot(post(1).tags(), 1);

        boolean deleted = pivotOf(php).delete();

        assertTrue(deleted);
        assertEquals(0, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video'"));
        assertEquals(ALL_LINKS - 1, linkCount(db, "1 = 1"));
    }

    @Test
    void pivotSaveStaysInType() {
        Tag php = tagWithPivot(post(1).tags().withPivot("weight"), 1);
        Pivot pivot = pivotOf(php);
        pivot.setAttribute("weight", 99);

        pivot.save();

        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 99"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video' and weight = 3"));
        assertEquals(1, linkCount(db, "weight = 99"));
    }

    @Test
    void detachAndUpdateStayInType() {
        Post first = post(1);

        int updated = first.tags().updateExistingPivot(1, Map.of("weight", 50));
        int removed = first.tags().detach(1);

        assertEquals(1, updated);
        assertEquals(1, removed);
        assertEquals(0, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video' and weight = 3"));
        assertEquals(ALL_LINKS - 1, linkCount(db, "1 = 1"));
    }

    @Test
    void detachAllRemovesOnlyTheParentsTypedRows() {
        Video firstVideo = video(1);

        int removed = firstVideo.tags().detach();

        assertEquals(2, removed);
        assertEquals(0, linkCount(db, "taggable_id = 1 and taggable_type = 'video'"));
        assertEquals(2, linkCount(db, "taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "taggable_id = 1 and taggable_type = 'tag'"));
    }

    @Test
    void touchesWorks() {
        TouchingPost first = TouchingPost.QUERY.on(db.db).findOrFail(1);
        first.setAttribute("title", "changed");

        boolean saved = first.save();

        assertTrue(saved);
        assertNotNull(updatedAt(1));
        assertNotNull(updatedAt(2));
        assertNull(updatedAt(3));
    }

    @Test
    void usingMustBeMorphPivot() {
        Post first = post(1);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> first.tags().using(PlainPivot.class));
        ModelCollection<Tag> viaMorphPivot = first.tags().using(TaggablePivot.class).get();

        assertTrue(thrown.getMessage().contains(PlainPivot.class.getName()), thrown.getMessage());
        assertEquals(ids(1, 2), sortedKeys(viaMorphPivot));
        Pivot pivot = pivotOf(viaMorphPivot.get(0));
        assertInstanceOf(TaggablePivot.class, pivot);
        assertEquals("post", pivot.getAttribute("taggable_type"));
    }

    @Test
    void typeColumnNotDuplicated() {
        Post first = post(1);
        db.clearLog();

        first.tags().withPivot("taggable_type").get();
        first.tags().withPivot("taggable_type", "weight").withPivot("taggable_type").get();

        for (String sql : db.sql()) {
            assertEquals(1, occurrences(sql, "as \"pivot_taggable_type\""), sql);
        }
    }

    @Test
    void chainKeepsMorphType() {
        Post first = post(1);

        MorphToMany<Tag> chained = first.tags().withPivot("weight").withTimestamps().as("tagging")
                .wherePivot("weight", ">", 0).orderByPivot("weight");

        assertEquals("taggable_type", chained.getMorphType());
        assertEquals("taggables.taggable_type", chained.getQualifiedMorphTypeName());
        assertEquals("post", chained.getMorphClass());
        assertFalse(chained.isInverse());
        assertSame(chained, chained.withPivot("weight"));
    }

    @Test
    void tagRelatedToItselfStaysInTheTagType() {
        Tag php = Tag.QUERY.on(db.db).findOrFail(1);

        ModelCollection<Tag> related = php.related().get();

        assertEquals(ids(2), sortedKeys(related));
        assertEquals("java", related.get(0).getAttribute("name"));
    }

    private Object updatedAt(int tagId) {
        return db.real.scalar("select updated_at from mm_tags where id = ?", tagId).orElse(null);
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        int from = text.indexOf(fragment);
        while (from >= 0) {
            count++;
            from = text.indexOf(fragment, from + fragment.length());
        }
        return count;
    }
}
