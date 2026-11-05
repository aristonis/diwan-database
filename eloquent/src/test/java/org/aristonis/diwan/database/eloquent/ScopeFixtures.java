package org.aristonis.diwan.database.eloquent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.HasManyThrough;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;

/**
 * Shared database and models for the scope and soft-delete tests, on SQLite or on a throwaway
 * PostgreSQL. Public, so the relation tests in the {@code relations} package can use it. Read-only
 * for the tests that use it: add a model or table here, never in a test.
 *
 * <p>Models: {@link Post} (soft-deleting, tenant scope after {@link #register()}), {@link User}
 * (soft-deleting, has many posts, belongs to many roles), {@link Country} (posts through users, and
 * posts through the plain {@link Writer}), {@link Tag} (no scopes, no soft deletes), {@link Role}
 * (soft-deleting), {@link Category} (soft-deleting, self relations), {@link Ticket} (custom
 * deleted-at column {@code removed_on}), {@link Memo} (soft-deleting, no timestamps), {@link Comment}
 * (soft-deleting, touches its post).
 *
 * <p>Every test that opens a fixture calls {@link #clearScopes()} in {@code @AfterEach}; {@link #open()}
 * and {@link #openPostgres()} call {@link #register()} again, so each test starts with the tenant scope.
 *
 * <p>Seed rows ({@link #seed()}), ids are explicit:
 * <ul>
 *   <li>countries: 1 France, 2 Japan; writers: 1 Wes (country 1)
 *   <li>users: 1 Ann and 2 Ben in country 1, 3 Cleo in country 2; none trashed
 *   <li>posts (id, tenant, user): 1 (7, 1, writer 1), 2 (7, 1), 3 (7, 2), 4 (7, 1, trashed),
 *       5 (8, 3), 6 (8, 1); {@code views} 0, timestamps null
 *   <li>tags: 1 php, 2 java; post_tag: (1,1), (1,2), (2,2)
 *   <li>roles: 1 admin, 2 editor (trashed); role_user (user, role): (1,1), (1,2), (2,1)
 *   <li>categories: 1 root, 2 and 3 (trashed) under 1, 4 under 2
 *   <li>tickets: 1 open, 2 removed; memos: 1; comments: 1 on post 1
 * </ul>
 * With the tenant scope on, tenant 7 sees live posts 1, 2, 3 and trashed post 4 only through
 * {@code withTrashed()}.
 */
public final class ScopeFixtures implements AutoCloseable {

    /** The tenant the post scope filters on. */
    public static final int TENANT_ID = 7;

    /** The other tenant in the seed rows. */
    public static final int OTHER_TENANT_ID = 8;

    /** The name of the post tenant scope. */
    public static final String TENANT = "tenant";

    /** The fixed moment of every model clock made by this fixture. */
    public static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");

    /** The text stored in {@code deleted_at} of the trashed seed rows. */
    public static final String TRASHED_AT = "2026-11-01 09:00:00";

    private static final String SQLITE_KEY = "integer primary key autoincrement";
    private static final String POSTGRES_KEY = "bigserial primary key";
    private static final String SQLITE_URL = "jdbc:sqlite::memory:";

    /** The logged factory the models are bound to. */
    public final QueryFactory db;

    /** The unlogged connection under {@link #db}, for raw checks. */
    public final Connection real;

    /** Every statement run through {@link #db}. */
    public final Log log;

    private final java.sql.Connection jdbc;

    private ScopeFixtures(Connection real, Log log, QueryFactory db, java.sql.Connection jdbc) {
        this.real = real;
        this.log = log;
        this.db = db;
        this.jdbc = jdbc;
        register();
    }

    /**
     * Opens a new in-memory SQLite database with every table, all empty.
     *
     * @return a new fixture, never {@code null}
     * @throws SQLException if the database cannot be opened or the tables cannot be created
     */
    public static ScopeFixtures open() throws SQLException {
        java.sql.Connection jdbc = DriverManager.getConnection(SQLITE_URL);
        try {
            Connection real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
            Log log = new Log();
            ScopeFixtures created = new ScopeFixtures(real, log, SQLiteQuery.of(wrap(real, log)), jdbc);
            created.createTables(SQLITE_KEY);
            return created;
        } catch (RuntimeException e) {
            jdbc.close();
            throw e;
        }
    }

    /**
     * Opens a new, empty PostgreSQL database with every table, or skips the test when PostgreSQL is
     * not installed.
     *
     * @return a new fixture, never {@code null}
     */
    public static ScopeFixtures openPostgres() {
        Connection real = PostgresServer.newDatabase();
        Log log = new Log();
        ScopeFixtures created = new ScopeFixtures(real, log, PostgresQuery.of(wrap(real, log)), null);
        created.createTables(POSTGRES_KEY);
        return created;
    }

    /**
     * Registers the post tenant scope under {@link #TENANT} (tenant {@link #TENANT_ID}). Safe to call
     * again: the same name replaces itself.
     */
    public static void register() {
        Post.QUERY.addGlobalScope(TENANT, builder -> builder.where("tenant_id", TENANT_ID));
        Tag.QUERY.getGlobalScopes();
        User.QUERY.getGlobalScopes();
        Country.QUERY.getGlobalScopes();
        Writer.QUERY.getGlobalScopes();
        Role.QUERY.getGlobalScopes();
        Category.QUERY.getGlobalScopes();
        Ticket.QUERY.getGlobalScopes();
        Memo.QUERY.getGlobalScopes();
        Comment.QUERY.getGlobalScopes();
    }

    /** Removes every registered scope of every fixture model. Call it in {@code @AfterEach}. */
    public static void clearScopes() {
        Post.QUERY.clearGlobalScopes();
        Tag.QUERY.clearGlobalScopes();
        User.QUERY.clearGlobalScopes();
        Country.QUERY.clearGlobalScopes();
        Writer.QUERY.clearGlobalScopes();
        Role.QUERY.clearGlobalScopes();
        Category.QUERY.clearGlobalScopes();
        Ticket.QUERY.clearGlobalScopes();
        Memo.QUERY.clearGlobalScopes();
        Comment.QUERY.clearGlobalScopes();
    }

    /** Returns the post entry bound to this database with the fixed clock. */
    public ModelQuery<Post> posts() {
        return Post.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the user entry bound to this database with the fixed clock. */
    public ModelQuery<User> users() {
        return User.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the country entry bound to this database with the fixed clock. */
    public ModelQuery<Country> countries() {
        return Country.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the tag entry bound to this database with the fixed clock. */
    public ModelQuery<Tag> tags() {
        return Tag.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the writer entry bound to this database with the fixed clock. */
    public ModelQuery<Writer> writers() {
        return Writer.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the role entry bound to this database with the fixed clock. */
    public ModelQuery<Role> roles() {
        return Role.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the category entry bound to this database with the fixed clock. */
    public ModelQuery<Category> categories() {
        return Category.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the ticket entry bound to this database with the fixed clock. */
    public ModelQuery<Ticket> tickets() {
        return Ticket.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the memo entry bound to this database with the fixed clock. */
    public ModelQuery<Memo> memos() {
        return Memo.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the comment entry bound to this database with the fixed clock. */
    public ModelQuery<Comment> comments() {
        return Comment.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /**
     * Inserts the seed rows described on the class and clears the log, so a test starts with no
     * recorded statements.
     */
    public void seed() {
        insert("countries", "id", 1, "name", "France");
        insert("countries", "id", 2, "name", "Japan");
        insert("users", "id", 1, "country_id", 1, "name", "Ann");
        insert("users", "id", 2, "country_id", 1, "name", "Ben");
        insert("users", "id", 3, "country_id", 2, "name", "Cleo");
        insert("writers", "id", 1, "country_id", 1, "name", "Wes");
        insert("posts", "id", 1, "tenant_id", TENANT_ID, "user_id", 1, "writer_id", 1, "title", "Post 1");
        insert("posts", "id", 2, "tenant_id", TENANT_ID, "user_id", 1, "writer_id", null, "title", "Post 2");
        insert("posts", "id", 3, "tenant_id", TENANT_ID, "user_id", 2, "writer_id", null, "title", "Post 3");
        insert("posts", "id", 4, "tenant_id", TENANT_ID, "user_id", 1, "writer_id", null, "title", "Post 4",
                "deleted_at", TRASHED_AT);
        insert("posts", "id", 5, "tenant_id", OTHER_TENANT_ID, "user_id", 3, "writer_id", null, "title", "Post 5");
        insert("posts", "id", 6, "tenant_id", OTHER_TENANT_ID, "user_id", 1, "writer_id", null, "title", "Post 6");
        insert("tags", "id", 1, "name", "php");
        insert("tags", "id", 2, "name", "java");
        insert("post_tag", "post_id", 1, "tag_id", 1);
        insert("post_tag", "post_id", 1, "tag_id", 2);
        insert("post_tag", "post_id", 2, "tag_id", 2);
        insert("roles", "id", 1, "name", "admin");
        insert("roles", "id", 2, "name", "editor", "deleted_at", TRASHED_AT);
        insert("role_user", "user_id", 1, "role_id", 1);
        insert("role_user", "user_id", 1, "role_id", 2);
        insert("role_user", "user_id", 2, "role_id", 1);
        insert("categories", "id", 1, "parent_id", null, "name", "root");
        insert("categories", "id", 2, "parent_id", 1, "name", "child");
        insert("categories", "id", 3, "parent_id", 1, "name", "gone", "deleted_at", TRASHED_AT);
        insert("categories", "id", 4, "parent_id", 2, "name", "grandchild");
        insert("tickets", "id", 1, "title", "Open");
        insert("tickets", "id", 2, "title", "Closed", "removed_on", TRASHED_AT);
        insert("memos", "id", 1, "body", "Memo 1");
        insert("comments", "id", 1, "post_id", 1, "body", "Nice");
        log.clear();
    }

    /**
     * Inserts one row with fixed literal values, outside the log. Strings are quoted, numbers are
     * written as they are, {@code null} stays null. For seed data only.
     *
     * @param table the table name
     * @param keyValues the column names and values, alternating, with an even count
     * @throws IllegalArgumentException if the count is odd
     */
    public void insert(String table, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("column names and values must come in pairs");
        }
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            columns.add("\"" + keyValues[i] + "\"");
            values.add(literal(keyValues[i + 1]));
        }
        real.statement("insert into \"" + table + "\" (" + String.join(", ", columns) + ") values ("
                + String.join(", ", values) + ")");
    }

    private static String literal(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    /**
     * Closes the SQLite connection. A PostgreSQL database stays on the shared server.
     *
     * @throws SQLException if the SQLite connection cannot be closed
     */
    @Override
    public void close() throws SQLException {
        if (jdbc != null) {
            jdbc.close();
        }
    }

    private void createTables(String key) {
        for (String statement : schema(key)) {
            real.statement(statement);
        }
    }

    private static List<String> schema(String key) {
        String stamps = "created_at timestamp, updated_at timestamp, deleted_at timestamp";
        return Arrays.asList(
                "create table countries (id " + key + ", name varchar(255) not null)",
                "create table users (id " + key + ", country_id bigint, name varchar(255) not null, " + stamps + ")",
                "create table writers (id " + key + ", country_id bigint, name varchar(255) not null)",
                "create table posts (id " + key + ", tenant_id integer not null, user_id bigint, writer_id bigint,"
                        + " title varchar(255) not null, views integer not null default 0, " + stamps + ")",
                "create table tags (id " + key + ", name varchar(255) not null)",
                "create table post_tag (post_id bigint not null, tag_id bigint not null)",
                "create table roles (id " + key + ", name varchar(255) not null, deleted_at timestamp)",
                "create table role_user (user_id bigint not null, role_id bigint not null, deleted_at timestamp)",
                "create table categories (id " + key + ", parent_id bigint, name varchar(255) not null,"
                        + " deleted_at timestamp)",
                "create table tickets (id " + key + ", title varchar(255) not null, removed_on timestamp,"
                        + " created_at timestamp, updated_at timestamp)",
                "create table memos (id " + key + ", body varchar(255) not null, deleted_at timestamp)",
                "create table comments (id " + key + ", post_id bigint not null, body varchar(255) not null, "
                        + stamps + ")"
        );
    }

    private static Connection wrap(Connection real, Log log) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object[] used = args;
                    if (args != null && args.length > 0 && args[0] instanceof String
                            && Log.RECORDED.contains(method.getName())) {
                        log.add(method.getName(), (String) args[0], args.length > 1 ? args[1] : null);
                    }
                    if ("transaction".equals(method.getName()) && args != null
                            && args[0] instanceof TransactionCallback) {
                        TransactionCallback<?> work = (TransactionCallback<?>) args[0];
                        used = args.clone();
                        used[0] = (TransactionCallback<Object>) inner -> work.run(wrap(inner, log));
                    }
                    try {
                        return method.invoke(real, used);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** One recorded statement: the connection method, its SQL and its bindings. */
    public static final class Statement {
        private final String method;
        private final String sql;
        private final List<Object> bindings;

        Statement(String method, String sql, List<Object> bindings) {
            this.method = method;
            this.sql = sql;
            this.bindings = bindings;
        }

        /** Returns the connection method that ran the statement. */
        public String method() {
            return method;
        }

        /** Returns the SQL text. */
        public String sql() {
            return sql;
        }

        /** Returns the bindings in placeholder order. */
        public List<Object> bindings() {
            return bindings;
        }

        /** Tells whether the statement reads rows. */
        public boolean isRead() {
            return Log.READS.contains(method);
        }

        @Override
        public String toString() {
            return method + ": " + sql + " " + bindings;
        }
    }

    /** A thread-safe log of every statement the factory runs. */
    public static final class Log {
        static final List<String> READS = Arrays.asList("select", "selectOne", "scalar", "cursor");
        static final List<String> RECORDED = Arrays.asList("select", "selectOne", "scalar", "cursor", "insert",
                "update", "delete", "statement", "affectingStatement");

        private final List<Statement> statements = Collections.synchronizedList(new ArrayList<>());

        void add(String method, String sql, Object bindings) {
            List<Object> copy = new ArrayList<>();
            if (bindings instanceof List) {
                copy.addAll((List<?>) bindings);
            } else if (bindings instanceof Object[]) {
                copy.addAll(Arrays.asList((Object[]) bindings));
            }
            statements.add(new Statement(method, sql, Collections.unmodifiableList(copy)));
        }

        /** Forgets every recorded statement. */
        public void clear() {
            statements.clear();
        }

        /** Returns every recorded statement. */
        public List<Statement> all() {
            synchronized (statements) {
                return new ArrayList<>(statements);
            }
        }

        /** Returns the statements that read rows. */
        public List<Statement> reads() {
            List<Statement> reads = new ArrayList<>();
            for (Statement statement : all()) {
                if (statement.isRead()) {
                    reads.add(statement);
                }
            }
            return reads;
        }

        /** Returns the statements that change data or schema. */
        public List<Statement> writes() {
            List<Statement> writes = new ArrayList<>();
            for (Statement statement : all()) {
                if (!statement.isRead()) {
                    writes.add(statement);
                }
            }
            return writes;
        }

        /** Returns the SQL text of every recorded statement. */
        public List<String> sql() {
            List<String> sql = new ArrayList<>();
            for (Statement statement : all()) {
                sql.add(statement.sql());
            }
            return sql;
        }
    }

    /** Hands out the one JDBC connection; {@link ScopeFixtures#close()} closes it. */
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
            // Closed by ScopeFixtures.close().
        }
    }

    /** A soft-deleting post with a mass-assignable table, owned by a user. */
    public static final class Post extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("user", Post::user).add("tags", Post::tags);
        }

        /** Returns the owner of this post. */
        public BelongsTo<User> user() {
            return belongsTo(User.class, "user_id");
        }

        /**
         * Returns the tags of this post through {@code post_tag}.
         *
         * @return the many-to-many relation, never {@code null}
         */
        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class, "post_tag", "post_id", "tag_id");
        }
    }

    /** A soft-deleting user with posts and roles. */
    public static final class User extends Model {
        /** The entry of this class. */
        public static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class).add("posts", User::posts).add("roles", User::roles)
                    .add("country", User::country);
        }

        /**
         * Returns the posts whose {@code user_id} is this user's key.
         *
         * @return the has-many relation, never {@code null}
         */
        public HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }

        /**
         * Returns the roles of this user through {@code role_user}; the pivot also has a
         * {@code deleted_at} column.
         *
         * @return the many-to-many relation, never {@code null}
         */
        public BelongsToMany<Role> roles() {
            return belongsToMany(Role.class, "role_user", "user_id", "role_id");
        }

        /** Returns the country of this user. */
        public BelongsTo<Country> country() {
            return belongsTo(Country.class, "country_id");
        }
    }

    /** A country with users, and posts through the soft-deleting users or through plain writers. */
    public static final class Country extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Country> QUERY = ModelQuery.of(Country.class, Country::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Country.class).add("users", Country::users).add("posts", Country::posts)
                    .add("plainPosts", Country::plainPosts);
        }

        /**
         * Returns the users whose {@code country_id} is this country's key.
         *
         * @return the has-many relation, never {@code null}
         */
        public HasMany<User> users() {
            return hasMany(User.class, "country_id");
        }

        /** Returns the posts reached through the soft-deleting users. */
        public HasManyThrough<Post> posts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id");
        }

        /**
         * Returns the posts reached through the plain writers ({@code posts.writer_id}).
         *
         * @return the through relation over a model without soft deletes, never {@code null}
         */
        public HasManyThrough<Post> plainPosts() {
            return hasManyThrough(Post.class, Writer.class, "country_id", "writer_id", "id", "id");
        }
    }

    /** A plain writer, the through model without soft deletes. */
    public static final class Writer extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Writer> QUERY = ModelQuery.of(Writer.class, Writer::new);

        @Override
        protected String table() {
            return "writers";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A plain tag: no scopes, no soft deletes. */
    public static final class Tag extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected String table() {
            return "tags";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A soft-deleting role without timestamps. */
    public static final class Role extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Role> QUERY = ModelQuery.of(Role.class, Role::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A soft-deleting category with a self relation and a through-self relation. */
    public static final class Category extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Category> QUERY = ModelQuery.of(Category.class, Category::new);

        @Override
        protected String table() {
            return "categories";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Category.class).add("children", Category::children)
                    .add("grandchildren", Category::grandchildren);
        }

        /**
         * Returns the categories whose {@code parent_id} is this key (a self relation).
         *
         * @return the has-many relation, never {@code null}
         */
        public HasMany<Category> children() {
            return hasMany(Category.class, "parent_id");
        }

        /** Returns the children of the children (a through-self relation). */
        public HasManyThrough<Category> grandchildren() {
            return hasManyThrough(Category.class, Category.class, "parent_id", "parent_id", "id", "id");
        }
    }

    /** A soft-deleting ticket with the custom deleted-at column {@code removed_on}. */
    public static final class Ticket extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected String table() {
            return "tickets";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected String deletedAtColumn() {
            return "removed_on";
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A soft-deleting memo with no timestamp columns. */
    public static final class Memo extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Memo> QUERY = ModelQuery.of(Memo.class, Memo::new);

        @Override
        protected String table() {
            return "memos";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A soft-deleting comment that touches its post. */
    public static final class Comment extends Model {
        /** The entry of this class. */
        public static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected String table() {
            return "comments";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("post");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("post", Comment::post);
        }

        /** Returns the post this comment belongs to. */
        public BelongsTo<Post> post() {
            return belongsTo(Post.class, "post_id");
        }
    }
}
