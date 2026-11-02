package org.aristonis.diwan.database.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * Streams the rows of one JDBC result set, reading one row ahead. When its session opened a
 * transaction for it, closing ends that transaction too. Confined to the session's thread.
 */
final class JdbcRowCursor implements RowCursor {

    private enum State { OPEN, CONSUMED, CLOSED }

    private final JdbcSession session;
    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private final JdbcColumns columns;
    private final boolean ownsTransaction;
    private State state = State.OPEN;
    private boolean isIterated;
    private boolean hasFailed;
    private Map<String, Object> ahead;

    JdbcRowCursor(JdbcSession session, PreparedStatement statement, ResultSet resultSet, JdbcColumns columns,
            boolean ownsTransaction) {
        this.session = session;
        this.statement = statement;
        this.resultSet = resultSet;
        this.columns = columns;
        this.ownsTransaction = ownsTransaction;
    }

    @Override
    public Iterator<Map<String, Object>> iterator() {
        if (isIterated) {
            throw new IllegalStateException("A cursor can be iterated only once");
        }
        isIterated = true;
        return this;
    }

    @Override
    public boolean hasNext() {
        if (state != State.OPEN) {
            return false;
        }
        if (ahead != null) {
            return true;
        }
        boolean hasRow;
        try {
            hasRow = resultSet.next();
            if (hasRow) {
                ahead = columns.read(resultSet);
            }
        } catch (SQLException e) {
            hasFailed = true;
            throw session.translate(e);
        } catch (RuntimeException e) {
            // A half-read cursor must roll its own transaction back, never commit it.
            hasFailed = true;
            throw e;
        }
        if (!hasRow) {
            state = State.CONSUMED;
            release();
        }
        return hasRow;
    }

    @Override
    public Map<String, Object> next() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("The cursor is closed");
        }
        if (!hasNext()) {
            throw new NoSuchElementException("The cursor has no more rows");
        }
        Map<String, Object> row = ahead;
        ahead = null;
        return row;
    }

    @Override
    public void close() {
        if (state != State.OPEN) {
            return;
        }
        state = State.CLOSED;
        ahead = null;
        release();
    }

    @Override
    public boolean isClosed() {
        return state != State.OPEN;
    }

    // Result set, then statement, then the cursor's own transaction (which also turns auto-commit
    // back on); each step runs even when an earlier one failed, an Error included, and the first
    // failure is the one thrown.
    private void release() {
        List<Throwable> failures = new ArrayList<>();
        try {
            resultSet.close();
        } catch (Throwable e) {
            failures.add(e);
        }
        try {
            statement.close();
        } catch (Throwable e) {
            failures.add(e);
        }
        if (ownsTransaction) {
            List<Exception> transactionFailures = new ArrayList<>();
            try {
                session.endCursorTransaction(failures.isEmpty() && !hasFailed, transactionFailures);
            } catch (Throwable e) {
                failures.add(e);
            }
            failures.addAll(transactionFailures);
        }
        session.throwFirst(failures);
    }
}
