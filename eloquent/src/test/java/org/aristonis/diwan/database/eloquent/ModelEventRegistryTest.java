package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelEvents;
import org.aristonis.diwan.database.eloquent.ModelObserver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.aristonis.diwan.database.eloquent.EventFixtures.Note;
import org.aristonis.diwan.database.eloquent.EventFixtures.Recorder;
import org.aristonis.diwan.database.eloquent.EventFixtures.TaggedPivot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Registering, forgetting and looking up listeners: derived entries share the class registry. */
class ModelEventRegistryTest {

    private EventFixtures fx;

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

    @Test
    void entryWithoutListenersReportsNone() {
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void registrationMakesTheEntryReportListeners() {
        Article.QUERY.events().saved(m -> { });

        assertTrue(Article.QUERY.events().hasListeners());
    }

    @Test
    void registrationMethodsReturnTheSameEventsForChaining() {
        ModelEvents<Article> events = Article.QUERY.events();

        assertSame(events, events.saving(m -> true));
        assertSame(events, events.saved(m -> { }));
        assertSame(events, events.observe(new Recorder()));
        assertSame(events, events.forget());
    }

    @Test
    void registrationOnADerivedEntryReachesTheRegisteredClassEntry() {
        Recorder recorder = new Recorder();
        fx.articles().events().observe(recorder);

        Article loaded = Article.QUERY.on(fx.db).query().find(1).get();

        assertTrue(Article.QUERY.events().hasListeners());
        assertEquals(Collections.singletonList("retrieved:1"), recorder.events());
        assertNotNull(loaded);
    }

    @Test
    void twoDerivedEntriesShareOneListenerSet() {
        Recorder recorder = new Recorder();
        fx.articles().events().observe(recorder);

        fx.articles().query().find(1);
        Article.QUERY.on(fx.db).query().find(2);

        assertEquals(Arrays.asList("retrieved:1", "retrieved:2"), recorder.events());
    }

    @Test
    void listenersOfOneModelClassDoNotReachAnother() {
        Recorder recorder = new Recorder();
        Article.QUERY.events().observe(recorder);

        fx.notes().query().find(1);

        assertTrue(recorder.events().isEmpty());
        assertFalse(Note.QUERY.events().hasListeners());
    }

    @Test
    void nullPredicateIsRefusedWithAMessage() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> Article.QUERY.events().saving((Predicate<Article>) null));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().trim().isEmpty());
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void nullConsumerIsRefusedWithAMessage() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> Article.QUERY.events().saved((Consumer<Article>) null));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().trim().isEmpty());
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void nullObserverIsRefusedWithAMessage() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> Article.QUERY.events().observe(null));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().trim().isEmpty());
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void forgetSilencesLaterFires() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events().saved(m -> calls.add("saved")).saving(m -> {
            calls.add("saving");
            return true;
        });
        Article.QUERY.events().forget();
        Article article = fx.articles().query().find(1).get();

        article.save();

        assertTrue(calls.isEmpty());
        assertFalse(Article.QUERY.events().hasListeners());
    }

    @Test
    void forgetKeepsTheOtherModelsListeners() {
        Recorder notes = new Recorder();
        Note.QUERY.events().observe(notes);
        Article.QUERY.events().saved(m -> { });

        Article.QUERY.events().forget();

        assertTrue(Note.QUERY.events().hasListeners());
    }

    @Test
    void registrationAfterForgetWorksAgain() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events().saved(m -> calls.add("old"));
        Article.QUERY.events().forget();
        Article.QUERY.events().saved(m -> calls.add("new"));
        Article article = fx.articles().query().find(1).get();

        article.save();

        assertEquals(Collections.singletonList("new"), calls);
    }

    @Test
    void listenersRunInRegistrationOrderAcrossKindsOfRegistration() {
        List<String> calls = new ArrayList<>();
        Article.QUERY.events()
                .saved(m -> calls.add("consumer"))
                .observe(new ModelObserver<Article>() {
                    @Override
                    public void saved(Article m) {
                        calls.add("observer");
                    }
                })
                .saved(m -> calls.add("last"));
        Article article = fx.articles().query().find(1).get();

        article.save();

        assertEquals(Arrays.asList("consumer", "observer", "last"), calls);
    }

    @Test
    void customPivotNotRegisteredOnItsOwnUsesTheEntryItWasBuiltFrom() {
        Recorder recorder = new Recorder();
        TaggedPivot.QUERY.events().observe(recorder);

        TaggedPivot loaded = fx.pivots().query().get().get(0);

        assertNotNull(loaded);
        assertEquals(Collections.singletonList("retrieved:null"), recorder.events());
    }

    @Test
    void customPivotInsertFiresItsSavingAndCreatedListeners() {
        Recorder recorder = new Recorder();
        TaggedPivot.QUERY.events().observe(recorder);
        TaggedPivot pivot = fx.pivots().make(Map.of("post_id", 2, "tag_id", 2));

        boolean saved = pivot.save();

        assertTrue(saved);
        assertEquals(Arrays.asList("saving:null", "creating:null", "created:null", "saved:null"),
                recorder.events());
        assertEquals(1, fx.count("post_tag", "\"post_id\" = 2 and \"tag_id\" = 2"));
    }
}
