package org.aristonis.diwan.database.testing;

import java.util.Arrays;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Truncate mode over self-referencing keys declared {@code restrictOnDelete()}. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class SQLiteRestrictTruncateTest {

    private static final Connection CONNECTION = Fixtures.sqlite("sqlite-restrict-truncate");

    @RegisterExtension
    final RefreshDatabase db = RefreshDatabase.truncate(() -> SQLiteQuery.of(CONNECTION), SQLiteSchema::of,
            Arrays.asList(new CreateCategories()));

    @Test
    void a1CommitsATreeOfRows(DatabaseAssertions assertions, QueryFactory query) {
        query.table("categories").insert(Map.of("id", 1, "name", "root"));
        query.table("categories").insert(Map.of("id", 2, "name", "leaf", "parent_id", 1));

        assertions.assertCount("categories", 2);
    }

    @Test
    void a2TheTreeWasEmptied(DatabaseAssertions assertions) {
        assertions.assertEmpty("categories");
    }

    static final class CreateCategories extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000001_create_categories";
        }

        @Override
        public void up(Schema schema) {
            schema.create("categories", table -> {
                table.id();
                table.string("name");
                table.foreignId("parent_id").nullable().constrained("categories").restrictOnDelete();
            });
        }
    }
}
