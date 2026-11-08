package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.query.Builder;
import org.junit.jupiter.api.Test;

class EloquentEscapedSearchTest {

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db);

    @Test
    void searchForwardersBuildTheSameSqlAsTheQueryBuilder() {
        EloquentBuilder<User> model = users.query().whereContains("a", "50%").orWhereContains("b", "x", false)
                .whereNotContains("c", "y").orWhereNotContains("d", "z", false)
                .whereStartsWith("e", "_").orWhereStartsWith("f", "!", false)
                .whereNotStartsWith("g", "h").orWhereNotStartsWith("i", "j", false)
                .whereEndsWith("k", "l").orWhereEndsWith("m", "n", false)
                .whereNotEndsWith("o", "p").orWhereNotEndsWith("q", "r", false);
        Builder base = fake.db.table("users").whereContains("a", "50%").orWhereContains("b", "x", false)
                .whereNotContains("c", "y").orWhereNotContains("d", "z", false)
                .whereStartsWith("e", "_").orWhereStartsWith("f", "!", false)
                .whereNotStartsWith("g", "h").orWhereNotStartsWith("i", "j", false)
                .whereEndsWith("k", "l").orWhereEndsWith("m", "n", false)
                .whereNotEndsWith("o", "p").orWhereNotEndsWith("q", "r", false);

        assertEquals(base.toSql(), model.toSql());
        assertEquals(base.getBindings(), model.getBindings());
    }

    @Test
    void searchForwardersRefuseANullValue() {
        assertThrows(IllegalArgumentException.class, () -> users.query().whereContains("a", null));
    }
}
