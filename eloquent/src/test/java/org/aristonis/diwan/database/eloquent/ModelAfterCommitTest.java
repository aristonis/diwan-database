package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelEvents;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Model listeners registered through {@code events().afterCommit()}: they wait for the commit of the
 * connection that wrote the model, run at once outside a transaction, are dropped on rollback, and are
 * refused before any write inside a transaction the caller owns.
 */
class ModelAfterCommitTest {

    private static final String SCHEMA = "create table articles (id integer primary key autoincrement,"
            + " title varchar(255) not null default '', views integer not null default 0,"
            + " created_at timestamp, updated_at timestamp, deleted_at timestamp)";

    @TempDir
    Path folder;

    private EventFixtures fx;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        fx.close();
    }

    private QueryFactory inTransaction(Connection tx) {
        return QueryFactory.of(tx, fx.db.grammar(), fx.db.processor());
    }

    private static Article titled(QueryFactory factory, String title) {
        return Article.QUERY.on(factory).make(Map.of("title", title));
    }

    private void remember(Article model) {
        seen.add(String.valueOf(model.getAttribute("title")));
    }

    // ---- commit, rollback, outside a transaction, savepoints, muting ----

    @Test
    void savedListenerRunsOnceAfterTheCommit() {
        Article.QUERY.events().afterCommit().saved(this::remember);

        fx.real.transaction(tx -> {
            assertTrue(titled(inTransaction(tx), "Fresh").save());
            assertTrue(seen.isEmpty());
            return null;
        });

        assertEquals(Arrays.asList("Fresh"), seen);
    }

    @Test
    void rolledBackTransactionNeverRunsTheListener() {
        Article.QUERY.events().afterCommit().saved(this::remember);

        assertThrows(IllegalStateException.class, () -> fx.real.transaction(tx -> {
            titled(inTransaction(tx), "Never").save();
            throw new IllegalStateException("rolled back");
        }));

        assertTrue(seen.isEmpty());
    }

    @Test
    void saveOutsideATransactionRunsTheListenerAtOnce() {
        Article.QUERY.events().afterCommit().saved(this::remember);

        assertTrue(titled(fx.db, "Solo").save());

        assertEquals(Arrays.asList("Solo"), seen);
    }

    @Test
    void rolledBackSavepointDropsOnlyTheInnerListener() {
        Article.QUERY.events().afterCommit().saved(this::remember);

        fx.real.transaction(tx -> {
            titled(inTransaction(tx), "Outer").save();
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                titled(inTransaction(inner), "Inner").save();
                throw new IllegalStateException("inner failed");
            }));
            return null;
        });

        assertEquals(Arrays.asList("Outer"), seen);
    }

    @Test
    void listenersRunInRegistrationOrderOnTheOutermostCommit() {
        Article.QUERY.events().afterCommit().saved(m -> seen.add("first")).saved(m -> seen.add("second"));

        fx.real.transaction(tx -> {
            titled(inTransaction(tx), "A").save();
            tx.transaction(inner -> titled(inTransaction(inner), "B").save());
            assertTrue(seen.isEmpty());
            return null;
        });

        assertEquals(Arrays.asList("first", "second", "first", "second"), seen);
    }

    @Test
    void mutedSaveQueuesNothing() {
        Article.QUERY.events().afterCommit().saved(this::remember);

        fx.real.transaction(tx -> {
            Article quiet = titled(inTransaction(tx), "Quiet");
            assertTrue(quiet.saveQuietly());
            Article.withoutEvents(() -> {
                titled(inTransaction(tx), "Muted").save();
            });
            return null;
        });

        assertTrue(seen.isEmpty());
        assertEquals(1, fx.count("articles", "title = 'Quiet'"));
    }

    @Test
    void saveOrFailRunsTheListenerAfterItsOwnTransactionCommitted() {
        Article.QUERY.events().saved(m -> seen.add("in transaction"));
        Article.QUERY.events().afterCommit().saved(
                m -> seen.add("after commit, rows: " + fx.count("articles", "title = 'Whole'")));

        assertTrue(fx.articles().make(Map.of("title", "Whole")).saveOrFail());

        assertEquals(Arrays.asList("in transaction", "after commit, rows: 1"), seen);
    }

    @Test
    void saveOrFailKeepsTheCommittedModelWhenAnAfterCommitListenerThrows() {
        AtomicBoolean isFirstCall = new AtomicBoolean(true);
        Article.QUERY.events().afterCommit().saved(m -> {
            if (isFirstCall.getAndSet(false)) {
                throw new IllegalStateException("listener failed");
            }
        });
        Article article = fx.articles().make(Map.of("title", "Kept"));

        IllegalStateException error = assertThrows(IllegalStateException.class, article::saveOrFail);

        assertEquals("listener failed", error.getMessage());
        assertTrue(article.exists());
        assertTrue(article.wasRecentlyCreated());
        assertNotNull(article.getKey());
        assertFalse(article.isDirty());
        assertEquals(1, fx.count("articles", "title = 'Kept'"));
        article.setAttribute("title", "Changed");
        assertTrue(article.save());
        assertEquals(0, fx.count("articles", "title = 'Kept'"));
        assertEquals(1, fx.count("articles", "title = 'Changed'"));
    }

    @Test
    void saveOrFailStillRestoresTheModelWhenTheTransactionRollsBack() {
        Article.QUERY.events().afterCommit().saved(this::remember);
        Article.QUERY.events().created(m -> {
            throw new IllegalStateException("hook failed");
        });
        Article article = fx.articles().make(Map.of("title", "Undone"));

        assertThrows(IllegalStateException.class, article::saveOrFail);

        assertFalse(article.exists());
        assertNull(article.getKey());
        assertTrue(seen.isEmpty());
        assertEquals(0, fx.count("articles", "title = 'Undone'"));
    }

    @Test
    void listenerSeesTheModelAsItIsWhenItRuns() {
        Article.QUERY.events().afterCommit().saved(this::remember);

        fx.real.transaction(tx -> {
            Article article = titled(inTransaction(tx), "Before");
            article.save();
            article.setAttribute("title", "After");
            return null;
        });

        assertEquals(Arrays.asList("After"), seen);
    }

    // ---- other write events ----

    @Test
    void deleteEventsWaitForTheCommit() {
        Article.QUERY.events().afterCommit().deleted(m -> seen.add("deleted")).softDeleted(m -> seen.add("soft"))
                .restored(m -> seen.add("restored")).forceDeleted(m -> seen.add("force"));

        fx.real.transaction(tx -> {
            Article article = Article.QUERY.on(inTransaction(tx)).query().find(1).get();
            assertTrue(article.delete());
            assertTrue(article.restore());
            assertTrue(article.forceDelete());
            assertTrue(seen.isEmpty());
            return null;
        });

        assertEquals(Arrays.asList("soft", "deleted", "restored", "deleted", "force"), seen);
    }

    // ---- view contract ----

    @Test
    void viewSharesTheListenersOfTheEntry() {
        assertFalse(Article.QUERY.events().hasListeners());

        Article.QUERY.events().afterCommit().saved(this::remember);

        assertTrue(Article.QUERY.events().hasListeners());
        Article.QUERY.events().forget();
        assertFalse(Article.QUERY.events().afterCommit().hasListeners());
    }

    @Test
    void haltingRegistrarsOnTheViewNameTheEvent() {
        ModelEvents<Article> view = Article.QUERY.events().afterCommit();

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> view.saving(m -> true));
        assertTrue(error.getMessage().contains("SAVING"), error.getMessage());
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> view.creating(m -> true))
                .getMessage().contains("CREATING"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> view.updating(m -> true))
                .getMessage().contains("UPDATING"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> view.deleting(m -> true))
                .getMessage().contains("DELETING"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> view.restoring(m -> true))
                .getMessage().contains("RESTORING"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> view.forceDeleting(m -> true))
                .getMessage().contains("FORCE_DELETING"));
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void retrievedAndReplicatingAreNotOfferedOnTheView() {
        ModelEvents<Article> view = Article.QUERY.events().afterCommit();

        assertThrows(UnsupportedOperationException.class, () -> view.retrieved(m -> { }));
        assertThrows(UnsupportedOperationException.class, () -> view.replicating(m -> { }));
    }

    // ---- a transaction the caller owns ----

    @Test
    void callerOwnedTransactionRefusesTheSaveBeforeAnyWrite() throws SQLException {
        String url = newDatabase();
        Connection external = Connection.builder().name("external")
                .connectionProvider(new FileProvider(url, true, 0)).build();
        Article.QUERY.events().afterCommit().saved(this::remember);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> external.transaction(tx -> titled(SQLiteQuery.of(tx), "Refused").save()));

        assertTrue(error.getMessage().contains(Article.class.getName()), error.getMessage());
        assertTrue(seen.isEmpty());
        assertEquals(0, rows(url, "1 = 1"));
    }

    @Test
    void callerOwnedTransactionRefusesDeleteRestoreAndForceDelete() throws SQLException {
        String url = newDatabase();
        Connection external = Connection.builder().name("external")
                .connectionProvider(new FileProvider(url, true, 0)).build();
        Article.QUERY.events().afterCommit().deleted(this::remember);

        external.transaction(tx -> {
            Article article = Article.QUERY.on(SQLiteQuery.of(tx)).make(Map.of("title", "Row"));
            article.setAttribute("id", 7);
            article.markExisting();
            assertThrows(IllegalStateException.class, article::delete);
            assertThrows(IllegalStateException.class, article::restore);
            assertThrows(IllegalStateException.class, article::forceDelete);
            return null;
        });

        assertTrue(seen.isEmpty());
    }

    @Test
    void callerOwnedTransactionRefusesIncrementAndDecrementBeforeAnyWrite() throws SQLException {
        String url = newDatabase();
        try (java.sql.Connection jdbc = DriverManager.getConnection(url); Statement statement = jdbc.createStatement()) {
            statement.execute("insert into articles (title, views) values ('Counted', 5)");
        }
        Connection external = Connection.builder().name("external")
                .connectionProvider(new FileProvider(url, true, 0)).build();
        Article.QUERY.events().afterCommit().saved(this::remember);

        external.transaction(tx -> {
            Article article = Article.QUERY.on(SQLiteQuery.of(tx)).make(Map.of("title", "Counted"));
            article.setAttribute("id", 1);
            article.markExisting();
            assertThrows(IllegalStateException.class, () -> article.increment("views"));
            assertThrows(IllegalStateException.class, () -> article.decrement("views"));
            return null;
        });

        assertEquals(1, rows(url, "views = 5"));
        assertTrue(seen.isEmpty());
    }

    @Test
    void callerOwnedTransactionStillSavesWhenNoListenerWaitsForTheCommit() throws SQLException {
        String url = newDatabase();
        Connection external = Connection.builder().name("external")
                .connectionProvider(new FileProvider(url, true, 0)).build();
        Article.QUERY.events().saved(this::remember);

        external.transaction(tx -> titled(SQLiteQuery.of(tx), "Plain").save());

        assertEquals(Arrays.asList("Plain"), seen);
    }

    @Test
    void connectionsReportWhetherTheyCanDefer() throws SQLException {
        String url = newDatabase();
        Connection own = Connection.builder().name("own").connectionProvider(new FileProvider(url, false, 0)).build();
        Connection external = Connection.builder().name("external")
                .connectionProvider(new FileProvider(url, true, 0)).build();

        assertTrue(own.canDeferUntilCommit());
        Boolean ownInside = own.transaction(tx -> tx.canDeferUntilCommit());
        Boolean externalInside = external.transaction(tx -> tx.canDeferUntilCommit());
        assertTrue(ownInside);
        assertFalse(externalInside);
    }

    // ---- root connection while the caller runs a transaction on it ----

    @Test
    void lenientRootWriteIsAlreadyCommittedSoTheListenerRunsAtOnce() throws SQLException {
        String url = newDatabase();
        Connection lenient = Connection.builder().name("lenient").strictTransactions(false)
                .connectionProvider(new FileProvider(url, false, 0)).build();
        Article.QUERY.events().afterCommit().saved(this::remember);

        lenient.transaction(tx -> {
            assertTrue(titled(SQLiteQuery.of(lenient), "Root").save());
            assertEquals(Arrays.asList("Root"), seen);
            return null;
        });

        assertEquals(Arrays.asList("Root"), seen);
        assertEquals(1, rows(url, "title = 'Root'"));
    }

    @Test
    void strictRootWriteInsideTheCallersTransactionIsStillRefused() throws SQLException {
        String url = newDatabase();
        Connection strict = Connection.builder().name("strict")
                .connectionProvider(new FileProvider(url, false, 0)).build();
        Article.QUERY.events().afterCommit().saved(this::remember);

        strict.transaction(tx -> {
            assertThrows(IllegalStateException.class, () -> titled(SQLiteQuery.of(strict), "Root").save());
            return null;
        });

        assertTrue(seen.isEmpty());
        assertEquals(0, rows(url, "1 = 1"));
    }

    // ---- retry ----

    @Test
    void retriedTransactionRunsTheListenerOnceAfterTheSuccessfulAttempt() throws SQLException {
        String url = newDatabase();
        Connection retrying = Connection.builder().name("retry")
                .connectionProvider(new FileProvider(url, false, 1)).build();
        Article.QUERY.events().afterCommit().saved(this::remember);
        AtomicInteger attempts = new AtomicInteger();

        retrying.transaction(tx -> {
            attempts.incrementAndGet();
            titled(SQLiteQuery.of(tx), "Retried").save();
            return tx.update("update \"articles\" set \"views\" = 1");
        }, 3);

        assertEquals(2, attempts.get());
        assertEquals(Arrays.asList("Retried"), seen);
        assertEquals(1, rows(url, "title = 'Retried'"));
    }

    // ---- support ----

    private String newDatabase() throws SQLException {
        String url = "jdbc:sqlite:" + folder.resolve("articles-" + System.nanoTime() + ".db");
        try (java.sql.Connection jdbc = DriverManager.getConnection(url); Statement statement = jdbc.createStatement()) {
            statement.execute(SCHEMA);
        }
        return url;
    }

    private static int rows(String url, String where) throws SQLException {
        try (java.sql.Connection jdbc = DriverManager.getConnection(url);
                Statement statement = jdbc.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from articles where " + where)) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * Opens a new JDBC connection per session. An external provider reports a transaction the caller
     * owns, with auto-commit off; a provider with failures left throws a serialization failure from the
     * first {@code update} statements it is asked to prepare.
     */
    private static final class FileProvider implements JdbcConnectionProvider {
        private final String url;
        private final boolean isExternal;
        private final AtomicInteger failuresLeft;

        FileProvider(String url, boolean isExternal, int failures) {
            this.url = url;
            this.isExternal = isExternal;
            this.failuresLeft = new AtomicInteger(failures);
        }

        @Override
        public java.sql.Connection acquire() throws SQLException {
            java.sql.Connection jdbc = DriverManager.getConnection(url);
            if (isExternal) {
                jdbc.setAutoCommit(false);
            }
            return failing(jdbc);
        }

        @Override
        public void release(java.sql.Connection jdbc) throws SQLException {
            jdbc.close();
        }

        @Override
        public boolean inExternalTransaction(java.sql.Connection jdbc) {
            return isExternal;
        }

        private java.sql.Connection failing(java.sql.Connection real) {
            return (java.sql.Connection) Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName()) && ((String) args[0]).startsWith("update")
                                && failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                            throw new SQLException("serialization failure", "40001");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
