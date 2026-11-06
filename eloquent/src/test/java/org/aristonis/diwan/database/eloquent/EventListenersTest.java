package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelObserver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.EventFixtures.Article;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The listener chain, the entries that feed it, the event table and the mute counter, without a database. */
class EventListenersTest {

    private static final Set<ModelEvent> HALTING = EnumSet.of(ModelEvent.SAVING, ModelEvent.CREATING,
            ModelEvent.UPDATING, ModelEvent.DELETING, ModelEvent.RESTORING, ModelEvent.FORCE_DELETING);

    private final Article model = Article.QUERY.newModel();

    @AfterEach
    void clearMute() {
        while (EventMute.isMuted()) {
            EventMute.exit();
        }
    }

    private static EventEntry recording(List<String> calls, String name, boolean answer) {
        return (event, m) -> {
            calls.add(name);
            return answer;
        };
    }

    @Test
    void emptyChainIsEmptyAndFiresTrue() {
        assertTrue(EventListeners.EMPTY.isEmpty());
        assertTrue(EventListeners.EMPTY.fire(ModelEvent.SAVING, model));
    }

    @Test
    void withAppendsWithoutChangingTheOriginal() {
        EventListeners one = EventListeners.EMPTY.with(recording(new ArrayList<>(), "a", true));

        assertTrue(EventListeners.EMPTY.isEmpty());
        assertFalse(one.isEmpty());
        assertNotSame(EventListeners.EMPTY, one);
    }

    @Test
    void withKeepsRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        EventListeners chain = EventListeners.EMPTY.with(recording(calls, "a", true))
                .with(recording(calls, "b", true)).with(recording(calls, "c", true));

        chain.fire(ModelEvent.SAVED, model);

        assertEquals(Arrays.asList("a", "b", "c"), calls);
    }

    @Test
    void olderSnapshotIsUnaffectedByLaterRegistration() {
        List<String> calls = new ArrayList<>();
        EventListeners older = EventListeners.EMPTY.with(recording(calls, "a", true));
        EventListeners newer = older.with(recording(calls, "b", true));

        older.fire(ModelEvent.SAVED, model);

        assertEquals(Collections.singletonList("a"), calls);
        assertFalse(newer.isEmpty());
    }

    @Test
    void haltingEventStopsAtTheFirstFalseAndAnswersFalse() {
        List<String> calls = new ArrayList<>();
        EventListeners chain = EventListeners.EMPTY.with(recording(calls, "a", true))
                .with(recording(calls, "b", false)).with(recording(calls, "c", true));

        boolean result = chain.fire(ModelEvent.SAVING, model);

        assertFalse(result);
        assertEquals(Arrays.asList("a", "b"), calls);
    }

    @Test
    void haltingEventWithOnlyTrueAnswersRunsEverythingAndAnswersTrue() {
        List<String> calls = new ArrayList<>();
        EventListeners chain = EventListeners.EMPTY.with(recording(calls, "a", true))
                .with(recording(calls, "b", true));

        boolean result = chain.fire(ModelEvent.DELETING, model);

        assertTrue(result);
        assertEquals(Arrays.asList("a", "b"), calls);
    }

    @Test
    void nonHaltingEventIgnoresFalseAndRunsEverything() {
        List<String> calls = new ArrayList<>();
        EventListeners chain = EventListeners.EMPTY.with(recording(calls, "a", false))
                .with(recording(calls, "b", false));

        boolean result = chain.fire(ModelEvent.SAVED, model);

        assertTrue(result);
        assertEquals(Arrays.asList("a", "b"), calls);
    }

    @Test
    void everyEventKnowsWhetherItHalts() {
        for (ModelEvent event : ModelEvent.values()) {
            assertEquals(HALTING.contains(event), event.halting(), event.name());
        }
    }

    @Test
    void predicateEntryAnswersItsPredicateForItsOwnEvent() {
        EventEntry veto = EventEntry.<Article>predicate(ModelEvent.SAVING, m -> false);
        EventEntry allow = EventEntry.<Article>predicate(ModelEvent.SAVING, m -> true);

        assertFalse(veto.handle(ModelEvent.SAVING, model));
        assertTrue(allow.handle(ModelEvent.SAVING, model));
    }

    @Test
    void predicateEntryIgnoresOtherEvents() {
        List<String> calls = new ArrayList<>();
        EventEntry entry = EventEntry.<Article>predicate(ModelEvent.SAVING, m -> {
            calls.add("ran");
            return false;
        });

        boolean result = entry.handle(ModelEvent.CREATING, model);

        assertTrue(result);
        assertTrue(calls.isEmpty());
    }

    @Test
    void consumerEntryRunsForItsEventAndAnswersTrue() {
        List<Model> seen = new ArrayList<>();
        EventEntry entry = EventEntry.<Article>consumer(ModelEvent.SAVED, seen::add);

        boolean result = entry.handle(ModelEvent.SAVED, model);

        assertTrue(result);
        assertEquals(1, seen.size());
        assertSame(model, seen.get(0));
    }

    @Test
    void consumerEntryIgnoresOtherEvents() {
        List<Model> seen = new ArrayList<>();
        EventEntry entry = EventEntry.<Article>consumer(ModelEvent.SAVED, seen::add);

        boolean result = entry.handle(ModelEvent.CREATED, model);

        assertTrue(result);
        assertTrue(seen.isEmpty());
    }

    @Test
    void observerEntryRoutesEachEventToItsMethod() {
        EventFixtures.Recorder recorder = new EventFixtures.Recorder();
        EventEntry entry = EventEntry.<Article>observer(recorder);

        for (ModelEvent event : ModelEvent.values()) {
            assertTrue(entry.handle(event, model), event.name());
        }

        assertEquals(ModelEvent.values().length, recorder.events().size());
        assertEquals("retrieved:null", recorder.events().get(0));
    }

    @Test
    void observerEntryAnswersFalseOnlyForAVetoedHaltingEvent() {
        EventFixtures.Recorder recorder = new EventFixtures.Recorder().veto(ModelEvent.UPDATING);
        EventEntry entry = EventEntry.<Article>observer(recorder);

        assertFalse(entry.handle(ModelEvent.UPDATING, model));
        assertTrue(entry.handle(ModelEvent.SAVING, model));
    }

    @Test
    void defaultObserverMethodsAllowEverything() {
        EventEntry entry = EventEntry.<Article>observer(new ModelObserver<Article>() { });

        for (ModelEvent event : ModelEvent.values()) {
            assertTrue(entry.handle(event, model), event.name());
        }
    }

    @Test
    void muteIsOffByDefault() {
        assertFalse(EventMute.isMuted());
    }

    @Test
    void muteCountsNestedEntersAndEndsAtTheLastExit() {
        EventMute.enter();
        EventMute.enter();

        EventMute.exit();
        assertTrue(EventMute.isMuted());
        EventMute.exit();
        assertFalse(EventMute.isMuted());
    }

    @Test
    void muteCanBeEnteredAgainAfterItEnded() {
        EventMute.enter();
        EventMute.exit();

        EventMute.enter();

        assertTrue(EventMute.isMuted());
    }
}
