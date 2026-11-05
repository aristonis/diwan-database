package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.linkCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.TouchingPost;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The writes of a morphToMany and morphedByMany relation: every row carries the relation's type, no
 * write can put a row under another type, and rows of other types with the same ids stay as they
 * are. Each test opens its own seeded in-memory database and checks the rows in it.
 */
class MorphToManyWriteTest {

    private static final int ALL_LINKS = 8;
    private static final int MANY_IDS = 700;
    private static final int FIRST_MANY_ID = 101;

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

    @Test
    void attachWritesTheType() {
        // Arrange
        Post first = post(1);

        // Act
        first.tags().attach(3);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video' and weight = 9"));
        assertEquals(ALL_LINKS + 1, linkCount(db, "1 = 1"));
    }

    @Test
    void attachWritesTheTypeOfAGivenModel() {
        // Arrange
        Post third = post(3);
        Tag go = Tag.QUERY.on(db.db).findOrFail(3);

        // Act
        third.tags().attach(go);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 3 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + 1, linkCount(db, "1 = 1"));
    }

    @Test
    void inverseAttachWritesRelatedType() {
        // Arrange
        Tag go = Tag.QUERY.on(db.db).findOrFail(3);

        // Act
        go.posts().attach(1);
        go.videos().attach(2);

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 2 and taggable_type = 'video'"));
        assertEquals(ALL_LINKS + 2, linkCount(db, "1 = 1"));
    }

    @Test
    void inverseAttachWithOtherTypeRefused() {
        // Arrange
        Tag go = Tag.QUERY.on(db.db).findOrFail(3);

        // Act
        assertThrows(IllegalArgumentException.class, () -> go.posts().attach(1, attrs("taggable_type", "video")));
        go.posts().attach(1, attrs("taggable_type", "post"));

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + 1, linkCount(db, "1 = 1"));
    }

    @Test
    void syncStaysInType() {
        // Arrange: post 1 has tags 1 and 2, video 1 has tags 1 and 3
        Post first = post(1);

        // Act
        SyncResult result = first.tags().sync(Arrays.asList(2, 3));

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(1L), result.getDetached());
        assertTrue(result.getUpdated().isEmpty());
        assertEquals(0, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 2 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video'"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void syncWithoutDetachingWritesTheType() {
        // Arrange
        Post first = post(1);

        // Act
        SyncResult result = first.tags().syncWithoutDetaching(Arrays.asList(1, 3));

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertTrue(result.getDetached().isEmpty());
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(3, linkCount(db, "taggable_id = 1 and taggable_type = 'post'"));
    }

    @Test
    void syncWithPivotValuesWritesTheType() {
        // Arrange: post 2 has tag 1
        Post second = post(2);

        // Act
        SyncResult result = second.tags().syncWithPivotValues(Arrays.asList(2, 3), attrs("weight", 2));

        // Assert
        assertEquals(Arrays.<Object>asList(2L, 3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(1L), result.getDetached());
        assertEquals(2, linkCount(db, "taggable_id = 2 and taggable_type = 'post' and weight = 2"));
        assertEquals(0, linkCount(db, "tag_id = 1 and taggable_id = 2 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 2 and taggable_id = 2 and taggable_type = 'video' and weight = 4"));
    }

    @Test
    void toggleStaysInType() {
        // Arrange: post 1 has tags 1 and 2, video 1 has tags 1 and 3
        Post first = post(1);

        // Act
        SyncResult result = first.tags().toggle(Arrays.asList(1, 3));

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(1L), result.getDetached());
        assertEquals(0, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video'"));
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'video'"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void toggleWritesTheTypeOnAttach() {
        // Arrange
        Post third = post(3);

        // Act
        third.tags().toggle(Arrays.asList(1, 2));

        // Assert
        assertEquals(2, linkCount(db, "taggable_id = 3 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + 2, linkCount(db, "1 = 1"));
    }

    @Test
    void attachWithOtherTypeRefused() {
        // Arrange
        Post first = post(1);

        // Act
        assertThrows(IllegalArgumentException.class, () -> first.tags().attach(3, attrs("taggable_type", "video")));

        // Assert
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void attachRefusesWhenOneRowHasAnotherType() {
        // Arrange
        Post third = post(3);
        Map<Object, Object> ids = new LinkedHashMap<>();
        ids.put(1, attrs("taggable_type", "post"));
        ids.put(2, attrs("taggable_type", "video"));

        // Act
        assertThrows(IllegalArgumentException.class, () -> third.tags().attach(ids));

        // Assert
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void upperCaseTypeColumnRefused() {
        // Arrange
        Post first = post(1);

        // Act
        assertThrows(IllegalArgumentException.class, () -> first.tags().attach(3, attrs("TAGGABLE_TYPE", "video")));
        assertThrows(IllegalArgumentException.class,
                () -> first.tags().updateExistingPivot(1, attrs("TAGGABLE_TYPE", "video")));
        assertThrows(IllegalArgumentException.class, () -> first.tags().withPivotValue("TAGGABLE_TYPE", "video"));

        // Assert
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
        assertEquals(0, linkCount(db, "taggable_type = 'TAGGABLE_TYPE'"));
    }

    @Test
    void nullTypeValueRefused() {
        // Arrange
        Post first = post(1);
        Map<String, Object> nullType = new LinkedHashMap<>();
        nullType.put("taggable_type", null);

        // Act
        assertThrows(IllegalArgumentException.class, () -> first.tags().attach(3, nullType));
        assertThrows(IllegalArgumentException.class, () -> first.tags().updateExistingPivot(1, nullType));
        assertThrows(IllegalArgumentException.class, () -> first.tags().withPivotValue("taggable_type", null));

        // Assert
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post'"));
    }

    @Test
    void attachWithSameTypeAccepted() {
        // Arrange
        Post first = post(1);

        // Act
        first.tags().attach(3, attrs("taggable_type", "post"));

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 3 and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + 1, linkCount(db, "1 = 1"));
    }

    @Test
    void pivotValueOtherTypeRefused() {
        // Arrange
        Post first = post(1);

        // Act
        assertThrows(IllegalArgumentException.class, () -> first.tags().withPivotValue("taggable_type", "video"));
        assertThrows(IllegalArgumentException.class,
                () -> first.tags().withPivotValue(attrs("taggable_type", "video")));
        MorphToMany<Tag> same = first.tags().withPivotValue("taggable_type", "post");

        // Assert
        assertEquals(2, same.get().size());
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void updateCannotMoveType() {
        // Arrange
        Post first = post(1);

        // Act
        assertThrows(IllegalArgumentException.class,
                () -> first.tags().updateExistingPivot(1, attrs("taggable_type", "video")));

        // Assert
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 5"));
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void updateWithSameTypeAccepted() {
        // Arrange
        Post first = post(1);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("taggable_type", "post");
        values.put("weight", 50);

        // Act
        int updated = first.tags().updateExistingPivot(1, values);

        // Assert
        assertEquals(1, updated);
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'post' and weight = 50"));
        assertEquals(1, linkCount(db, "tag_id = 1 and taggable_id = 1 and taggable_type = 'video' and weight = 3"));
    }

    @Test
    void createLinksWithType() {
        // Arrange
        Post first = post(1);

        // Act
        Tag created = first.tags().create(attrs("name", "kotlin"));

        // Assert
        assertTrue(created.exists());
        assertEquals(4, tagCount());
        assertEquals(1, linkCount(db, "tag_id = " + created.getKey() + " and taggable_id = 1 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + 1, linkCount(db, "1 = 1"));
    }

    @Test
    void createWithOtherTypeRollsBackTheTag() {
        // Arrange
        Post first = post(1);

        // Act
        assertThrows(IllegalArgumentException.class,
                () -> first.tags().create(attrs("name", "kotlin"), attrs("taggable_type", "video")));

        // Assert
        assertEquals(3, tagCount());
        assertEquals(ALL_LINKS, linkCount(db, "1 = 1"));
    }

    @Test
    void createManyLinksEveryTag() {
        // Arrange
        Post third = post(3);

        // Act
        List<Tag> created = third.tags().createMany(Arrays.asList(attrs("name", "rust"), attrs("name", "zig")));

        // Assert
        assertEquals(2, created.size());
        assertEquals(5, tagCount());
        assertEquals(2, linkCount(db, "taggable_id = 3 and taggable_type = 'post'"));
    }

    @Test
    void modelWritesLinkWithType() {
        // Arrange
        Post second = post(2);
        Tag go = Tag.QUERY.on(db.db).findOrFail(3);

        // Act
        second.tags().save(go);
        Tag first = second.tags().firstOrCreate(attrs("name", "kotlin"));
        Tag updated = second.tags().updateOrCreate(attrs("name", "rust"), attrs("name", "rust"));
        Tag createdOrFound = second.tags().createOrFirst(attrs("name", "scala"));
        List<Tag> many = second.tags().saveMany(Collections.singletonList(newTag("zig")));

        // Assert
        for (Tag tag : Arrays.asList(go, first, updated, createdOrFound, many.get(0))) {
            assertEquals(1, linkCount(db, "tag_id = " + tag.getKey() + " and taggable_id = 2 and taggable_type = 'post'"),
                    String.valueOf(tag.getAttribute("name")));
        }
        assertEquals(5, linkCount(db, "taggable_id = 2 and taggable_type = 'post' and tag_id <> 1"));
        assertEquals(0, linkCount(db, "taggable_type not in ('post', 'video', 'tag')"));
    }

    @Test
    void chunkedAttachTypesEveryRow() {
        // Arrange
        Post third = post(3);
        List<Integer> ids = new ArrayList<>();
        for (int id = FIRST_MANY_ID; id < FIRST_MANY_ID + MANY_IDS; id++) {
            ids.add(id);
        }

        // Act
        third.tags().attach(ids);

        // Assert
        assertEquals(MANY_IDS, linkCount(db, "taggable_id = 3 and taggable_type = 'post'"));
        assertEquals(ALL_LINKS + MANY_IDS, linkCount(db, "1 = 1"));
        assertEquals(0, linkCount(db, "taggable_type is null or taggable_type = ''"));
    }

    @Test
    void timestampsAndType() {
        // Arrange
        SQLiteSchema.of(db.real).create("stamped_taggables", t -> {
            t.integer("tag_id");
            t.integer("taggable_id");
            t.string("taggable_type");
            t.nullableTimestamps();
        });
        Post first = post(1);

        // Act
        first.tagsInTable("stamped_taggables").withTimestamps().attach(2);

        // Assert
        Map<String, Object> row = db.real.selectOne("select * from stamped_taggables").orElseThrow();
        assertEquals("post", row.get("taggable_type"));
        assertEquals(2, ((Number) row.get("tag_id")).intValue());
        assertEquals(1, ((Number) row.get("taggable_id")).intValue());
        assertNotNull(row.get("created_at"));
        assertEquals(row.get("created_at"), row.get("updated_at"));
    }

    @Test
    void attachTouches() {
        // Arrange: post 2 has tag 1; tag 2 is linked to post 1 and video 2, tag 3 to videos only
        TouchingPost second = TouchingPost.QUERY.on(db.db).findOrFail(2);

        // Act
        second.tags().attach(3);

        // Assert
        assertNotNull(updatedAt(1));
        assertNotNull(updatedAt(3));
        assertNull(updatedAt(2));
    }

    private Post post(int id) {
        return Post.QUERY.on(db.db).findOrFail(id);
    }

    private Tag newTag(String name) {
        Tag tag = Tag.QUERY.on(db.db).newModel();
        tag.setAttribute("name", name);
        return tag;
    }

    private int tagCount() {
        return ((Number) db.real.scalar("select count(*) from mm_tags").orElseThrow()).intValue();
    }

    private Object updatedAt(int tagId) {
        return db.real.scalar("select updated_at from mm_tags where id = ?", tagId).orElse(null);
    }

    private static Map<String, Object> attrs(String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }
}
