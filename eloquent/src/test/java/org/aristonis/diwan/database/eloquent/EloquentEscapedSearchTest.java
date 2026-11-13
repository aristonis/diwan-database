package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.diwan.database.eloquent.TestModels.User;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.SearchOptions;
import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class EloquentEscapedSearchTest {

    private static final Pattern SEARCH_NAME = Pattern.compile("^(orWhere|where)(Not)?(Contains|StartsWith|EndsWith)$");

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

    @Test
    void searchOptionsForwardersBuildTheSameSqlAsTheQueryBuilder() {
        SearchOptions o = SearchOptions.DEFAULT;
        EloquentBuilder<User> model = users.query().whereContains("a", "50%", o).orWhereContains("b", "x", o)
                .whereNotContains("c", "y", o).orWhereNotContains("d", "z", o)
                .whereStartsWith("e", "_", o).orWhereStartsWith("f", "!", o)
                .whereNotStartsWith("g", "h", o).orWhereNotStartsWith("i", "j", o)
                .whereEndsWith("k", "l", o).orWhereEndsWith("m", "n", o)
                .whereNotEndsWith("o", "p", o).orWhereNotEndsWith("q", "r", o);
        Builder base = fake.db.table("users").whereContains("a", "50%", o).orWhereContains("b", "x", o)
                .whereNotContains("c", "y", o).orWhereNotContains("d", "z", o)
                .whereStartsWith("e", "_", o).orWhereStartsWith("f", "!", o)
                .whereNotStartsWith("g", "h", o).orWhereNotStartsWith("i", "j", o)
                .whereEndsWith("k", "l", o).orWhereEndsWith("m", "n", o)
                .whereNotEndsWith("o", "p", o).orWhereNotEndsWith("q", "r", o);

        assertEquals(base.toSql(), model.toSql());
        assertEquals(base.getBindings(), model.getBindings());
    }

    @Test
    void booleanForwardersBuildTheSameSqlAsTheOptionsForwarders() {
        EloquentBuilder<User> flag = users.query().whereContains("a", "x", false).orWhereNotStartsWith("b", "y", false)
                .whereEndsWith("c", "z", false);
        EloquentBuilder<User> options = users.query().whereContains("a", "x", SearchOptions.DEFAULT)
                .orWhereNotStartsWith("b", "y", SearchOptions.DEFAULT).whereEndsWith("c", "z", SearchOptions.DEFAULT);

        assertEquals(options.toSql(), flag.toSql());
        assertEquals(options.getBindings(), flag.getBindings());
    }

    @Test
    void searchOptionsForwardersRefuseNullOptions() {
        assertThrows(NullPointerException.class, () -> users.query().whereContains("a", "x", (SearchOptions) null));
    }

    @Test
    void everyBooleanSearchForwarderIsDeprecatedForRemoval() {
        List<Method> methods = searchMethods(boolean.class);

        assertEquals(12, methods.size());
        for (Method method : methods) {
            Deprecated deprecated = method.getAnnotation(Deprecated.class);
            assertTrue(deprecated != null && deprecated.forRemoval() && "0.5.0".equals(deprecated.since()));
        }
    }

    @Test
    void everyOptionsSearchForwarderIsNotDeprecated() {
        List<Method> methods = searchMethods(SearchOptions.class);

        assertEquals(12, methods.size());
        for (Method method : methods) {
            assertFalse(method.isAnnotationPresent(Deprecated.class));
        }
    }

    private static List<Method> searchMethods(Class<?> modeType) {
        List<Method> found = new ArrayList<>();
        for (Method method : EloquentBuilder.class.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (SEARCH_NAME.matcher(method.getName()).matches() && types.length == 3 && types[2] == modeType) {
                found.add(method);
            }
        }
        return found;
    }
}
