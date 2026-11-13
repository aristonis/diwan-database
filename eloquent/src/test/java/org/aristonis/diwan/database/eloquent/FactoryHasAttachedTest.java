package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphToMany;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
import org.aristonis.diwan.database.eloquent.relations.Pivot;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory hasAttached: related models attached through many-to-many and morph-to-many relations. */
class FactoryHasAttachedTest {

    /** A model with no timestamps and no guarded attributes. */
    abstract static class Plain extends Model {
        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A pivot whose meta column is an array cast. */
    static final class ArrayPivot extends Pivot {
        static final ModelQuery<ArrayPivot> QUERY = ModelQuery.of(ArrayPivot.class, ArrayPivot::new);

        @Override
        protected String table() {
            return "post_tag_array";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("meta", Casts.of("array"));
        }
    }

    /** A pivot whose meta column is a JSON cast. */
    static final class JsonPivot extends Pivot {
        static final ModelQuery<JsonPivot> QUERY = ModelQuery.of(JsonPivot.class, JsonPivot::new);

        @Override
        protected String table() {
            return "post_tag_json";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("meta", Casts.of("json"));
        }
    }

    /** A post with plain, array-pivot and JSON-pivot tags. */
    static final class Post extends Plain {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("tags", Post::tags).add("arrayTags", Post::arrayTags)
                    .add("jsonTags", Post::jsonTags);
        }

        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class, "post_tag", "post_id", "tag_id").withPivot("weight");
        }

        public BelongsToMany<Tag> arrayTags() {
            return belongsToMany(Tag.class, "post_tag_array", "post_id", "tag_id").using(ArrayPivot.class)
                    .withPivot("meta");
        }

        public BelongsToMany<Tag> jsonTags() {
            return belongsToMany(Tag.class, "post_tag_json", "post_id", "tag_id").using(JsonPivot.class)
                    .withPivot("meta");
        }
    }

    /** A video that owns tags through the shared taggables table. */
    static final class Video extends Plain {
        static final ModelQuery<Video> QUERY = ModelQuery.of(Video.class, Video::new);

        @Override
        protected String table() {
            return "videos";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Video.class).add("tags", Video::tags);
        }

        public MorphToMany<Tag> tags() {
            return morphToMany(Tag.class, "taggable", "taggables", "taggable_id", "tag_id");
        }
    }

    /** A tag. */
    static final class Tag extends Plain {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected String table() {
            return "tags";
        }
    }

    private SqliteModelDb database;

    @BeforeEach
    void open() throws SQLException {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("att_video", Video.class);
        MorphMap.morphMap(aliases);
        ArrayPivot.QUERY.getModelClass();
        JsonPivot.QUERY.getModelClass();
        database = SqliteModelDb.open();
        database.sql("create table posts (id integer primary key autoincrement, title text)");
        database.sql("create table videos (id integer primary key autoincrement, title text)");
        database.sql("create table tags (id integer primary key autoincrement, name text)");
        database.sql("create table post_tag (post_id integer, tag_id integer, weight integer)");
        database.sql("create table post_tag_array (post_id integer, tag_id integer, meta text)");
        database.sql("create table post_tag_json (post_id integer, tag_id integer, meta text)");
        database.sql("create table taggables (tag_id integer, taggable_id integer, taggable_type text)");
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Factory<Post> posts() {
        return Factory.of(Post.QUERY.on(database.db), () -> map("title", "Hello"));
    }

    private Factory<Video> videos() {
        return Factory.of(Video.QUERY.on(database.db), () -> map("title", "Clip"));
    }

    private Factory<Tag> tags() {
        return Factory.of(Tag.QUERY.on(database.db), () -> map("name", "t"));
    }

    private long count(String table) {
        return ((Number) database.scalar("select count(*) from " + table)).longValue();
    }

    private List<String> column(String table, String column) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : database.rows("select " + column + " from " + table + " order by rowid")) {
            values.add(String.valueOf(row.get(column)));
        }
        return values;
    }

    @Test
    void hasAttachedWithAFactoryAttachesTheCreatedModels() {
        Post post = posts().hasAttached(tags().count(2), "tags").create();

        assertEquals(2L, count("tags"));
        assertEquals(Arrays.asList("1", "2"), column("post_tag", "tag_id"));
        assertEquals(Arrays.asList(String.valueOf(post.getKey()), String.valueOf(post.getKey())),
                column("post_tag", "post_id"));
    }

    @Test
    void hasAttachedWithAPivotMapWritesTheValues() {
        posts().hasAttached(tags().count(2), map("weight", 5), "tags").create();

        assertEquals(Arrays.asList("5", "5"), column("post_tag", "weight"));
    }

    @Test
    void aPivotFunctionIsCalledOncePerRelatedModelWithTheParent() {
        List<Object> parents = new ArrayList<>();
        Function<Model, Map<String, Object>> pivot = parent -> {
            parents.add(parent.getKey());
            return map("weight", 7);
        };

        Post post = posts().hasAttached(tags().count(3), pivot, "tags").create();

        assertEquals(Arrays.asList(post.getKey(), post.getKey(), post.getKey()), parents);
        assertEquals(Arrays.asList("7", "7", "7"), column("post_tag", "weight"));
    }

    @Test
    void aPivotListSetsTheRelatedCountWhenNoneIsSet() {
        posts().hasAttached(tags(), Arrays.asList(map("weight", 1), map("weight", 2)), "tags").create();

        assertEquals(2L, count("tags"));
        assertEquals(Arrays.asList("1", "2"), column("post_tag", "weight"));
    }

    @Test
    void aShorterPivotListCyclesWhenTheCountIsLarger() {
        posts().hasAttached(tags().count(3), Arrays.asList(map("weight", 1), map("weight", 2)), "tags").create();

        assertEquals(Arrays.asList("1", "2", "1"), column("post_tag", "weight"));
    }

    @Test
    void anEmptyPivotListThrowsIllegalArgument() {
        List<Map<String, Object>> none = new ArrayList<>();

        assertThrows(IllegalArgumentException.class, () -> posts().hasAttached(tags(), none, "tags"));
    }

    @Test
    void existingSavedModelsAreAttachedAndNoneIsCreated() {
        List<Tag> existing = tags().createMany(2);

        posts().hasAttached(existing, map("weight", 3), "tags").create();

        assertEquals(2L, count("tags"));
        assertEquals(Arrays.asList("1", "2"), column("post_tag", "tag_id"));
        assertEquals(Arrays.asList("3", "3"), column("post_tag", "weight"));
    }

    @Test
    void anUnsavedExistingModelThrowsTheExistingIllegalArgument() {
        List<Tag> unsaved = Collections.singletonList(tags().make());
        Factory<Post> factory = posts().hasAttached(unsaved, map("weight", 3), "tags");

        assertThrows(IllegalArgumentException.class, factory::create);
    }

    @Test
    void hasAttachedOnAMorphToManyWritesTheTypeColumn() {
        Video video = videos().hasAttached(tags().count(2), "tags").create();

        assertEquals(Arrays.asList("att_video", "att_video"), column("taggables", "taggable_type"));
        assertEquals(Arrays.asList(String.valueOf(video.getKey()), String.valueOf(video.getKey())),
                column("taggables", "taggable_id"));
    }

    @Test
    void arrayCastPivotValuesReadBackEqual() {
        // A pivot attached through a using class takes its columns raw, so the cast value is encoded here.
        String encoded = JsonCodec.standard().encode(Arrays.asList("a", "b"));
        Post post = posts().hasAttached(tags(), map("meta", encoded), "arrayTags").create();

        Post reloaded = Post.QUERY.on(database.db).findOrFail(post.getKey());

        Pivot pivot = (Pivot) reloaded.arrayTags().get().get(0).getRelation("pivot");
        assertEquals(Arrays.asList("a", "b"), pivot.getAttribute("meta"));
    }

    @Test
    void jsonCastPivotValuesReadBackEqual() {
        Map<String, Object> meta = map("k", "v", "j", "w");
        // A pivot attached through a using class takes its columns raw, so the cast value is encoded here.
        Post post = posts().hasAttached(tags(), map("meta", JsonCodec.standard().encode(meta)), "jsonTags").create();

        Post reloaded = Post.QUERY.on(database.db).findOrFail(post.getKey());

        Pivot pivot = (Pivot) reloaded.jsonTags().get().get(0).getRelation("pivot");
        assertEquals(meta, pivot.getAttribute("meta"));
    }

    @Test
    void hasAttachedOnMakeCreatesNothing() {
        Post made = posts().hasAttached(tags().count(2), "tags").make();

        assertFalse(made.exists());
        assertEquals(0L, count("tags"));
        assertEquals(0L, count("post_tag"));
    }

    @Test
    void aParentAwareStateSeesTheParentForAttachedModels() {
        posts().hasAttached(tags().state((soFar, parent) -> map("name", "for " + parent.getAttribute("title"))),
                "tags").create();

        assertEquals(Collections.singletonList("for Hello"), column("tags", "name"));
    }

    @Test
    void aParentAwareAfterCreatingSeesTheParentForAttachedModels() {
        List<Object> parents = new ArrayList<>();

        Post post = posts().hasAttached(tags().count(2).afterCreating((tag, parent) -> parents.add(parent.getKey())),
                "tags").create();

        assertEquals(Arrays.asList(post.getKey(), post.getKey()), parents);
    }

    @Test
    void afterAttachTheRelatedModelsReadThroughTheRelation() {
        Post post = posts().hasAttached(tags().count(2).state(map("name", "x")), map("weight", 4), "tags").create();

        List<Tag> read = post.tags().get();

        assertEquals(2, read.size());
        assertEquals("x", read.get(0).getAttribute("name"));
        assertEquals(4L, ((Number) ((Pivot) read.get(0).getRelation("pivot")).getAttribute("weight")).longValue());
    }
}
