package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelObserver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.Pivot;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;

/**
 * Shared database, models and recording observer for the model event tests, on SQLite or on a
 * throwaway PostgreSQL. Every test that registers a listener calls {@link #forgetAll()} in
 * {@code @AfterEach}.
 *
 * <p>Seed rows ({@link #seed()}), ids follow the insert order:
 * <ul>
 *   <li>articles: 1 One, 2 Two, 3 Gone (trashed); {@code views} 0, timestamps null
 *   <li>notes: 1 Memo; audit_logs: none; post_tag: (1, 1)
 * </ul>
 */
final class EventFixtures implements AutoCloseable {

    /** The fixed moment of every model clock made by this fixture. */
    static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");

    /** The text stored in {@code deleted_at} of the trashed seed article. */
    static final String TRASHED_AT = "2026-11-01 09:00:00";

    private static final String SQLITE_KEY = "integer primary key autoincrement";
    private static final String POSTGRES_KEY = "bigserial primary key";

    /** The logged factory the models are bound to. */
    final QueryFactory db;

    /** The unlogged connection under {@link #db}, for raw checks. */
    final Connection real;

    /** Every statement run through {@link #db}. */
    final Log log;

    private final java.sql.Connection jdbc;

    private EventFixtures(Connection real, Log log, QueryFactory db, java.sql.Connection jdbc) {
        this.real = real;
        this.log = log;
        this.db = db;
        this.jdbc = jdbc;
    }

    /**
     * Opens a new in-memory SQLite database with every table, all empty.
     *
     * @return a new fixture
     * @throws SQLException if the database cannot be opened
     */
    static EventFixtures open() throws SQLException {
        java.sql.Connection jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            Connection real = Connection.builder().name("sqlite").connectionProvider(new Single(jdbc)).build();
            Log log = new Log();
            EventFixtures created = new EventFixtures(real, log, SQLiteQuery.of(wrap(real, log)), jdbc);
            created.createTables(SQLITE_KEY);
            return created;
        } catch (RuntimeException e) {
            jdbc.close();
            throw e;
        }
    }

    /**
     * Opens a new PostgreSQL database with every table, or skips the test when PostgreSQL is not
     * installed.
     *
     * @return a new fixture
     */
    static EventFixtures openPostgres() {
        Connection real = PostgresServer.newDatabase();
        Log log = new Log();
        EventFixtures created = new EventFixtures(real, log, PostgresQuery.of(wrap(real, log)), null);
        created.createTables(POSTGRES_KEY);
        return created;
    }

    /** Removes every listener of every fixture model. Call it in {@code @AfterEach}. */
    static void forgetAll() {
        Article.QUERY.events().forget();
        Note.QUERY.events().forget();
        AuditLog.QUERY.events().forget();
        TaggedPivot.QUERY.events().forget();
    }

    /** Returns the article entry bound to this database with the fixed clock. */
    ModelQuery<Article> articles() {
        return Article.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the note entry bound to this database with the fixed clock. */
    ModelQuery<Note> notes() {
        return Note.QUERY.on(db).withClock(ModelClock.fixed(NOW));
    }

    /** Returns the audit log entry bound to this database. */
    ModelQuery<AuditLog> audits() {
        return AuditLog.QUERY.on(db);
    }

    /** Returns the tagged pivot entry bound to this database. */
    ModelQuery<TaggedPivot> pivots() {
        return TaggedPivot.QUERY.on(db);
    }

    /** Inserts the seed rows described on the class and clears the log. */
    void seed() {
        real.statement("insert into \"articles\" (\"title\") values ('One')");
        real.statement("insert into \"articles\" (\"title\") values ('Two')");
        real.statement("insert into \"articles\" (\"title\", \"deleted_at\") values ('Gone', '" + TRASHED_AT + "')");
        real.statement("insert into \"notes\" (\"body\") values ('Memo')");
        real.statement("insert into \"post_tag\" (\"post_id\", \"tag_id\") values (1, 1)");
        log.clear();
    }

    /**
     * Counts the rows of a table that match a condition, outside the log.
     *
     * @param table the table name
     * @param where the SQL condition, such as {@code "title = 'One'"}
     * @return the number of rows
     */
    long count(String table, String where) {
        List<Map<String, Object>> rows = real.select(
                "select count(*) as c from \"" + table + "\" where " + where, Collections.emptyList());
        return ((Number) rows.get(0).get("c")).longValue();
    }

    @Override
    public void close() throws SQLException {
        if (jdbc != null) {
            jdbc.close();
        }
    }

    private void createTables(String key) {
        String stamps = "created_at timestamp, updated_at timestamp, deleted_at timestamp";
        real.statement("create table articles (id " + key + ", title varchar(255) not null default '',"
                + " views integer not null default 0, " + stamps + ")");
        real.statement("create table notes (id " + key + ", body varchar(255) not null default '',"
                + " views integer not null default 0)");
        real.statement("create table audit_logs (id " + key + ", article_id bigint, note varchar(255) not null"
                + " default '')");
        real.statement("create table post_tag (post_id bigint not null, tag_id bigint not null)");
    }

    private static Connection wrap(Connection real, Log log) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object[] used = args;
                    if (args != null && args.length > 0 && args[0] instanceof String
                            && Log.RECORDED.contains(method.getName())) {
                        log.add(method.getName(), (String) args[0]);
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

    /** A thread-safe log of the SQL of every statement the factory runs. */
    static final class Log {
        static final List<String> READS = Arrays.asList("select", "selectOne", "scalar", "cursor");
        static final List<String> RECORDED = Arrays.asList("select", "selectOne", "scalar", "cursor", "insert",
                "update", "delete", "statement", "affectingStatement");

        private final List<String[]> statements = Collections.synchronizedList(new ArrayList<>());

        void add(String method, String sql) {
            statements.add(new String[] {method, sql});
        }

        void clear() {
            statements.clear();
        }

        /** Returns the SQL of every statement that changes data, in order. */
        List<String> writes() {
            return sqlOf(false);
        }

        /** Returns the SQL of every statement that reads rows, in order. */
        List<String> reads() {
            return sqlOf(true);
        }

        private List<String> sqlOf(boolean isRead) {
            List<String> sql = new ArrayList<>();
            synchronized (statements) {
                for (String[] statement : statements) {
                    if (READS.contains(statement[0]) == isRead) {
                        sql.add(statement[1]);
                    }
                }
            }
            return sql;
        }
    }

    /** Hands out the one JDBC connection; {@link EventFixtures#close()} closes it. */
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
            // Closed by EventFixtures.close().
        }
    }

    /**
     * An observer that logs {@code "event:key"} for each event it sees, and halts the halting events
     * it was told to veto. The key reads {@code null} for a model that has none yet.
     */
    static final class Recorder implements ModelObserver<Model> {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private final Set<ModelEvent> vetoes = EnumSet.noneOf(ModelEvent.class);

        /** Makes the given halting events return {@code false}; returns this recorder. */
        Recorder veto(ModelEvent... halted) {
            vetoes.addAll(Arrays.asList(halted));
            return this;
        }

        /** Returns a copy of the log. */
        List<String> events() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }

        /** Forgets the log. */
        void clear() {
            events.clear();
        }

        private boolean note(ModelEvent event, String name, Model model) {
            events.add(name + ":" + model.getKey());
            return !vetoes.contains(event);
        }

        @Override
        public boolean saving(Model m) {
            return note(ModelEvent.SAVING, "saving", m);
        }

        @Override
        public void retrieved(Model m) {
            note(ModelEvent.RETRIEVED, "retrieved", m);
        }

        @Override
        public void saved(Model m) {
            note(ModelEvent.SAVED, "saved", m);
        }

        @Override
        public boolean creating(Model m) {
            return note(ModelEvent.CREATING, "creating", m);
        }

        @Override
        public void created(Model m) {
            note(ModelEvent.CREATED, "created", m);
        }

        @Override
        public boolean updating(Model m) {
            return note(ModelEvent.UPDATING, "updating", m);
        }

        @Override
        public void updated(Model m) {
            note(ModelEvent.UPDATED, "updated", m);
        }

        @Override
        public boolean deleting(Model m) {
            return note(ModelEvent.DELETING, "deleting", m);
        }

        @Override
        public void deleted(Model m) {
            note(ModelEvent.DELETED, "deleted", m);
        }

        @Override
        public void softDeleted(Model m) {
            note(ModelEvent.SOFT_DELETED, "softDeleted", m);
        }

        @Override
        public boolean restoring(Model m) {
            return note(ModelEvent.RESTORING, "restoring", m);
        }

        @Override
        public void restored(Model m) {
            note(ModelEvent.RESTORED, "restored", m);
        }

        @Override
        public boolean forceDeleting(Model m) {
            return note(ModelEvent.FORCE_DELETING, "forceDeleting", m);
        }

        @Override
        public void forceDeleted(Model m) {
            note(ModelEvent.FORCE_DELETED, "forceDeleted", m);
        }

        @Override
        public void replicating(Model m) {
            note(ModelEvent.REPLICATING, "replicating", m);
        }
    }

    /**
     * A soft-deleting article with timestamps. Each own hook records its name in {@link #ownHooks}
     * and vetoes when its flag is set.
     */
    static final class Article extends Model {
        static final ModelQuery<Article> QUERY = ModelQuery.of(Article.class, Article::new);

        final List<String> ownHooks = new ArrayList<>();
        boolean vetoSaving;
        boolean vetoCreating;
        boolean vetoUpdating;
        boolean vetoDeleting;
        boolean vetoRestoring;
        boolean vetoForceDeleting;

        @Override
        protected String table() {
            return "articles";
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
            return RelationMap.of(Article.class).add("audits", Article::audits);
        }

        /** Returns the audit rows of this article. */
        HasMany<AuditLog> audits() {
            return hasMany(AuditLog.class, "article_id");
        }

        @Override
        protected boolean saving() {
            ownHooks.add("saving");
            return !vetoSaving;
        }

        @Override
        protected boolean creating() {
            ownHooks.add("creating");
            return !vetoCreating;
        }

        @Override
        protected boolean updating() {
            ownHooks.add("updating");
            return !vetoUpdating;
        }

        @Override
        protected boolean deleting() {
            ownHooks.add("deleting");
            return !vetoDeleting;
        }

        @Override
        protected boolean restoring() {
            ownHooks.add("restoring");
            return !vetoRestoring;
        }

        @Override
        protected boolean forceDeleting() {
            ownHooks.add("forceDeleting");
            return !vetoForceDeleting;
        }
    }

    /** A plain note: no soft deletes, no timestamps. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected String table() {
            return "notes";
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

    /** An audit row an article's listener writes. */
    static final class AuditLog extends Model {
        static final ModelQuery<AuditLog> QUERY = ModelQuery.of(AuditLog.class, AuditLog::new);

        @Override
        protected String table() {
            return "audit_logs";
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

    /** A custom pivot on {@code post_tag}, registered under its own entry. */
    static final class TaggedPivot extends Pivot {
        static final ModelQuery<TaggedPivot> QUERY = ModelQuery.of(TaggedPivot.class, TaggedPivot::new);

        @Override
        protected String table() {
            return "post_tag";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** Whole flows shared by the SQLite and PostgreSQL tests, so both run the same checks. */
    static final class Flows {
        private Flows() {
        }

        /** Insert, update, soft delete, restore and force delete log their events in order. */
        static void lifecycleEventOrder(EventFixtures fx) {
            Recorder recorder = new Recorder();
            Article.QUERY.events().observe(recorder);
            Article fresh = fx.articles().make(Map.of("title", "Fresh"));

            assertTrue(fresh.save());
            String id = String.valueOf(fresh.getKey());
            assertEquals(Arrays.asList("saving:null", "creating:null", "created:" + id, "saved:" + id),
                    recorder.events());

            recorder.clear();
            fresh.setAttribute("title", "Edited");
            assertTrue(fresh.save());
            assertEquals(Arrays.asList("saving:" + id, "updating:" + id, "updated:" + id, "saved:" + id),
                    recorder.events());

            recorder.clear();
            assertTrue(fresh.delete());
            assertEquals(Arrays.asList("deleting:" + id, "softDeleted:" + id, "deleted:" + id), recorder.events());

            recorder.clear();
            assertTrue(fresh.restore());
            assertEquals(Arrays.asList("restoring:" + id, "saving:" + id, "updating:" + id, "updated:" + id,
                    "saved:" + id, "restored:" + id), recorder.events());

            recorder.clear();
            assertTrue(fresh.forceDelete());
            assertEquals(Arrays.asList("forceDeleting:" + id, "deleting:" + id, "deleted:" + id,
                    "forceDeleted:" + id), recorder.events());
            assertEquals(0, fx.count("articles", "\"title\" = 'Edited'"));
        }

        /** A vetoing listener stops the save before any write. */
        static void vetoedSaveWritesNothing(EventFixtures fx) {
            Article.QUERY.events().saving(m -> false);
            Article article = fx.articles().query().find(1).get();
            article.setAttribute("title", "Nope");
            fx.log.clear();

            assertFalse(article.save());

            assertTrue(fx.log.writes().isEmpty());
            assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'One'"));
        }

        /** A later throw rolls back the article row and the audit row a listener wrote. */
        static void listenerWriteRollsBackWithTheSave(EventFixtures fx) {
            Article.QUERY.events().saved(m -> AuditLog.QUERY.on(m.getQueryFactory())
                    .create(Map.of("article_id", 1, "note", "saved")));
            Article.QUERY.events().saved(m -> {
                throw new IllegalStateException("late failure");
            });
            Article fresh = fx.articles().make(Map.of("title", "Rolled"));

            assertThrows(IllegalStateException.class, fresh::saveOrFail);

            assertEquals(0, fx.count("articles", "\"title\" = 'Rolled'"));
            assertEquals(0, fx.count("audit_logs", "1 = 1"));
            assertFalse(fresh.exists());
        }

        /** The retrieved event fires once per hydrated model, and eager loads fire it for the children. */
        static void retrievedFiresPerHydratedModel(EventFixtures fx) {
            fx.real.statement("insert into \"audit_logs\" (\"article_id\", \"note\") values (1, 'a')");
            fx.real.statement("insert into \"audit_logs\" (\"article_id\", \"note\") values (2, 'b')");
            Recorder articles = new Recorder();
            Recorder audits = new Recorder();
            Article.QUERY.events().observe(articles);
            AuditLog.QUERY.events().observe(audits);

            fx.articles().with("audits").get();

            assertEquals(Arrays.asList("retrieved:1", "retrieved:2"), articles.events());
            assertEquals(2, audits.events().size());
        }

        /** Quiet writes do the write and stay silent, even when listeners would veto. */
        static void quietWritesAreSilent(EventFixtures fx) {
            Recorder recorder = new Recorder().veto(ModelEvent.SAVING, ModelEvent.DELETING);
            Article.QUERY.events().observe(recorder);
            Article article = fx.articles().query().find(1).get();
            recorder.clear();
            article.setAttribute("title", "Quiet");

            assertTrue(article.saveQuietly());
            assertTrue(article.deleteQuietly());

            assertTrue(recorder.events().isEmpty());
            assertEquals(1, fx.count("articles", "\"id\" = 1 and \"title\" = 'Quiet' and \"deleted_at\" is not null"));
        }
    }
}
