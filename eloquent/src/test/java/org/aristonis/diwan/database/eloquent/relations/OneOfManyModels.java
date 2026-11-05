package org.aristonis.diwan.database.eloquent.relations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;

/**
 * The models and the database of the one-of-many tests. Users own orders, with a created-at day, a
 * price, a status and a soft delete; posts and videos own images; countries reach orders through
 * users; nodes form a tree.
 *
 * <p>Public, so the SQLite and PostgreSQL tests outside this package can use it. Every test inserts
 * its own rows through {@link Db}; the tables start empty.
 */
public final class OneOfManyModels {

    private OneOfManyModels() {
    }

    /** Initialises every model class, so each {@link ModelQuery} entry is registered. */
    public static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(User.QUERY, StringKeyUser.QUERY, Order.QUERY,
                PlainOrder.QUERY, Country.QUERY, Post.QUERY, Video.QUERY, Image.QUERY, Node.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /** Maps the aliases {@code post} and {@code video} to their classes. */
    public static void mapAliases() {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        MorphMap.morphMap(aliases);
    }

    /**
     * Returns a created-at text for a day of January 2026.
     *
     * @param day the day of the month, 1 to 28
     * @return a sortable timestamp text
     */
    public static String day(int day) {
        return String.format("2026-01-%02d 10:00:00", day);
    }

    /**
     * Returns the keys of models as numbers, in the order given.
     *
     * @param models the models
     * @return their keys
     */
    public static List<Long> keys(Iterable<? extends Model> models) {
        List<Long> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(key(model));
        }
        return keys;
    }

    /**
     * Returns the key of a model as a number.
     *
     * @param model the model, or {@code null}
     * @return the key, or {@code null} when the model is {@code null}
     */
    public static Long key(Model model) {
        return model == null ? null : Long.parseLong(String.valueOf(model.getKey()));
    }

    /** A user: owns orders and reaches the latest, oldest and largest of them in several ways. */
    public static final class User extends Model {
        public static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected String table() {
            return "users";
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
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class).add("orders", User::orders).add("latestOrder", User::latestOrder)
                    .add("latestOrderViaMany", User::latestOrderViaMany)
                    .add("latestByCreated", User::latestByCreated).add("oldestByCreated", User::oldestByCreated)
                    .add("priciestOrder", User::priciestOrder).add("cheapestOrder", User::cheapestOrder)
                    .add("latestPaidOrder", User::latestPaidOrder)
                    .add("latestOrDefault", User::latestOrDefault).add("mixedMapOrder", User::mixedMapOrder)
                    .add("namedLatest", User::namedLatest).add("tableNamedLatest", User::tableNamedLatest)
                    .add("parentNamedLatest", User::parentNamedLatest).add("plainOrders", User::plainOrders)
                    .add("latestOrderThroughSelf", User::latestOrderThroughSelf);
        }

        public HasMany<Order> orders() {
            return hasMany(Order.class, "user_id");
        }

        public HasMany<PlainOrder> plainOrders() {
            return hasMany(PlainOrder.class, "user_id");
        }

        /** The order with the highest key. */
        public HasOne<Order> latestOrder() {
            return hasOne(Order.class, "user_id").latestOfMany();
        }

        /** The latest order reached through the user table itself, so the outer table is the through table. */
        public HasOneThrough<Order> latestOrderThroughSelf() {
            return hasOneThrough(Order.class, User.class, "id", "user_id", "id", "id").latestOfMany();
        }

        /** The same relation, reached from the many side. */
        public HasOne<Order> latestOrderViaMany() {
            return hasMany(Order.class, "user_id").one().latestOfMany();
        }

        /** The order with the latest {@code created_at}, the highest key on a tie. */
        public HasOne<Order> latestByCreated() {
            return hasOne(Order.class, "user_id").latestOfMany("created_at");
        }

        /** The order with the earliest {@code created_at}, the lowest key on a tie. */
        public HasOne<Order> oldestByCreated() {
            return hasOne(Order.class, "user_id").oldestOfMany("created_at");
        }

        /** The highest price, then the latest creation, then the key. */
        public HasOne<Order> latestByPriceThenDay() {
            return hasOne(Order.class, "user_id").latestOfMany("price", "created_at");
        }

        public HasOne<Order> priciestOrder() {
            return hasOne(Order.class, "user_id").ofMany("price", "max");
        }

        public HasOne<Order> cheapestOrder() {
            return hasOne(Order.class, "user_id").ofMany("price", "min");
        }

        /** The latest paid order: the status constraint applies inside the subqueries. */
        public HasOne<Order> latestPaidOrder() {
            return hasOne(Order.class, "user_id").ofMany("created_at", "max", q -> q.where("status", "=", "paid"));
        }

        public HasOne<Order> latestOrDefault() {
            return hasOne(Order.class, "user_id").latestOfMany().withDefault(Map.of("status", "none"));
        }

        /** Price ascending, then latest creation, then the key by the first entry's aggregate. */
        public HasOne<Order> mixedMapOrder() {
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("price", "min");
            columns.put("created_at", "max");
            return hasOne(Order.class, "user_id").ofMany(columns);
        }

        /** A relation on the plain, scope-free order model, with an explicit alias. */
        public HasOne<PlainOrder> namedLatest() {
            return hasOne(PlainOrder.class, "user_id").latestOfMany().ofManyAs("latest_order");
        }

        /** An alias equal to the related table. */
        public HasOne<Order> tableNamedLatest() {
            return hasOne(Order.class, "user_id").latestOfMany().ofManyAs("orders");
        }

        /** An alias equal to the parent table. */
        public HasOne<Order> parentNamedLatest() {
            return hasOne(Order.class, "user_id").latestOfMany().ofManyAs("users");
        }

        public HasOne<Order> ofManyOrder(String column, String aggregate) {
            return hasOne(Order.class, "user_id").ofMany(column, aggregate);
        }

        public HasOne<Order> ofManyOrder(Map<String, String> columns) {
            return hasOne(Order.class, "user_id").ofMany(columns);
        }
    }

    /** A user whose key is a string, so eager keys are bound instead of inlined. */
    public static final class StringKeyUser extends Model {
        public static final ModelQuery<StringKeyUser> QUERY =
                ModelQuery.of(StringKeyUser.class, StringKeyUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(StringKeyUser.class).add("latestOrder", StringKeyUser::latestOrder);
        }

        public HasOne<Order> latestOrder() {
            return hasOne(Order.class, "user_id").latestOfMany();
        }
    }

    /** An order: soft-deleting, so its scope runs inside the one-of-many subqueries. */
    public static final class Order extends Model {
        public static final ModelQuery<Order> QUERY = ModelQuery.of(Order.class, Order::new);

        @Override
        protected String table() {
            return "orders";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }
    }

    /** An order with no scopes, for tests of the exact SQL. */
    public static final class PlainOrder extends Model {
        public static final ModelQuery<PlainOrder> QUERY = ModelQuery.of(PlainOrder.class, PlainOrder::new);

        @Override
        protected String table() {
            return "orders";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A country: reaches the orders of its users through the users. */
    public static final class Country extends Model {
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
            return RelationMap.of(Country.class).add("orders", Country::orders)
                    .add("latestOrder", Country::latestOrder).add("oldestOrder", Country::oldestOrder)
                    .add("latestOrderViaMany", Country::latestOrderViaMany).add("users", Country::users);
        }

        public HasMany<User> users() {
            return hasMany(User.class, "country_id");
        }

        public HasManyThrough<Order> orders() {
            return hasManyThrough(Order.class, User.class, "country_id", "user_id", "id", "id");
        }

        public HasOneThrough<Order> latestOrder() {
            return hasOneThrough(Order.class, User.class, "country_id", "user_id", "id", "id")
                    .latestOfMany();
        }

        public HasOneThrough<Order> oldestOrder() {
            return hasOneThrough(Order.class, User.class, "country_id", "user_id", "id", "id")
                    .oldestOfMany("created_at");
        }

        public HasOneThrough<Order> latestOrderViaMany() {
            return hasManyThrough(Order.class, User.class, "country_id", "user_id", "id", "id").one()
                    .latestOfMany();
        }
    }

    /** A post: owns images, and its latest image. */
    public static final class Post extends Model {
        public static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("images", Post::images).add("latestImage", Post::latestImage)
                    .add("latestImageViaMany", Post::latestImageViaMany);
        }

        public MorphMany<Image> images() {
            return morphMany(Image.class, "imageable");
        }

        public MorphOne<Image> latestImage() {
            return morphOne(Image.class, "imageable").latestOfMany("created_at");
        }

        public MorphOne<Image> latestImageViaMany() {
            return morphMany(Image.class, "imageable").one().latestOfMany("created_at");
        }
    }

    /** A video: owns images, with keys that overlap the posts'. */
    public static final class Video extends Model {
        public static final ModelQuery<Video> QUERY = ModelQuery.of(Video.class, Video::new);

        @Override
        protected String table() {
            return "videos";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Video.class).add("latestImage", Video::latestImage);
        }

        public MorphOne<Image> latestImage() {
            return morphOne(Image.class, "imageable").latestOfMany("created_at");
        }
    }

    /** An image of a post or a video. */
    public static final class Image extends Model {
        public static final ModelQuery<Image> QUERY = ModelQuery.of(Image.class, Image::new);

        @Override
        protected String table() {
            return "images";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A tree node: a self relation to its latest child. */
    public static final class Node extends Model {
        public static final ModelQuery<Node> QUERY = ModelQuery.of(Node.class, Node::new);

        @Override
        protected String table() {
            return "nodes";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Node.class).add("children", Node::children).add("latestChild", Node::latestChild);
        }

        public HasMany<Node> children() {
            return hasMany(Node.class, "parent_id");
        }

        public HasOne<Node> latestChild() {
            return hasOne(Node.class, "parent_id").latestOfMany("created_at");
        }
    }

    /**
     * A database with the one-of-many tables, on SQLite or on a throwaway PostgreSQL, and a log of
     * every select (SQL and bindings) the models run. Rows are inserted through an unlogged factory.
     */
    public static final class Db implements AutoCloseable {

        private static final List<String> SELECTS = Arrays.asList("select", "selectOne", "scalar", "cursor");

        /** One recorded select. */
        public record Query(String sql, List<Object> bindings) {
        }

        /** The logged factory the models are bound to. */
        public final QueryFactory db;

        /** The unlogged connection underneath. */
        public final Connection real;

        private final QueryFactory raw;
        private final java.sql.Connection jdbc;
        private final List<Query> log = Collections.synchronizedList(new ArrayList<>());

        private Db(Connection real, boolean postgres, java.sql.Connection jdbc) {
            this.real = real;
            this.jdbc = jdbc;
            this.raw = postgres ? PostgresQuery.of(real) : SQLiteQuery.of(real);
            this.db = postgres ? PostgresQuery.of(logged(real)) : SQLiteQuery.of(logged(real));
            register();
            for (String statement : schema(postgres ? "bigserial primary key"
                    : "integer primary key autoincrement")) {
                real.statement(statement);
            }
        }

        /**
         * Opens a new in-memory SQLite database with empty tables.
         *
         * @return the database
         * @throws SQLException if it cannot be opened
         */
        public static Db open() throws SQLException {
            java.sql.Connection jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
            try {
                Connection real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
                return new Db(real, false, jdbc);
            } catch (RuntimeException e) {
                jdbc.close();
                throw e;
            }
        }

        /**
         * Opens a new, empty PostgreSQL database, or skips the test when PostgreSQL is not installed.
         *
         * @return the database
         */
        public static Db openPostgres() {
            return new Db(PostgresServer.newDatabase(), true, null);
        }

        private static List<String> schema(String key) {
            return Arrays.asList(
                    "create table countries (id " + key + ", name varchar(255) not null)",
                    "create table users (id " + key + ", country_id bigint, name varchar(255) not null,"
                            + " deleted_at varchar(32))",
                    "create table orders (id " + key + ", user_id bigint, created_at varchar(32) not null,"
                            + " price integer not null, status varchar(32) not null, deleted_at varchar(32))",
                    "create table posts (id " + key + ", title varchar(255) not null)",
                    "create table videos (id " + key + ", title varchar(255) not null)",
                    "create table images (id " + key + ", imageable_type varchar(32), imageable_id bigint,"
                            + " created_at varchar(32) not null)",
                    "create table nodes (id " + key + ", parent_id bigint, created_at varchar(32) not null)");
        }

        private Connection logged(Connection target) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (SELECTS.contains(method.getName()) && args != null && args[0] instanceof String) {
                            log.add(new Query((String) args[0], bindingsOf(args.length > 1 ? args[1] : null)));
                        }
                        try {
                            return method.invoke(target, args);
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

        /** Forgets the recorded selects. */
        public void clearLog() {
            log.clear();
        }

        /**
         * Returns the recorded selects.
         *
         * @return a copy, in order
         */
        public List<Query> queries() {
            synchronized (log) {
                return new ArrayList<>(log);
            }
        }

        /**
         * Returns the recorded selects that read a table.
         *
         * @param table the table name
         * @return the selects whose SQL contains {@code from "table"}
         */
        public List<Query> readsOn(String table) {
            List<Query> found = new ArrayList<>();
            for (Query query : queries()) {
                if (query.sql().contains("from \"" + table + "\"")) {
                    found.add(query);
                }
            }
            return found;
        }

        public void country(long id, String name) {
            insert("countries", "id", id, "name", name);
        }

        public void user(long id, Long countryId, String name) {
            insert("users", "id", id, "country_id", countryId, "name", name, "deleted_at", null);
        }

        public void trashedUser(long id, Long countryId, String name) {
            insert("users", "id", id, "country_id", countryId, "name", name, "deleted_at", "2026-01-30 09:00:00");
        }

        public void order(long id, long userId, String createdAt, int price, String status) {
            insert("orders", "id", id, "user_id", userId, "created_at", createdAt, "price", price, "status",
                    status, "deleted_at", null);
        }

        public void trashedOrder(long id, long userId, String createdAt, int price, String status) {
            insert("orders", "id", id, "user_id", userId, "created_at", createdAt, "price", price, "status",
                    status, "deleted_at", "2026-01-30 09:00:00");
        }

        public void post(long id) {
            insert("posts", "id", id, "title", "post " + id);
        }

        public void video(long id) {
            insert("videos", "id", id, "title", "video " + id);
        }

        public void image(long id, String type, long ownerId, String createdAt) {
            insert("images", "id", id, "imageable_type", type, "imageable_id", ownerId, "created_at", createdAt);
        }

        public void node(long id, Long parentId, String createdAt) {
            insert("nodes", "id", id, "parent_id", parentId, "created_at", createdAt);
        }

        private void insert(String table, Object... keyValues) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                row.put((String) keyValues[i], keyValues[i + 1]);
            }
            raw.table(table).insert(row);
        }

        @Override
        public void close() throws SQLException {
            if (jdbc != null) {
                jdbc.close();
            }
        }

        /** Hands out the one JDBC connection; {@link Db#close()} closes it. */
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
                // Closed by Db.close().
            }
        }
    }
}
