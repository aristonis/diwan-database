package org.aristonis.diwan.database.event;

import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.exception.ListenerFailedException;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.event.TransactionPhase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

class ConnectionEventsTest {

    private static ConnectionEvents events(int limit, ConnectionListener... listeners) {
        return new ConnectionEvents(limit, Arrays.asList(listeners));
    }

    private static QueryExecuted query(String sql, Duration time, Object... bindings) {
        return new QueryExecuted("main", QueryKind.SELECT, sql, Arrays.asList(bindings), time, 0);
    }

    private static QueryFailed failure(String sql) {
        DriverException cause = new DriverException("boom", "HY000", 1, null,
                Collections.<DriverFailureKind>emptySet());
        QueryException exception = new QueryException("main", sql, new ArrayList<>(), cause, false);
        return new QueryFailed("main", QueryKind.UPDATE, sql, new ArrayList<>(), Duration.ofMillis(2), 0, exception);
    }

    private static TransactionEvent level(int level) {
        return new TransactionEvent("main", level, level > 1);
    }

    private static final class Recorder implements ConnectionListener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void queryExecuted(QueryExecuted event) {
            calls.add("executed:" + event.sql());
        }

        @Override
        public void queryFailed(QueryFailed event) {
            calls.add("failed:" + event.sql());
        }

        @Override
        public void transactionBeginning(TransactionEvent event) {
            calls.add("beginning:" + event.level());
        }

        @Override
        public void transactionCommitting(TransactionEvent event) {
            calls.add("committing:" + event.level());
        }

        @Override
        public void transactionCommitted(TransactionEvent event) {
            calls.add("committed:" + event.level());
        }

        @Override
        public void transactionRolledBack(TransactionEvent event) {
            calls.add("rolledBack:" + event.level());
        }
    }

    @Test
    void isInactiveUntilSomethingListensLogsOrHandles() {
        ConnectionEvents events = events(10);
        assertFalse(events.isActive());

        events.enableQueryLog();
        assertTrue(events.isActive());
        events.disableQueryLog();
        assertFalse(events.isActive());

        Registration listener = events.listen(e -> { });
        assertTrue(events.isActive());
        listener.remove();
        assertFalse(events.isActive());

        Registration slow = events.whenQueryTakesLongerThan(Duration.ZERO, e -> { });
        assertTrue(events.isActive());
        slow.remove();
        Registration total = events.whenQueryingForLongerThan(Duration.ZERO, (c, e) -> { });
        assertTrue(events.isActive());
        total.remove();
        assertFalse(events.isActive());
    }

    @Test
    void initialListenersMakeTheEventsActive() {
        assertTrue(events(10, new Recorder()).isActive());
    }

    @Test
    void firesEveryListenerInRegistrationOrder() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        ConnectionEvents events = events(10, first);
        events.addListener(second);

        events.executed(null, query("select 1", Duration.ZERO));

        assertEquals(Arrays.asList("executed:select 1"), first.calls);
        assertEquals(Arrays.asList("executed:select 1"), second.calls);
    }

    @Test
    void removingARegistrationStopsEventsAndIsIdempotent() {
        Recorder recorder = new Recorder();
        ConnectionEvents events = events(10);
        Registration registration = events.addListener(recorder);

        registration.remove();
        registration.remove();
        events.executed(null, query("select 1", Duration.ZERO));

        assertEquals(0, recorder.calls.size());
    }

    @Test
    void addingTheSameListenerTwiceGivesTwoIndependentRegistrations() {
        Recorder recorder = new Recorder();
        ConnectionEvents events = events(10);
        Registration first = events.addListener(recorder);
        events.addListener(recorder);

        first.remove();
        first.remove();
        events.executed(null, query("select 1", Duration.ZERO));

        assertEquals(1, recorder.calls.size());
    }

    @Test
    void listenReceivesQueryExecutedEvents() {
        ConnectionEvents events = events(10);
        List<String> seen = new ArrayList<>();
        events.listen(e -> seen.add(e.sql()));

        events.executed(null, query("select 1", Duration.ZERO));

        assertEquals(Arrays.asList("select 1"), seen);
    }

    @Test
    void queryLogIsOffUnlessEnabledAndKeepsEntriesAfterDisable() {
        ConnectionEvents events = events(10, new Recorder());

        events.executed(null, query("select 0", Duration.ZERO));
        assertEquals(0, events.getQueryLog().size());
        events.enableQueryLog();
        assertTrue(events.isLoggingQueries());
        events.executed(null, query("select 1", Duration.ZERO, 5));
        events.disableQueryLog();
        events.executed(null, query("select 2", Duration.ZERO));

        assertFalse(events.isLoggingQueries());
        assertEquals(Arrays.asList(new LoggedQuery("select 1", Arrays.<Object>asList(5))), events.getQueryLog());
    }

    @Test
    void queryLogDropsTheOldestEntryAtTheLimit() {
        ConnectionEvents events = events(2);
        events.enableQueryLog();

        events.executed(null, query("a", Duration.ZERO));
        events.executed(null, query("b", Duration.ZERO));
        events.executed(null, query("c", Duration.ZERO));

        assertEquals(Arrays.asList("b", "c"), sqlOf(events.getQueryLog()));
    }

    @Test
    void getQueryLogReturnsAnUnmodifiableCopyAndFlushEmptiesTheLog() {
        ConnectionEvents events = events(5);
        events.enableQueryLog();
        events.executed(null, query("a", Duration.ZERO));
        List<LoggedQuery> copy = events.getQueryLog();

        assertThrows(UnsupportedOperationException.class, () -> copy.add(new LoggedQuery("x", new ArrayList<>())));
        events.flushQueryLog();

        assertEquals(1, copy.size());
        assertEquals(0, events.getQueryLog().size());
    }

    @Test
    void queryLogStoresCompactBindingsWhileTheEventKeepsTheRawOnes() {
        ConnectionEvents events = events(5);
        events.enableQueryLog();
        byte[] blob = new byte[4096];
        String longText = new String(new char[1030]).replace('\0', 'x');
        String exactText = new String(new char[1024]).replace('\0', 'y');
        QueryExecuted event = query("insert", Duration.ZERO, blob, longText, exactText, 7, null);

        events.executed(null, event);

        List<Object> logged = events.getQueryLog().get(0).bindings();
        assertEquals("<byte[4096]>", logged.get(0));
        assertEquals(new String(new char[1024]).replace('\0', 'x') + "...(1030 chars)", logged.get(1));
        assertEquals(exactText, logged.get(2));
        assertEquals(7, logged.get(3));
        assertEquals(null, logged.get(4));
        assertEquals(4096, ((byte[]) event.bindings().get(0)).length);
        assertEquals(longText, event.bindings().get(1));
    }

    @Test
    void queryLogCutDoesNotSplitASurrogatePair() {
        ConnectionEvents events = events(5);
        events.enableQueryLog();
        String text = new String(new char[1023]).replace('\0', 'x') + "😀" + "tail";

        events.executed(null, query("s", Duration.ZERO, text));

        String logged = (String) events.getQueryLog().get(0).bindings().get(0);
        assertEquals(new String(new char[1023]).replace('\0', 'x') + "...(" + text.length() + " chars)", logged);
    }

    @Test
    void perQueryHandlerFiresOnlyForStatementsStrictlySlowerThanTheThreshold() {
        ConnectionEvents events = events(5);
        List<String> slow = new ArrayList<>();
        events.whenQueryTakesLongerThan(Duration.ofMillis(10), e -> slow.add(e.sql()));

        events.executed(null, query("fast", Duration.ofMillis(5)));
        events.executed(null, query("edge", Duration.ofMillis(10)));
        events.executed(null, query("slow", Duration.ofMillis(11)));

        assertEquals(Arrays.asList("slow"), slow);
    }

    @Test
    void removedPerQueryHandlerNoLongerFires() {
        ConnectionEvents events = events(5, new Recorder());
        AtomicInteger count = new AtomicInteger();
        Registration registration = events.whenQueryTakesLongerThan(Duration.ZERO, e -> count.incrementAndGet());

        events.executed(null, query("a", Duration.ofMillis(1)));
        registration.remove();
        registration.remove();
        events.executed(null, query("b", Duration.ofMillis(1)));

        assertEquals(1, count.get());
    }

    @Test
    void totalTimeHandlerFiresOnceUntilTheTotalIsReset() {
        ConnectionEvents events = events(5);
        AtomicInteger count = new AtomicInteger();
        events.whenQueryingForLongerThan(Duration.ofMillis(10), (connection, e) -> count.incrementAndGet());

        events.executed(null, query("a", Duration.ofMillis(6)));
        assertEquals(0, count.get());
        events.executed(null, query("b", Duration.ofMillis(6)));
        events.executed(null, query("c", Duration.ofMillis(6)));
        assertEquals(1, count.get());
        assertEquals(Duration.ofMillis(18), events.totalQueryDuration());

        events.resetTotalQueryDuration();
        assertEquals(Duration.ZERO, events.totalQueryDuration());
        events.executed(null, query("d", Duration.ofMillis(11)));
        events.executed(null, query("e", Duration.ofMillis(1)));

        assertEquals(2, count.get());
    }

    @Test
    void totalTimeHandlerReceivesTheStatementThatCrossedTheThreshold() {
        ConnectionEvents events = events(5);
        List<String> seen = new ArrayList<>();
        events.whenQueryingForLongerThan(Duration.ofMillis(5), (connection, e) -> seen.add(e.sql()));

        events.executed(null, query("a", Duration.ofMillis(3)));
        events.executed(null, query("b", Duration.ofMillis(3)));

        assertEquals(Arrays.asList("b"), seen);
    }

    @Test
    void slowHandlersRefuseNegativeThresholdsAndNulls() {
        ConnectionEvents events = events(5);

        assertThrows(IllegalArgumentException.class,
                () -> events.whenQueryTakesLongerThan(Duration.ofMillis(-1), e -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> events.whenQueryingForLongerThan(Duration.ofMillis(-1), (c, e) -> { }));
        assertThrows(NullPointerException.class, () -> events.whenQueryTakesLongerThan(null, e -> { }));
        assertThrows(NullPointerException.class, () -> events.whenQueryTakesLongerThan(Duration.ZERO, null));
        assertThrows(NullPointerException.class, () -> events.addListener(null));
        assertThrows(NullPointerException.class, () -> events.listen(null));
    }

    @Test
    void statementsRunByAListenerFireNoEventsAndAreNotLogged() {
        ConnectionEvents events = events(5);
        events.enableQueryLog();
        List<String> seen = new ArrayList<>();
        events.listen(e -> {
            seen.add(e.sql());
            assertTrue(events.isDispatching());
            events.executed(null, query("inner", Duration.ZERO));
        });

        events.executed(null, query("outer", Duration.ZERO));

        assertEquals(Arrays.asList("outer"), seen);
        assertEquals(Arrays.asList("outer"), sqlOf(events.getQueryLog()));
        assertFalse(events.isDispatching());
    }

    @Test
    void transactionAndFailureEventsInsideAListenerAreSkippedToo() {
        Recorder inner = new Recorder();
        ConnectionEvents events = events(5, inner);
        events.listen(e -> {
            events.transaction(TransactionPhase.BEGINNING, level(1));
            events.failed(failure("inner"));
        });

        events.executed(null, query("outer", Duration.ZERO));

        assertEquals(Arrays.asList("executed:outer"), inner.calls);
    }

    @Test
    void theGuardIsPerThread() throws InterruptedException {
        ConnectionEvents events = events(5);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        events.listen(e -> seen.add(e.sql()));
        events.listen(e -> {
            if (e.sql().equals("outer")) {
                Thread other = new Thread(() -> events.executed(null, query("other thread", Duration.ZERO)));
                other.start();
                try {
                    other.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        events.executed(null, query("outer", Duration.ZERO));

        assertTrue(seen.contains("other thread"));
    }

    @Test
    void guardIsReleasedAfterAListenerFails() {
        ConnectionEvents events = events(5);
        events.listen(e -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(ListenerFailedException.class, () -> events.executed(null, query("a", Duration.ZERO)));

        assertFalse(events.isDispatching());
    }

    @Test
    void listenerFailureIsWrappedAfterAllListenersRanWithTheRestSuppressed() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        Recorder last = new Recorder();
        ConnectionEvents events = events(5);
        events.listen(e -> {
            throw first;
        });
        events.listen(e -> {
            throw second;
        });
        events.addListener(last);
        events.enableQueryLog();

        ListenerFailedException thrown = assertThrows(ListenerFailedException.class,
                () -> events.executed(null, query("insert", Duration.ZERO)));

        assertSame(first, thrown.getCause());
        assertEquals(Arrays.asList(second), Arrays.asList(first.getSuppressed()));
        assertTrue(thrown.getMessage().contains("already succeeded"));
        assertEquals(Arrays.asList("executed:insert"), last.calls);
        assertEquals(1, events.getQueryLog().size());
    }

    @Test
    void slowHandlerFailureIsWrappedLikeAListenerFailure() {
        ConnectionEvents events = events(5);
        events.whenQueryTakesLongerThan(Duration.ZERO, e -> {
            throw new IllegalStateException("slow handler");
        });

        ListenerFailedException thrown = assertThrows(ListenerFailedException.class,
                () -> events.executed(null, query("a", Duration.ofMillis(1))));

        assertEquals("slow handler", thrown.getCause().getMessage());
    }

    @Test
    void failedFiresListenersAndSuppressesTheirFailuresOntoTheQueryException() {
        Recorder recorder = new Recorder();
        IllegalStateException listenerFailure = new IllegalStateException("listener");
        ConnectionEvents events = events(5, new ConnectionListener() {
            @Override
            public void queryFailed(QueryFailed event) {
                throw listenerFailure;
            }
        }, recorder);
        QueryFailed event = failure("update t");

        events.failed(event);

        assertEquals(Arrays.asList("failed:update t"), recorder.calls);
        assertEquals(Arrays.asList(listenerFailure), Arrays.asList(event.exception().getSuppressed()));
        assertFalse(events.isDispatching());
    }

    @Test
    void failedDoesNotLogOrCountTime() {
        ConnectionEvents events = events(5, new Recorder());
        events.enableQueryLog();

        events.failed(failure("update t"));

        assertEquals(0, events.getQueryLog().size());
        assertEquals(Duration.ZERO, events.totalQueryDuration());
    }

    @Test
    void transactionPhasesReachTheMatchingListenerMethod() {
        Recorder recorder = new Recorder();
        ConnectionEvents events = events(5, recorder);

        events.transaction(TransactionPhase.BEGINNING, level(1));
        events.transaction(TransactionPhase.COMMITTING, level(2));
        events.transaction(TransactionPhase.COMMITTED, level(2));
        events.transaction(TransactionPhase.ROLLED_BACK, level(1));

        assertEquals(Arrays.asList("beginning:1", "committing:2", "committed:2", "rolledBack:1"), recorder.calls);
    }

    @Test
    void committingFailurePropagatesAtOnceWithoutRunningLaterListeners() {
        IllegalStateException failure = new IllegalStateException("veto");
        Recorder later = new Recorder();
        ConnectionEvents events = events(5, new ConnectionListener() {
            @Override
            public void transactionCommitting(TransactionEvent event) {
                throw failure;
            }
        }, later);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> events.transaction(TransactionPhase.COMMITTING, level(1)));

        assertSame(failure, thrown);
        assertEquals(0, later.calls.size());
        assertFalse(events.isDispatching());
    }

    @Test
    void committedFailuresRunEveryListenerThenThrowTheFirstWithTheRestSuppressed() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        Recorder last = new Recorder();
        ConnectionEvents events = events(5, new ConnectionListener() {
            @Override
            public void transactionCommitted(TransactionEvent event) {
                throw first;
            }
        }, new ConnectionListener() {
            @Override
            public void transactionCommitted(TransactionEvent event) {
                throw second;
            }
        }, last);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> events.transaction(TransactionPhase.COMMITTED, level(1)));

        assertSame(first, thrown);
        assertEquals(Arrays.asList(second), Arrays.asList(first.getSuppressed()));
        assertEquals(Arrays.asList("committed:1"), last.calls);
    }

    @Test
    void rolledBackFailureAlsoRunsEveryListener() {
        Recorder last = new Recorder();
        ConnectionEvents events = events(5, new ConnectionListener() {
            @Override
            public void transactionRolledBack(TransactionEvent event) {
                throw new IllegalStateException("boom");
            }
        }, last);

        assertThrows(IllegalStateException.class,
                () -> events.transaction(TransactionPhase.ROLLED_BACK, level(1)));

        assertEquals(Arrays.asList("rolledBack:1"), last.calls);
    }

    @Test
    void redactedReplacesEveryBindingWithAQuestionMarkAndLeavesTheOriginal() {
        QueryExecuted event = query("select ?", Duration.ofMillis(3), "secret", null);

        QueryExecuted redacted = event.redacted();

        assertEquals(Arrays.<Object>asList("?", "?"), redacted.bindings());
        assertEquals(Arrays.<Object>asList("secret", null), event.bindings());
        assertEquals(event.sql(), redacted.sql());
        assertEquals(event.time(), redacted.time());
        assertFalse(event.toString().contains("secret"));
    }

    @Test
    void eventBindingsAreCopiedFromTheCallersList() {
        byte[] blob = {1, 2};
        List<Object> bindings = new ArrayList<>(Arrays.<Object>asList(blob, "a"));
        QueryExecuted event = new QueryExecuted("main", QueryKind.INSERT, "i", bindings, Duration.ZERO, 1);

        bindings.clear();
        blob[0] = 9;

        assertEquals(2, event.bindings().size());
        assertEquals(1, ((byte[]) event.bindings().get(0))[0]);
        assertThrows(UnsupportedOperationException.class, () -> event.bindings().add(1));
    }

    @Test
    void settingsValidationRefusesAZeroLimit() {
        assertThrows(IllegalArgumentException.class, () -> events(0));
        assertThrows(IllegalArgumentException.class, () -> events(-1));
    }

    private static List<String> sqlOf(List<LoggedQuery> log) {
        List<String> sql = new ArrayList<>();
        for (LoggedQuery query : log) {
            sql.add(query.sql());
        }
        return sql;
    }

    @Test
    void queryLogKeepsBindingsInCompactFormWhenTheyMayBeShown() {
        ConnectionEvents events = new ConnectionEvents(5, Collections.<ConnectionListener>emptyList(), true);
        events.enableQueryLog();

        events.executed(null, query("select ?", Duration.ZERO, "secret", 7));

        assertEquals(Arrays.<Object>asList("secret", 7), events.getQueryLog().get(0).bindings());
    }

    @Test
    void queryLogStoresEveryBindingAsAQuestionMarkWhenBindingsMustStayHidden() {
        ConnectionEvents events = new ConnectionEvents(5, Collections.<ConnectionListener>emptyList(), false);
        events.enableQueryLog();
        QueryExecuted event = query("select ?", Duration.ZERO, "secret", 7, null, new byte[3]);

        events.executed(null, event);

        assertEquals(Arrays.<Object>asList("?", "?", "?", "?"), events.getQueryLog().get(0).bindings());
        assertEquals("secret", event.bindings().get(0));
    }

    @Test
    void failedKeepsRunningListenersAfterAnErrorAndSuppressesIt() {
        Recorder recorder = new Recorder();
        AssertionError error = new AssertionError("listener");
        ConnectionEvents events = events(5, new ConnectionListener() {
            @Override
            public void queryFailed(QueryFailed event) {
                throw error;
            }
        }, recorder);
        QueryFailed event = failure("update t");

        events.failed(event);

        assertEquals(Arrays.asList("failed:update t"), recorder.calls);
        assertEquals(Arrays.asList(error), Arrays.asList(event.exception().getSuppressed()));
        assertFalse(events.isDispatching());
    }

    @Test
    void executedRunsTheOtherListenersAfterAnErrorAndThrowsTheError() {
        Recorder recorder = new Recorder();
        AssertionError error = new AssertionError("listener");
        ConnectionEvents events = events(5, new ConnectionListener() {
            @Override
            public void queryExecuted(QueryExecuted event) {
                throw error;
            }
        }, recorder);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> events.executed(null, query("a", Duration.ZERO)));

        assertSame(error, thrown);
        assertEquals(Arrays.asList("executed:a"), recorder.calls);
        assertFalse(events.isDispatching());
    }
}
