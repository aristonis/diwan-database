package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelClock;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.TouchScope;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Relation.touch()} and {@code rawUpdate}: the UPDATE text on the SQLite and PostgreSQL
 * grammars (both run over the in-memory SQLite connection), the no-query cases and the timestamp
 * value.
 */
class RelationTouchTest {

    /** The owner's own clock reading; differs from every clock the child is bound with. */
    static final Instant OWNER_NOW = Instant.parse("2021-06-01T10:15:30Z");

    /** An owner keeping timestamps, its clock fixed by its own {@code freshTimestamp()}. */
    static final class Author extends Model {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "authors";
        }

        @Override
        public Instant freshTimestamp() {
            return OWNER_NOW;
        }
    }

    /** An owner over the same table with timestamps off. */
    static final class QuietAuthor extends Model {
        static final ModelQuery<QuietAuthor> QUERY = ModelQuery.of(QuietAuthor.class, QuietAuthor::new);

        @Override
        protected String table() {
            return "authors";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** An owner keeping timestamps but no update column. */
    static final class Stampless extends Model {
        static final ModelQuery<Stampless> QUERY = ModelQuery.of(Stampless.class, Stampless::new);

        @Override
        protected String table() {
            return "authors";
        }

        @Override
        protected String updatedAtColumn() {
            return null;
        }
    }

    /** A child of every owner kind, and the parent of notes. */
    static final class Article extends Model {
        static final ModelQuery<Article> QUERY = ModelQuery.of(Article.class, Article::new);

        @Override
        protected String table() {
            return "articles";
        }

        @Override
        public Instant freshTimestamp() {
            return Instant.EPOCH;
        }

        BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }

        BelongsTo<QuietAuthor> quietAuthor() {
            return belongsTo(QuietAuthor.class, "author_id");
        }

        BelongsTo<Stampless> stampless() {
            return belongsTo(Stampless.class, "author_id");
        }

        HasMany<Note> notes() {
            return hasMany(Note.class, "article_id");
        }

        HasOne<Note> note() {
            return hasOne(Note.class, "article_id");
        }
    }

    /** A child row of an article, keeping timestamps on the system clock. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected String table() {
            return "notes";
        }
    }

    /** One logged write: its SQL and bindings. */
    record Write(String sql, List<Object> bindings) {
    }

    private RelationDb db;
    private final List<Write> writes = Collections.synchronizedList(new ArrayList<>());
    private QueryFactory sqlite;
    private QueryFactory pgsql;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        List<ModelQuery<?>> entries = Arrays.asList(Author.QUERY, QuietAuthor.QUERY, Stampless.QUERY,
                Article.QUERY, Note.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
        db.real.statement("create table authors (id integer primary key, name varchar,"
                + " created_at varchar, updated_at varchar)");
        db.real.statement("create table articles (id integer primary key, author_id integer,"
                + " title varchar, created_at varchar, updated_at varchar)");
        db.real.statement("create table notes (id integer primary key, article_id integer,"
                + " body varchar, created_at varchar, updated_at varchar)");
        db.real.statement("insert into authors (id, name, updated_at) values (1, 'Ann', 'old')");
        db.real.statement("insert into articles (id, author_id, title) values (1, 1, 'a'), (2, null, 'orphan')");
        db.real.statement("insert into notes (id, article_id, body, updated_at)"
                + " values (1, 1, 'x', 'old'), (2, 1, 'y', 'old'), (3, 2, 'z', 'old')");
        Connection logged = logWrites(db.real);
        sqlite = SQLiteQuery.of(logged);
        pgsql = PostgresQuery.of(logged);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Connection logWrites(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (isWrite(method.getName()) && args != null && args[0] instanceof String) {
                        writes.add(new Write((String) args[0], bindingsOf(args[1])));
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static boolean isWrite(String name) {
        return name.equals("update") || name.equals("affectingStatement");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bindingsOf(Object argument) {
        if (argument instanceof List) {
            return new ArrayList<>((List<Object>) argument);
        }
        return argument == null ? Collections.emptyList() : Arrays.asList((Object[]) argument);
    }

    private List<QueryFactory> grammars() {
        return Arrays.asList(sqlite, pgsql);
    }

    private Article article(QueryFactory factory, int id) {
        Article article = Article.QUERY.on(factory).findOrFail(id);
        writes.clear();
        db.clearLog();
        return article;
    }

    private List<Write> writes() {
        synchronized (writes) {
            return new ArrayList<>(writes);
        }
    }

    private Object column(String table, int id, String column) {
        return db.real.selectOne("select " + column + " from " + table + " where id = ?", id)
                .orElseThrow().get(column);
    }

    private static Object ownerValue(QueryFactory factory) {
        return Author.QUERY.on(factory).newModel().freshTimestampValue();
    }

    @Test
    void belongsToTouchUpdatesTheOwnerRow() {
        for (QueryFactory factory : grammars()) {
            Article article = article(factory, 1);

            article.author().touch();

            assertEquals(1, writes().size());
            assertEquals("update \"authors\" set \"updated_at\" = ? where \"authors\".\"id\" = ?",
                    writes().get(0).sql());
            assertEquals(Arrays.asList(ownerValue(factory), 1), writes().get(0).bindings());
            assertEquals(ownerValue(factory), column("authors", 1, "updated_at"));
        }
    }

    @Test
    void hasManyTouchUpdatesEveryChildWithIsNotNull() {
        for (QueryFactory factory : grammars()) {
            Article article = article(factory, 1);
            Class<?> expected = Note.QUERY.on(factory).newModel().freshTimestampValue().getClass();

            article.notes().touch();

            assertEquals(1, writes().size());
            assertEquals("update \"notes\" set \"updated_at\" = ? where \"notes\".\"article_id\" = ?"
                    + " and \"notes\".\"article_id\" is not null", writes().get(0).sql());
            assertEquals(1, writes().get(0).bindings().get(1));
            assertEquals(expected, writes().get(0).bindings().get(0).getClass());
            assertNotEquals("old", column("notes", 1, "updated_at"));
            assertNotEquals("old", column("notes", 2, "updated_at"));
            assertEquals("old", column("notes", 3, "updated_at"));
        }
    }

    @Test
    void hasOneTouchUsesTheSameConstraints() {
        for (QueryFactory factory : grammars()) {
            Article article = article(factory, 1);

            article.note().touch();

            assertEquals(1, writes().size());
            assertEquals("update \"notes\" set \"updated_at\" = ? where \"notes\".\"article_id\" = ?"
                    + " and \"notes\".\"article_id\" is not null", writes().get(0).sql());
        }
    }

    @Test
    void nullForeignKeyRunsNoQuery() {
        for (QueryFactory factory : grammars()) {
            Article orphan = article(factory, 2);

            orphan.author().touch();

            assertEquals(Collections.emptyList(), writes());
            assertEquals(Collections.emptyList(), db.sql());
        }
    }

    @Test
    void noTimestampsNoTouch() {
        for (QueryFactory factory : grammars()) {
            Article article = article(factory, 1);

            article.quietAuthor().touch();
            article.stampless().touch();

            assertEquals(Collections.emptyList(), writes());
            assertEquals("old", column("authors", 1, "updated_at"));
        }
    }

    @Test
    void withoutTimestampsSuppressesTheTouch() {
        Article article = article(sqlite, 1);

        Model.withoutTimestamps(() -> {
            article.author().touch();
            article.notes().touch();
            return null;
        });

        assertEquals(Collections.emptyList(), writes());
    }

    @Test
    void touchScopeIgnoresTheListedOwnerClass() {
        Article article = article(sqlite, 1);

        TouchScope.run(Collections.singletonList(Author.class), () -> {
            article.author().touch();
            return null;
        });
        article.notes().touch();

        assertEquals(1, writes().size());
        assertTrue(writes().get(0).sql().startsWith("update \"notes\""));
    }

    @Test
    void timestampComesFromTheRelatedModelNotTheParentClock() {
        for (QueryFactory factory : grammars()) {
            Article article = Article.QUERY.withClock(ModelClock.fixed(Instant.EPOCH)).on(factory).findOrFail(1);
            writes.clear();

            article.author().touch();

            Object written = writes().get(0).bindings().get(0);
            assertEquals(ownerValue(factory), written);
            assertNotEquals(article.freshTimestampValue(), written);
        }
    }

    @Test
    void timestampFormat() {
        for (QueryFactory factory : grammars()) {
            Author saved = Author.QUERY.on(factory).newModel();
            saved.setAttribute("name", "Bob");
            saved.save();
            Object ordinary = column("authors", ((Number) saved.getKey()).intValue(), "updated_at");
            Article article = article(factory, 1);

            article.author().touch();

            assertEquals(ordinary, column("authors", 1, "updated_at"));
        }
    }

    @Test
    void rawUpdateKeepsConstraintsAndAddsNoTimestamps() {
        for (QueryFactory factory : grammars()) {
            Article article = article(factory, 1);

            int changed = article.notes().where("body", "x").rawUpdate(Collections.singletonMap("body", "w"));

            assertEquals(1, changed);
            assertEquals(1, writes().size());
            assertEquals("update \"notes\" set \"body\" = ? where \"notes\".\"article_id\" = ?"
                    + " and \"notes\".\"article_id\" is not null and \"body\" = ?", writes().get(0).sql());
            assertEquals(Arrays.asList("w", 1, "x"), writes().get(0).bindings());
            assertEquals("old", column("notes", 1, "updated_at"));
            db.real.update("update notes set body = 'x' where id = 1");
        }
    }

    @Test
    void rawUpdateRejectsNull() {
        Article article = article(sqlite, 1);

        assertThrows(NullPointerException.class, () -> article.notes().rawUpdate(null));
        assertEquals(Collections.emptyList(), writes());
    }

    @Test
    void touchableKinds() {
        Article article = article(sqlite, 1);

        assertTrue(article.author().supportsTouch());
        assertTrue(article.notes().supportsTouch());
        assertTrue(article.note().supportsTouch());
        assertEquals(Collections.emptyList(), writes());
    }
}
