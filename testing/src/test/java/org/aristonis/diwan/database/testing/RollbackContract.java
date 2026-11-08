package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * What every rollback-mode test class must satisfy, whatever the database. Tests run in name order:
 * each {@code a...} test leaves data behind and the next one proves it is gone. The extension is
 * registered through an instance field, which also covers the case without {@code beforeAll}.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
abstract class RollbackContract {

    @RegisterExtension
    final RefreshDatabase db = create();

    /** Creates the rollback extension for the database under test. */
    abstract RefreshDatabase create();

    @Test
    void a1RowInsertedThroughTheRootConnectionIsVisibleInTheTest(DatabaseAssertions assertions, QueryFactory query,
            Connection connection) {
        query.table("users").insert(Map.of("name", "ann"));

        assertions.assertCount("users", 1);
        assertions.assertHas("users", Map.of("name", "ann"));
        // A strict connection would refuse this call if it did not join the test's transaction.
        assertSame(connection, query.connection());
        assertNotNull(connection.select("select name from users"));
    }

    @Test
    void a2RowFromThePreviousTestIsGone(DatabaseAssertions assertions) {
        assertions.assertEmpty("users");
    }

    @Test
    void a3TransactionInsideTheTestBecomesASavepointAndStillRollsBack(DatabaseAssertions assertions,
            Connection connection) {
        connection.transaction(tx -> {
            tx.insert("insert into users (name) values (?)", "bob");
            return null;
        });

        assertions.assertHas("users", Map.of("name", "bob"));
    }

    @Test
    void a4SavepointDataFromThePreviousTestIsGone(DatabaseAssertions assertions) {
        assertions.assertEmpty("users");
    }

    @Test
    void a5FailedInnerTransactionKeepsTheOuterTestUsable(DatabaseAssertions assertions, Connection connection) {
        connection.insert("insert into users (name) values (?)", "kept");
        try {
            connection.transaction(tx -> {
                tx.insert("insert into users (name) values (?)", "dropped");
                throw new IllegalStateException("inner failure");
            });
        } catch (IllegalStateException expected) {
            // The savepoint rolled back; the test's own transaction goes on.
        }

        assertions.assertCount("users", 1);
        assertions.assertMissing("users", Map.of("name", "dropped"));
    }
}
