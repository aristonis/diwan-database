package org.aristonis.diwan.database.eloquent;

import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Model after-commit listeners on SQLite: commit, rollback, no transaction, saveOrFail and an observer. */
class SQLiteAfterCommitTest {

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
    void listenerRunsAfterCommitAndSeesTheRow() {
        AfterCommitFlows.listenerRunsAfterCommitAndSeesTheRow(fx);
    }

    @Test
    void rollbackDropsTheListener() {
        AfterCommitFlows.rollbackDropsTheListener(fx);
    }

    @Test
    void saveOutsideATransactionRunsAtOnce() {
        AfterCommitFlows.saveOutsideATransactionRunsAtOnce(fx);
    }

    @Test
    void saveOrFailRunsAfterItsOwnCommit() {
        AfterCommitFlows.saveOrFailRunsAfterItsOwnCommit(fx);
    }

    @Test
    void observerDefersOnlyNonHaltingMethods() {
        AfterCommitFlows.observerDefersOnlyNonHaltingMethods(fx);
    }
}
