package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * What every truncate-mode test class must satisfy, whatever the database. Tests run in name order:
 * the first commits data, the next ones prove the tables were emptied and the migrations table kept.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
abstract class TruncateContract {

    private static final AtomicBoolean FIRED = new AtomicBoolean();

    @RegisterExtension
    final RefreshDatabase db = create();

    /** Creates the truncate extension for the database under test. */
    abstract RefreshDatabase create();

    @Test
    void a1CommittedDataStaysAndAfterCommitCallbacksFire(DatabaseAssertions assertions, Connection connection,
            QueryFactory query) {
        FIRED.set(false);

        connection.transaction(tx -> {
            query.on(tx).table("users").insert(Map.of("name", "ann"));
            tx.afterCommit(() -> FIRED.set(true));
            return null;
        });
        Object userId = query.table("users").first().orElseThrow().get("id");
        query.table("posts").insert(Map.of("user_id", userId, "title", "hello"));

        assertTrue(FIRED.get());
        assertions.assertCount("users", 1);
        assertions.assertCount("posts", 1);
    }

    @Test
    void a2TablesAreEmptyAndTheMigrationsTableIsKept(DatabaseAssertions assertions, QueryFactory query) {
        assertions.assertEmpty("users");
        assertions.assertEmpty("posts");
        assertTrue(query.table("migrations").count() > 0);
    }

    @Test
    void a3IdentitiesRestartAfterTheTruncate(QueryFactory query) {
        query.table("users").insert(Map.of("name", "carl"));

        assertEquals(1L, ((Number) query.table("users").first().orElseThrow().get("id")).longValue());
    }
}
