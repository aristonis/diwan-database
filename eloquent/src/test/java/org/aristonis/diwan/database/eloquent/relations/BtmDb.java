package org.aristonis.diwan.database.eloquent.relations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/**
 * An in-memory SQLite database with the belongsToMany test tables and a log of
 * every select the logged factory runs. Seeding goes through an unlogged factory
 * on the same connection.
 */
final class BtmDb implements AutoCloseable {

    private static final List<String> SELECTS = Arrays.asList("select", "selectOne", "scalar", "cursor");

    /** The users the fixture seeds, with ids 1 to 4. */
    private static final int FIXTURE_USER_COUNT = 4;

    /** The roles the fixture seeds, with ids 1 and 2. */
    private static final int FIXTURE_ROLE_COUNT = 2;

    /** One logged select: its SQL and bindings. */
    static final class Query {
        private final String sql;
        private final List<Object> bindings;

        Query(String sql, List<Object> bindings) {
            this.sql = sql;
            this.bindings = bindings;
        }

        String sql() {
            return sql;
        }

        List<Object> bindings() {
            return bindings;
        }
    }

    private final java.sql.Connection jdbc;
    private final QueryFactory raw;
    private final List<Query> log = Collections.synchronizedList(new ArrayList<>());

    /** The logged factory models are bound to. */
    final QueryFactory db;

    /** The unlogged connection, for tests that add their own tables. */
    final Connection real;

    private BtmDb() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
        raw = SQLiteQuery.of(real);
        db = SQLiteQuery.of(logged(real));
        createTables(real);
        BtmModels.register();
        seedFixture();
    }

    /**
     * Opens a new in-memory SQLite database with the belongsToMany schema and seed data.
     *
     * @return a new database instance, never {@code null}
     * @throws SQLException if the database cannot be opened
     */
    static BtmDb open() throws SQLException {
        return new BtmDb();
    }

    private Connection logged(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (SELECTS.contains(method.getName()) && args != null && args[0] instanceof String) {
                        log.add(new Query((String) args[0], bindingsOf(args[1])));
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bindingsOf(Object argument) {
        if (argument instanceof List) {
            return new ArrayList<>((List<Object>) argument);
        }
        return argument == null ? Collections.emptyList() : Arrays.asList((Object[]) argument);
    }

    private static void createTables(Connection real) {
        SQLiteSchema.of(real).create("users", t -> {
            t.id();
            t.string("name");
        });

        SQLiteSchema.of(real).create("roles", t -> {
            t.id();
            t.string("name");
        });

        // role_user has no id column.
        SQLiteSchema.of(real).create("role_user", t -> {
            t.integer("user_id");
            t.integer("role_id");
            t.string("status");
            t.integer("weight");
            t.timestamp("created_at").nullable();
            t.timestamp("updated_at").nullable();
        });

        SQLiteSchema.of(real).create("user_user", t -> {
            t.integer("user_id");
            t.integer("friend_id");
            t.string("status");
        });

        SQLiteSchema.of(real).create("posts", t -> {
            t.id();
            t.integer("user_id").nullable();
            t.string("title");
        });

        SQLiteSchema.of(real).create("tags", t -> {
            t.id();
            t.string("name");
        });

        SQLiteSchema.of(real).create("post_tag", t -> {
            t.integer("post_id");
            t.integer("tag_id");
        });

        SQLiteSchema.of(real).create("blog_posts", t -> {
            t.id();
            t.string("title");
        });

        SQLiteSchema.of(real).create("blog_post_tag", t -> {
            t.integer("blog_post_id");
            t.integer("tag_id");
        });

        SQLiteSchema.of(real).create("countries", t -> {
            t.string("code").primary();
            t.string("name");
        });

        SQLiteSchema.of(real).create("languages", t -> {
            t.string("iso").primary();
            t.string("name");
        });

        SQLiteSchema.of(real).create("country_language", t -> {
            t.string("country_code");
            t.string("language_iso");
        });

        SQLiteSchema.of(real).create("permissions", t -> {
            t.id();
            t.string("name");
        });

        SQLiteSchema.of(real).create("permission_role", t -> {
            t.integer("permission_id");
            t.integer("role_id");
        });
    }

    private void seedFixture() {
        raw.table("users").insert(Map.of("id", 1, "name", "User 1"));
        raw.table("users").insert(Map.of("id", 2, "name", "User 2"));
        raw.table("users").insert(Map.of("id", 3, "name", "User 3"));
        raw.table("users").insert(Map.of("id", 4, "name", "User 4")); // has no roles

        raw.table("roles").insert(Map.of("id", 1, "name", "admin"));
        raw.table("roles").insert(Map.of("id", 2, "name", "editor"));

        // user 1 has roles 1 (active, weight 5) and 2 (pending, 1)
        raw.table("role_user").insert(Map.of(
                "user_id", 1, "role_id", 1, "status", "active", "weight", 5,
                "created_at", "2024-01-01 10:00:00", "updated_at", "2024-01-01 10:00:00"
        ));
        raw.table("role_user").insert(Map.of(
                "user_id", 1, "role_id", 2, "status", "pending", "weight", 1,
                "created_at", "2024-01-01 11:00:00", "updated_at", "2024-01-01 11:00:00"
        ));
        // user 2 has role 1 (active, 3)
        raw.table("role_user").insert(Map.of(
                "user_id", 2, "role_id", 1, "status", "active", "weight", 3,
                "created_at", "2024-01-01 12:00:00", "updated_at", "2024-01-01 12:00:00"
        ));

        raw.table("user_user").insert(Map.of("user_id", 1, "friend_id", 2, "status", "active"));
        raw.table("user_user").insert(Map.of("user_id", 2, "friend_id", 1, "status", "active"));

        raw.table("posts").insert(Map.of("id", 1, "user_id", 1, "title", "Post 1"));
        raw.table("posts").insert(Map.of("id", 2, "user_id", 2, "title", "Post 2"));

        raw.table("tags").insert(Map.of("id", 1, "name", "Tag 1"));
        raw.table("tags").insert(Map.of("id", 2, "name", "Tag 2"));

        raw.table("post_tag").insert(Map.of("post_id", 1, "tag_id", 1));
        raw.table("post_tag").insert(Map.of("post_id", 1, "tag_id", 2));
        raw.table("post_tag").insert(Map.of("post_id", 2, "tag_id", 2));

        raw.table("blog_posts").insert(Map.of("id", 1, "title", "Blog Post 1"));
        raw.table("blog_posts").insert(Map.of("id", 2, "title", "Blog Post 2"));

        raw.table("blog_post_tag").insert(Map.of("blog_post_id", 1, "tag_id", 1));
        raw.table("blog_post_tag").insert(Map.of("blog_post_id", 2, "tag_id", 2));

        raw.table("countries").insert(Map.of("code", "US", "name", "United States"));
        raw.table("countries").insert(Map.of("code", "FR", "name", "France"));

        raw.table("languages").insert(Map.of("iso", "en", "name", "English"));
        raw.table("languages").insert(Map.of("iso", "fr", "name", "French"));

        raw.table("country_language").insert(Map.of("country_code", "US", "language_iso", "en"));
        raw.table("country_language").insert(Map.of("country_code", "FR", "language_iso", "fr"));

        raw.table("permissions").insert(Map.of("id", 1, "name", "Permission 1"));
        raw.table("permissions").insert(Map.of("id", 2, "name", "Permission 2"));

        raw.table("permission_role").insert(Map.of("permission_id", 1, "role_id", 1));
        raw.table("permission_role").insert(Map.of("permission_id", 2, "role_id", 1));
        raw.table("permission_role").insert(Map.of("permission_id", 1, "role_id", 2));
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
     * Seeds many users with roles for larger tests.
     *
     * @param users the number of users to create
     * @param rolesPerUser the number of roles per user
     */
    void seedMany(int users, int rolesPerUser) {
        for (int i = 0; i < users; i++) {
            int userId = FIXTURE_USER_COUNT + 1 + i;
            raw.table("users").insert(Map.of("id", userId, "name", "User " + userId));

            for (int j = 0; j < rolesPerUser; j++) {
                int roleId = (j % FIXTURE_ROLE_COUNT) + 1;
                raw.table("role_user").insert(Map.of(
                        "user_id", userId, "role_id", roleId,
                        "status", "active", "weight", j + 1,
                        "created_at", "2024-01-01 10:00:00", "updated_at", "2024-01-01 10:00:00"
                ));
            }
        }
    }

    /**
     * Forgets the logged selects.
     */
    void clearLog() {
        log.clear();
    }

    /**
     * Returns the logged selects since the last clear.
     *
     * @return a copy of the log
     */
    List<Query> queries() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /**
     * Returns the SQL of the logged selects since the last clear.
     *
     * @return the SQL texts, in order
     */
    List<String> sql() {
        List<String> texts = new ArrayList<>();
        for (Query query : queries()) {
            texts.add(query.sql());
        }
        return texts;
    }

    @Override
    public void close() throws SQLException {
        jdbc.close();
    }

    /**
     * Hands out the one JDBC connection; the database closes it at the end.
     */
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
            // Closed by BtmDb.close().
        }
    }
}