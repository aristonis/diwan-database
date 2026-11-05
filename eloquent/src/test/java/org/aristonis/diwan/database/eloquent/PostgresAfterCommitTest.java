package org.aristonis.diwan.database.eloquent;

import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Model after-commit listeners on a throwaway PostgreSQL, the same flows as on SQLite. Skipped when the server
 * binaries are missing, and failing when {@code DIWAN_PG_REQUIRED} is set.
 */
class PostgresAfterCommitTest {

    private EventFixtures fx;

    @BeforeEach
    void open() {
        fx = EventFixtures.openPostgres();
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
