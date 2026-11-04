package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.HasOne;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Relations over Android's SQLiteDatabase, under Robolectric: the lazy SQL and the 900-key
 * chunk that keeps every query under Android's 999 bound-variable limit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidRelationsTest {

    private static final int COUNTRIES = 2_000;
    private static final int VARIABLE_LIMIT = 999;
    private static final List<String> SELECTS = Arrays.asList("select", "selectOne", "scalar", "cursor");

    /** A country keyed by its text code; has many cities. */
    public static final class Country extends Model {
        static final ModelQuery<Country> QUERY = ModelQuery.of(Country.class, Country::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected String primaryKey() {
            return "code";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Country.class).add("cities", Country::cities);
        }

        public HasMany<City> cities() {
            return hasMany(City.class, "country_code");
        }
    }

    /** A city of a country. */
    public static final class City extends Model {
        static final ModelQuery<City> QUERY = ModelQuery.of(City.class, City::new);

        @Override
        protected String table() {
            return "cities";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A post: belongs to a user, has many comments. */
    public static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("author", Post::author).add("comments", Post::comments);
        }

        public BelongsTo<User> author() {
            return belongsTo(User.class, "user_id");
        }

        public HasMany<Comment> comments() {
            return hasMany(Comment.class, "post_id");
        }
    }

    /** A comment on a post. */
    public static final class Comment extends Model {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A user: has one profile. */
    public static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class).add("profile", User::profile);
        }

        public HasOne<Profile> profile() {
            return hasOne(Profile.class, "user_id");
        }
    }

    /** A profile of a user. */
    public static final class Profile extends Model {
        static final ModelQuery<Profile> QUERY = ModelQuery.of(Profile.class, Profile::new);

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A folder: has many notes; has timestamps. */
    public static final class Folder extends Model {
        static final ModelQuery<Folder> QUERY = ModelQuery.of(Folder.class, Folder::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Folder.class).add("notes", Folder::notes);
        }

        public HasMany<Note> notes() {
            return hasMany(Note.class, "folder_id");
        }
    }

    /** A note: belongs to a folder and touches it on save. */
    public static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected Set<String> touches() {
            return Collections.singleton("folder");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Note.class).add("folder", Note::folder);
        }

        public BelongsTo<Folder> folder() {
            return belongsTo(Folder.class, "folder_id");
        }
    }

    private final List<String> sql = new ArrayList<>();
    private final List<Integer> bindingCounts = new ArrayList<>();
    private SQLiteDatabase database;
    private Connection real;
    private QueryFactory db;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        real = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db = SQLiteQuery.of(logged(real));
        Schema schema = SQLiteSchema.of(real);
        schema.create("users", t -> {
            t.id();
            t.string("name");
        });
        schema.create("posts", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("title");
        });
        schema.create("comments", t -> {
            t.id();
            t.integer("post_id").nullable();
            t.string("body");
        });
        schema.create("profiles", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("bio");
        });
        schema.create("countries", t -> {
            t.string("code").primary();
            t.string("name");
        });
        schema.create("cities", t -> {
            t.id();
            t.string("country_code");
            t.string("name");
        });
        schema.create("folders", t -> {
            t.id();
            t.string("name");
            t.timestamps();
        });
        schema.create("notes", t -> {
            t.id();
            t.integer("folder_id").nullable();
            t.string("body");
            t.timestamps();
        });
        assertTrue(Arrays.asList(Folder.QUERY, Note.QUERY, Country.QUERY, City.QUERY, Post.QUERY, Comment.QUERY, User.QUERY, Profile.QUERY)
                .stream().noneMatch(entry -> entry == null));
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    private Connection logged(Connection target) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (SELECTS.contains(method.getName()) && args != null && args[0] instanceof String) {
                        sql.add((String) args[0]);
                        Object bindings = args.length > 1 ? args[1] : null;
                        bindingCounts.add(bindings instanceof List ? ((List<?>) bindings).size()
                                : bindings == null ? 0 : ((Object[]) bindings).length);
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void seedCountries() {
        real.statement("insert into countries (code, name) with recursive s(n) as "
                + "(select 1 union all select n + 1 from s where n < " + COUNTRIES + ") "
                + "select 'c' || n, 'name' from s");
        real.statement("insert into cities (country_code, name) select code, 'city' from countries");
    }

    private void clearLog() {
        sql.clear();
        bindingCounts.clear();
    }

    @Test
    public void lazyRelationsUseLaravelSql() {
        SQLiteQuery.of(real).table("users").insert(Map.of("id", 1, "name", "Ann"));
        SQLiteQuery.of(real).table("profiles").insert(Map.of("id", 7, "user_id", 1, "bio", "hi"));
        SQLiteQuery.of(real).table("posts").insert(Map.of("id", 1, "user_id", 1, "title", "p"));
        SQLiteQuery.of(real).table("comments").insert(Map.of("id", 1, "post_id", 1, "body", "x"));
        SQLiteQuery.of(real).table("comments").insert(Map.of("id", 2, "post_id", 1, "body", "y"));
        Post post = Post.QUERY.on(db).findOrFail(1);
        User user = User.QUERY.on(db).findOrFail(1);
        clearLog();

        ModelCollection<Comment> comments = post.relatedMany("comments");
        User author = post.related("author");
        Profile profile = user.related("profile");

        assertEquals(2, comments.size());
        assertEquals(1L, author.getKey());
        assertEquals(7L, profile.getKey());
        assertEquals("select * from \"comments\" where \"comments\".\"post_id\" = ?"
                + " and \"comments\".\"post_id\" is not null", sql.get(0));
        assertEquals("select * from \"users\" where \"users\".\"id\" = ? limit 1", sql.get(1));
        assertEquals("select * from \"profiles\" where \"profiles\".\"user_id\" = ?"
                + " and \"profiles\".\"user_id\" is not null limit 1", sql.get(2));
    }

    @Test
    public void stringKeysChunkedAt900() {
        seedCountries();
        clearLog();

        ModelCollection<Country> countries = Country.QUERY.on(db).with("cities").get();

        assertEquals(COUNTRIES, countries.size());
        assertEquals(Arrays.asList(0, 900, 900, 200), bindingCounts);
        for (Country country : countries) {
            assertEquals(1, country.relatedMany("cities").size());
        }
    }

    @Test
    public void constrainedStringKeysUnder999() {
        seedCountries();
        clearLog();

        ModelCollection<Country> countries = Country.QUERY.on(db)
                .with("cities", q -> q.where("name", "city")).get();

        assertEquals(COUNTRIES, countries.size());
        assertEquals(4, bindingCounts.size());
        assertEquals(Collections.emptyList(), bindingCounts.stream().filter(n -> n >= VARIABLE_LIMIT).toList());
        assertEquals(1, countries.get(COUNTRIES - 1).relatedMany("cities").size());
    }

    private static final String OLD = "2000-01-01 00:00:00";

    private static Map<String, Object> stamped(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        row.put("created_at", OLD);
        row.put("updated_at", OLD);
        return row;
    }

    private void seedFolder() {
        SQLiteQuery.of(real).table("folders").insert(stamped("id", 1, "name", "f"));
        SQLiteQuery.of(real).table("notes").insert(stamped("id", 1, "folder_id", 1, "body", "a"));
        SQLiteQuery.of(real).table("notes").insert(stamped("id", 2, "folder_id", 1, "body", "b"));
    }

    /** On Android: loadCount over 2,000 string keys is 3 queries of 900, 900 and 200 keys. */
    @Test
    public void loadCountOverStringKeysRunsInThreeChunks() {
        seedCountries();
        ModelCollection<Country> countries = Country.QUERY.on(db).query().get();
        clearLog();

        countries.loadCount("cities");

        assertEquals(Arrays.asList(900, 900, 200), bindingCounts);
        for (Country country : countries) {
            assertEquals(1, ((Number) country.getAttribute("cities_count")).intValue());
        }
    }

    /** On Android: a constrained load keeps constraint bindings plus keys under 999 per query. */
    @Test
    public void constrainedLoadCountStaysUnder999PerQuery() {
        seedCountries();
        ModelCollection<Country> countries = Country.QUERY.on(db).query().get();
        clearLog();
        Map<String, Consumer<EloquentBuilder<?>>> constrained = new HashMap<>();
        constrained.put("cities", q -> q.where("name", "city"));

        countries.loadCount(constrained);

        assertEquals(3, bindingCounts.size());
        for (int count : bindingCounts) {
            assertTrue("bindings " + count, count < VARIABLE_LIMIT);
        }
        int total = 0;
        for (int count : bindingCounts) {
            total += count;
        }
        assertEquals(COUNTRIES + bindingCounts.size(), total);
        assertEquals(1, ((Number) countries.get(COUNTRIES - 1).getAttribute("cities_count")).intValue());
    }

    /** On Android: loadExists over string keys is chunked and returns Boolean. */
    @Test
    public void loadExistsOverStringKeysIsBoolean() {
        seedCountries();
        real.statement("insert into countries (code, name) values ('empty', 'none')");
        ModelCollection<Country> countries = Country.QUERY.on(db).query().get();
        clearLog();

        countries.loadExists("cities");

        assertEquals(3, bindingCounts.size());
        for (int count : bindingCounts) {
            assertTrue(count < VARIABLE_LIMIT);
        }
        int falses = 0;
        for (Country country : countries) {
            Object value = country.getAttribute("cities_exists");
            assertTrue(value instanceof Boolean);
            if (Boolean.FALSE.equals(value)) {
                falses++;
                assertEquals("empty", country.getKey());
            }
        }
        assertEquals(1, falses);
    }

    /** On Android: withExists yields Boolean true and false on API 28 SQLite. */
    @Test
    public void withExistsReturnsBoolean() {
        seedFolder();
        SQLiteQuery.of(real).table("folders").insert(stamped("id", 2, "name", "empty"));

        ModelCollection<Folder> folders = Folder.QUERY.on(db).query().withExists("notes").orderBy("id").get();

        assertEquals(Boolean.TRUE, folders.get(0).getAttribute("notes_exists"));
        assertEquals(Boolean.FALSE, folders.get(1).getAttribute("notes_exists"));
    }

    /** On Android: withCount runs as a correlated subselect on API 28 SQLite. */
    @Test
    public void withCountCountsRelatedRows() {
        seedFolder();
        clearLog();

        Folder folder = Folder.QUERY.on(db).query().withCount("notes").findOrFail(1);

        assertEquals(2, ((Number) folder.getAttribute("notes_count")).intValue());
        assertEquals(1, sql.size());
        assertTrue(sql.get(0).contains("(select count(*) from \"notes\" where"));
    }

    /** On Android: saving a note issues the touch UPDATE and moves the folder's updated_at. */
    @Test
    public void touchUpdatesOwnerOnApi28() {
        seedFolder();
        Note note = Note.QUERY.on(db).findOrFail(1);
        note.setAttribute("body", "changed");

        assertTrue(note.save());

        Folder folder = Folder.QUERY.on(db).findOrFail(1);
        assertNotEquals(OLD, String.valueOf(folder.getAttribute("updated_at")));
    }

    /** On Android: a clean save issues no touch. */
    @Test
    public void cleanSaveLeavesOwnerUntouched() {
        seedFolder();
        Note note = Note.QUERY.on(db).findOrFail(1);

        assertTrue(note.save());

        assertEquals(OLD, String.valueOf(Folder.QUERY.on(db).findOrFail(1).getAttribute("updated_at")));
    }
}
