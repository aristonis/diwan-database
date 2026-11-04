package org.aristonis.diwan.database.eloquent.relations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/**
 * An in-memory SQLite database for the touch tests, with timestamped tables and a log of every
 * statement (select, insert, update, delete) the logged factory runs. Seeding is not logged.
 */
final class TouchDb implements AutoCloseable {

    /** The timestamp every seeded row starts with. */
    static final String OLD = "2000-01-01 00:00:00";

    private static final List<String> LOGGED = Arrays.asList("select", "selectOne", "scalar", "cursor", "insert",
            "update", "delete", "statement", "affectingStatement");

    private final java.sql.Connection jdbc;
    private final QueryFactory raw;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    /** The logged factory models are bound to. */
    final QueryFactory db;

    /** The unlogged connection. */
    final Connection real;

    TouchDb() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
        raw = SQLiteQuery.of(real);
        db = SQLiteQuery.of(logged(real));
        createTables(real);
        TouchModels.register();
    }

    private Connection logged(Connection target) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (LOGGED.contains(method.getName()) && args != null && args[0] instanceof String) {
                        log.add((String) args[0]);
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void createTables(Connection real) {
        SQLiteSchema.of(real).create("users", t -> {
            t.id();
            t.string("name").nullable();
            t.nullableTimestamps();
        });
        SQLiteSchema.of(real).create("posts", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("title").nullable();
            t.nullableTimestamps();
        });
        SQLiteSchema.of(real).create("topics", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("title").nullable();
            t.nullableTimestamps();
        });
        SQLiteSchema.of(real).create("comments", t -> {
            t.id();
            t.integer("post_id").nullable();
            t.integer("topic_id").nullable();
            t.string("body").nullable();
            t.nullableTimestamps();
        });
    }

    /**
     * Inserts a row without logging it.
     *
     * @param table the table
     * @param row the row
     */
    void seed(String table, Map<String, ?> row) {
        raw.table(table).insert(row);
    }

    /**
     * Reads a row's {@code updated_at} without logging it.
     *
     * @param table the table
     * @param id the row id
     * @return the stored value, as text
     */
    String updatedAt(String table, long id) {
        Object value = real.selectOne("select \"updated_at\" from \"" + table + "\" where \"id\" = ?", id)
                .orElseThrow(IllegalStateException::new).get("updated_at");
        return value == null ? null : value.toString();
    }

    /** Forgets the logged statements. */
    void clearLog() {
        log.clear();
    }

    /**
     * Returns the logged statements since the last clear.
     *
     * @return the SQL texts, in order
     */
    List<String> sql() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /**
     * Returns how many logged statements start with the given text.
     *
     * @param prefix the start, such as {@code update "posts"}
     * @return the count
     */
    int count(String prefix) {
        int count = 0;
        for (String text : sql()) {
            if (text.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns how many logged statements are selects.
     *
     * @return the count
     */
    int selects() {
        return count("select");
    }

    @Override
    public void close() throws SQLException {
        jdbc.close();
    }

    /** Hands out the one JDBC connection; the database closes it at the end. */
    private static final class Single implements JdbcConnectionProvider {
        private final java.sql.Connection connection;

        Single(java.sql.Connection connection) {
            this.connection = connection;
        }

        @Override
        public java.sql.Connection acquire() {
            return connection;
        }

        @Override
        public void release(java.sql.Connection released) {
            // Closed by TouchDb.close().
        }
    }

    /** The timestamped models of the touch tests. */
    static final class TouchModels {

        private TouchModels() {
        }

        /** Initialises every model class, so each entry is registered before a relation names it. */
        static void register() {
            List<ModelQuery<?>> entries = Arrays.asList(TUser.QUERY, TPost.QUERY, TTopic.QUERY, TComment.QUERY);
            if (entries.contains(null)) {
                throw new IllegalStateException("a model entry is not initialised");
            }
        }

        private static Set<String> names(String... names) {
            return new LinkedHashSet<>(Arrays.asList(names));
        }
    }

    /** A user: touches nothing; counts its {@code saved()} calls. */
    static final class TUser extends Model {
        static final ModelQuery<TUser> QUERY = ModelQuery.of(TUser.class, TUser::new);

        int savedCalls;

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected void saved() {
            savedCalls++;
        }
    }

    /** A post: belongs to its author and touches it; has many comments. */
    static final class TPost extends Model {
        static final ModelQuery<TPost> QUERY = ModelQuery.of(TPost.class, TPost::new);

        int savedCalls;

        /** When set, the next {@code saved()} hook saves the post once more. */
        boolean savesInHook;

        /** When set, the {@code saved()} hook throws, so a touch walk fails mid-path. */
        boolean throwsInHook;

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected Set<String> touches() {
            return TouchModels.names("author");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TPost.class).add("author", TPost::author).add("comments", TPost::comments);
        }

        public BelongsTo<TUser> author() {
            return belongsTo(TUser.class, "user_id");
        }

        public HasMany<TComment> comments() {
            return hasMany(TComment.class, "post_id");
        }

        @Override
        protected void saved() {
            savedCalls++;
            if (throwsInHook) {
                throw new IllegalStateException("saved() hook failed");
            }
            if (savesInHook) {
                savesInHook = false;
                save();
            }
        }
    }

    /** A topic: touches its owner and its comments (a {@code HasMany}). */
    static final class TTopic extends Model {
        static final ModelQuery<TTopic> QUERY = ModelQuery.of(TTopic.class, TTopic::new);

        int savedCalls;

        @Override
        protected String table() {
            return "topics";
        }

        @Override
        protected Set<String> touches() {
            return TouchModels.names("owner", "comments");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TTopic.class).add("owner", TTopic::owner).add("comments", TTopic::comments);
        }

        public BelongsTo<TUser> owner() {
            return belongsTo(TUser.class, "user_id");
        }

        public HasMany<TComment> comments() {
            return hasMany(TComment.class, "topic_id");
        }

        @Override
        protected void saved() {
            savedCalls++;
        }
    }

    /** A comment: touches its post, then its topic; can veto its save and delete. */
    static final class TComment extends Model {
        static final ModelQuery<TComment> QUERY = ModelQuery.of(TComment.class, TComment::new);

        int savedCalls;

        boolean vetoes;

        @Override
        protected String table() {
            return "comments";
        }

        @Override
        protected Set<String> touches() {
            return TouchModels.names("post", "topic");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TComment.class).add("post", TComment::post).add("topic", TComment::topic);
        }

        public BelongsTo<TPost> post() {
            return belongsTo(TPost.class, "post_id");
        }

        public BelongsTo<TTopic> topic() {
            return belongsTo(TTopic.class, "topic_id");
        }

        @Override
        protected boolean saving() {
            return !vetoes;
        }

        @Override
        protected boolean deleting() {
            return !vetoes;
        }

        @Override
        protected void saved() {
            savedCalls++;
        }
    }
}
