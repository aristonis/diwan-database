package org.aristonis.diwan.database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Listener fixture that records every event it receives, safe to read from another thread. */
final class RecordingListener implements ConnectionListener {

    private final List<QueryExecuted> executed = Collections.synchronizedList(new ArrayList<QueryExecuted>());
    private final List<QueryFailed> failed = Collections.synchronizedList(new ArrayList<QueryFailed>());
    private final List<TransactionEvent> transactionEvents =
            Collections.synchronizedList(new ArrayList<TransactionEvent>());
    private final List<String> phases = Collections.synchronizedList(new ArrayList<String>());

    List<QueryExecuted> executed() {
        synchronized (executed) {
            return new ArrayList<>(executed);
        }
    }

    List<QueryFailed> failed() {
        synchronized (failed) {
            return new ArrayList<>(failed);
        }
    }

    /** The transaction events in arrival order, as {@code phase:level}, for example {@code committed:1}. */
    List<String> phases() {
        synchronized (phases) {
            return new ArrayList<>(phases);
        }
    }

    List<TransactionEvent> transactionEvents() {
        synchronized (transactionEvents) {
            return new ArrayList<>(transactionEvents);
        }
    }

    List<String> executedSql() {
        List<String> sql = new ArrayList<>();
        for (QueryExecuted event : executed()) {
            sql.add(event.sql());
        }
        return sql;
    }

    @Override
    public void queryExecuted(QueryExecuted event) {
        executed.add(event);
    }

    @Override
    public void queryFailed(QueryFailed event) {
        failed.add(event);
    }

    @Override
    public void transactionBeginning(TransactionEvent event) {
        record("beginning", event);
    }

    @Override
    public void transactionCommitting(TransactionEvent event) {
        record("committing", event);
    }

    @Override
    public void transactionCommitted(TransactionEvent event) {
        record("committed", event);
    }

    @Override
    public void transactionRolledBack(TransactionEvent event) {
        record("rolledBack", event);
    }

    private void record(String phase, TransactionEvent event) {
        transactionEvents.add(event);
        phases.add(phase + ":" + event.level());
    }
}
