package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Comment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphToExistenceTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToModels.prepare(db);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void whereHasPointsAtWhereHasMorph() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> Comment.QUERY.on(db.db).query().whereHas("commentable", query -> {
                }));

        assertTrue(thrown.getMessage().contains("whereHasMorph"), thrown.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void hasPointsAtWhereHasMorph() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> Comment.QUERY.on(db.db).query().has("commentable"));

        assertTrue(thrown.getMessage().contains("whereHasMorph"), thrown.getMessage());
    }
}
