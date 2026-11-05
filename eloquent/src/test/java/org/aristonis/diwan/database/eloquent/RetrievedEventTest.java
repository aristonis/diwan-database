package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.AuditLog;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The retrieved event fires once for every model a query hydrates, and never for a model built in memory. */
class RetrievedEventTest {

    private EventFixtures fx;
    private Recorder recorder;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
        recorder = new Recorder();
        Article.QUERY.events().observe(recorder);
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        fx.close();
    }

    @Test
    void findFiresRetrievedOnce() {
        fx.articles().query().find(1);

        assertEquals(Collections.singletonList("retrieved:1"), recorder.events());
    }

    @Test
    void getFiresRetrievedForEveryLiveRow() {
        fx.articles().query().get();

        assertEquals(Arrays.asList("retrieved:1", "retrieved:2"), recorder.events());
    }

    @Test
    void withTrashedFiresRetrievedForTheTrashedRowToo() {
        fx.articles().query().withTrashed().get();

        assertEquals(Arrays.asList("retrieved:1", "retrieved:2", "retrieved:3"), recorder.events());
    }

    @Test
    void emptyResultFiresNothing() {
        fx.articles().query().where("title", "Nobody").get();

        assertTrue(recorder.events().isEmpty());
    }

    @Test
    void makeAndNewModelFireNothing() {
        fx.articles().make(java.util.Map.of("title", "In memory"));
        fx.articles().newModel();

        assertTrue(recorder.events().isEmpty());
    }

    @Test
    void eagerLoadFiresRetrievedForTheChildrenToo() {
        fx.real.statement("insert into \"audit_logs\" (\"article_id\", \"note\") values (1, 'a')");
        fx.real.statement("insert into \"audit_logs\" (\"article_id\", \"note\") values (1, 'b')");
        fx.real.statement("insert into \"audit_logs\" (\"article_id\", \"note\") values (2, 'c')");
        Recorder children = new Recorder();
        AuditLog.QUERY.events().observe(children);

        fx.articles().with("audits").get();

        assertEquals(Arrays.asList("retrieved:1", "retrieved:2"), recorder.events());
        assertEquals(Arrays.asList("retrieved:1", "retrieved:2", "retrieved:3"), children.events());
    }

    @Test
    void freshFiresRetrievedOnTheNewCopyOnly() {
        Article original = fx.articles().query().find(1).get();
        List<Article> seen = new ArrayList<>();
        recorder.clear();
        Article.QUERY.events().retrieved(seen::add);

        Article copy = original.<Article>fresh().get();

        assertEquals(Collections.singletonList("retrieved:1"), recorder.events());
        assertEquals(1, seen.size());
        assertNotSame(original, seen.get(0));
        assertSame(copy, seen.get(0));
    }

    @Test
    void retrievedListenerSeesTheLoadedAttributes() {
        List<Object> titles = new ArrayList<>();
        Article.QUERY.events().retrieved(m -> titles.add(m.getAttribute("title")));

        fx.articles().query().get();

        assertEquals(Arrays.asList("One", "Two"), titles);
    }

    @Test
    void mutedQueryFiresNoRetrieved() {
        List<Article> loaded = Model.withoutEvents(() -> {
            return fx.articles().query().get();
        });

        assertEquals(2, loaded.size());
        assertTrue(recorder.events().isEmpty());
    }
}
