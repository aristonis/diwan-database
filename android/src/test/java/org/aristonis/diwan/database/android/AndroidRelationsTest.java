package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
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
        assertTrue(Arrays.asList(Country.QUERY, City.QUERY, Post.QUERY, Comment.QUERY, User.QUERY, Profile.QUERY)
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
}
