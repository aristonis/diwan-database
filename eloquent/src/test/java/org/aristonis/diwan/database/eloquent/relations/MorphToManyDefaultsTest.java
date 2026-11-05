package org.aristonis.diwan.database.eloquent.relations;

import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.ids;
import static org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToManyModels.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The table and key names of morphToMany and morphedByMany: the defaults and the explicit overrides. */
class MorphToManyDefaultsTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToManyModels.register();
        MorphToManyModels.mapAliases();
        MorphToManyModels.createAndSeed(db);
        MorphToManyModels.createLabels(db);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Post post(int id) {
        return Post.QUERY.on(db.db).findOrFail(id);
    }

    @Test
    void defaultsFollowLaravel() {
        MorphToMany<Tag> direct = post(1).tags();
        MorphToMany<Post> inverse = Tag.QUERY.on(db.db).findOrFail(1).posts();

        assertEquals("taggables", direct.getTable());
        assertEquals("taggable_id", direct.getForeignPivotKeyName());
        assertEquals("tag_id", direct.getRelatedPivotKeyName());
        assertEquals("taggable_type", direct.getMorphType());
        assertEquals("taggables.taggable_type", direct.getQualifiedMorphTypeName());
        assertEquals("id", direct.getParentKeyName());
        assertEquals("id", direct.getRelatedKeyName());
        assertFalse(direct.isInverse());
        assertEquals("taggables", inverse.getTable());
        assertEquals("tag_id", inverse.getForeignPivotKeyName());
        assertEquals("taggable_id", inverse.getRelatedPivotKeyName());
        assertEquals("taggable_type", inverse.getMorphType());
        assertTrue(inverse.isInverse());
    }

    @Test
    void defaultTablePluralisesTheLastWord() {
        assertEquals("taggables", MorphToMany.defaultTable("taggable"));
        assertEquals("model_tags", MorphToMany.defaultTable("model_tag"));
        assertEquals("model_categories", MorphToMany.defaultTable("model_category"));
    }

    @Test
    void emptyTableFallsBackToTheDefault() {
        assertEquals("taggables", post(1).tagsInTable(null).getTable());
        assertEquals("taggables", post(1).tagsInTable("").getTable());
    }

    @Test
    void explicitNamesWin() {
        Post first = post(1);

        MorphToMany<Tag> labels = first.labels();
        MorphToMany<Post> inverse = Tag.QUERY.on(db.db).findOrFail(2).labeledPosts();

        assertEquals("mm_labels", labels.getTable());
        assertEquals("owner_ref", labels.getForeignPivotKeyName());
        assertEquals("tag_ref", labels.getRelatedPivotKeyName());
        assertEquals("labelable_type", labels.getMorphType());
        assertEquals("mm_labels.labelable_type", labels.getQualifiedMorphTypeName());
        assertEquals(ids(2), sortedKeys(labels.get()));
        assertEquals("tag_ref", inverse.getForeignPivotKeyName());
        assertEquals("owner_ref", inverse.getRelatedPivotKeyName());
        assertEquals(ids(1), sortedKeys(inverse.get()));
    }

    @Test
    void explicitKeysAndRelationNameAreUsedAsGiven() {
        MorphToMany<Tag> labels = post(1).fullyNamedLabels();

        assertEquals("id", labels.getParentKeyName());
        assertEquals("id", labels.getRelatedKeyName());
        assertEquals("mm_labels.owner_ref", labels.getQualifiedForeignPivotKeyName());
        assertEquals("mm_labels.tag_ref", labels.getQualifiedRelatedPivotKeyName());
        assertEquals(ids(2), sortedKeys(labels.get()));
    }

    @Test
    void emptyNameIsRefused() {
        Post first = post(1);

        assertThrows(IllegalArgumentException.class, () -> first.tagsNamed(""));
    }
}
