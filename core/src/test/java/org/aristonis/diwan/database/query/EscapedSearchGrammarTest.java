package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class EscapedSearchGrammarTest {

    private static final Pattern SEARCH_NAME = Pattern.compile("^(orWhere|where)(Not)?(Contains|StartsWith|EndsWith)$");

    private static final List<String> SEARCH_NAMES = List.of("whereContains", "orWhereContains", "whereNotContains",
            "orWhereNotContains", "whereStartsWith", "orWhereStartsWith", "whereNotStartsWith",
            "orWhereNotStartsWith", "whereEndsWith", "orWhereEndsWith", "whereNotEndsWith", "orWhereNotEndsWith");

    /** Refuses case-sensitive settings in the hook and overrides the pattern without calling super. */
    private static final class RefusingGrammar extends QueryGrammar {
        RefusingGrammar() {
            super("");
        }

        @Override
        protected void validateSearchOptions(SearchOptions options) {
            if (options.isCaseSensitive()) {
                throw unsupported("Case-sensitive search");
            }
        }

        @Override
        public String searchPattern(String value, SearchWhere.Anchor anchor, SearchOptions options) {
            return "marker";
        }
    }

    /** Overrides only the earlier boolean form and records what it received. */
    private static final class BooleanOnlyGrammar extends QueryGrammar {
        BooleanOnlyGrammar() {
            super("");
        }

        @Override
        public String searchPattern(String value, SearchWhere.Anchor anchor, boolean caseSensitive) {
            return caseSensitive + "|" + value;
        }
    }

    /** Overrides both forms; the options form is the one that runs. */
    private static final class BothFormsGrammar extends QueryGrammar {
        BothFormsGrammar() {
            super("");
        }

        @Override
        public String searchPattern(String value, SearchWhere.Anchor anchor, SearchOptions options) {
            return "options|" + value;
        }

        @Override
        public String searchPattern(String value, SearchWhere.Anchor anchor, boolean caseSensitive) {
            return "boolean|" + value;
        }
    }

    private static Builder table(QueryGrammar grammar) {
        return QueryFactory.of(TestQueries.connection(""), grammar, new Processor()).table("t");
    }

    private static Builder chain(Class<?> modeType, Object mode) throws ReflectiveOperationException {
        Builder query = db().table("t");
        for (String name : SEARCH_NAMES) {
            Method method = modeType == null
                    ? Builder.class.getMethod(name, String.class, String.class)
                    : Builder.class.getMethod(name, String.class, String.class, modeType);
            query = (Builder) (modeType == null ? method.invoke(query, "a", "x") : method.invoke(query, "a", "x", mode));
        }
        return query;
    }

    private static List<Method> searchMethods(Class<?> modeType) {
        List<Method> found = new ArrayList<>();
        for (Method method : Builder.class.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (SEARCH_NAME.matcher(method.getName()).matches() && types.length == 3 && types[2] == modeType) {
                found.add(method);
            }
        }
        return found;
    }

    private static final String LIKE = "select * from \"t\" where \"a\" like ? escape '!'";

    @Test
    void containsStartsAndEndsWrapTheEscapedText() {
        Builder contains = db().table("t").whereContains("a", "50%_!*?[\\");
        Builder starts = db().table("t").whereStartsWith("a", "50%_!*?[\\");
        Builder ends = db().table("t").whereEndsWith("a", "50%_!*?[\\");

        assertEquals(LIKE, contains.toSql());
        assertEquals(List.of("%50!%!_!!*?[\\%"), contains.getBindings());
        assertEquals(LIKE, starts.toSql());
        assertEquals(List.of("50!%!_!!*?[\\%"), starts.getBindings());
        assertEquals(LIKE, ends.toSql());
        assertEquals(List.of("%50!%!_!!*?[\\"), ends.getBindings());
    }

    @Test
    void notAndOrFormsCombineWithOtherWheres() {
        Builder query = db().table("t").where("x", 1).whereNotContains("a", "p").orWhereStartsWith("b", "q")
                .orWhereNotEndsWith("c", "r");

        assertEquals("select * from \"t\" where \"x\" = ? and \"a\" not like ? escape '!'"
                + " or \"b\" like ? escape '!' or \"c\" not like ? escape '!'", query.toSql());
        assertEquals(List.of(1, "%p%", "q%", "%r"), query.getBindings());
    }

    @Test
    void caseInsensitiveFlagIsTheDefault() {
        assertEquals(db().table("t").whereContains("a", "x").toSql(),
                db().table("t").whereContains("a", "x", false).toSql());
    }

    @Test
    void theBaseGrammarRefusesCaseSensitiveSearch() {
        assertThrows(UnsupportedOperationException.class,
                () -> db().table("t").whereContains("a", "x", true).toSql());
    }

    @Test
    void nullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> db().table("t").whereContains("a", null));
        assertThrows(IllegalArgumentException.class, () -> db().table("t").orWhereNotEndsWith("a", null, false));
        assertThrows(NullPointerException.class, () -> db().table("t").whereStartsWith(null, "x"));

        Builder empty = db().table("t").whereContains("a", "");

        assertEquals(LIKE, empty.toSql());
        assertEquals(List.of("%%"), empty.getBindings());
    }

    @Test
    void flaglessAndDefaultOptionsFormsCompileEqualSqlAndBindings() throws ReflectiveOperationException {
        Builder flagless = chain(null, null);
        Builder options = chain(SearchOptions.class, SearchOptions.DEFAULT);

        assertEquals(flagless.toSql(), options.toSql());
        assertEquals(flagless.getBindings(), options.getBindings());
    }

    @Test
    void booleanFalseAndDefaultOptionsFormsCompileEqualSqlAndBindings() throws ReflectiveOperationException {
        Builder flag = chain(boolean.class, false);
        Builder options = chain(SearchOptions.class, SearchOptions.DEFAULT);

        assertEquals(flag.toSql(), options.toSql());
        assertEquals(flag.getBindings(), options.getBindings());
    }

    @Test
    void theBaseGrammarRefusesCaseSensitiveOptions() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> db().table("t").whereContains("a", "x", SearchOptions.caseSensitive()));

        assertEquals(UnsupportedOperationException.class, error.getClass());
        assertTrue(error.getMessage().contains("TestGrammar"));
    }

    @Test
    void nullOptionsAreRefusedWithANullPointerException() {
        assertThrows(NullPointerException.class, () -> db().table("t").whereContains("a", "x", (SearchOptions) null));
    }

    @Test
    void nullValueWithOptionsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> db().table("t").whereContains("a", null, SearchOptions.DEFAULT));
    }

    @Test
    void aGrammarThatOverridesTheHookCanRefuseASetting() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> table(new RefusingGrammar()).whereContains("a", "x", SearchOptions.caseSensitive()));

        assertEquals(UnsupportedOperationException.class, error.getClass());
        assertTrue(error.getMessage().contains("Case-sensitive search"));
        assertTrue(error.getMessage().contains("RefusingGrammar"));
    }

    @Test
    void theHookRunsEvenWhenSearchPatternIsOverriddenWithoutSuper() {
        assertThrows(UnsupportedOperationException.class,
                () -> table(new RefusingGrammar()).orWhereNotEndsWith("a", "x", SearchOptions.caseSensitive()));
    }

    @Test
    void aSettingTheHookAcceptsIsNotRefused() {
        Builder query = table(new RefusingGrammar()).whereContains("a", "x", SearchOptions.DEFAULT);

        assertEquals(List.of("marker"), query.getBindings());
    }

    @Test
    void aGrammarOverridingOnlyTheBooleanSearchPatternIsStillCalled() {
        Builder flagless = table(new BooleanOnlyGrammar()).whereContains("a", "x");
        Builder defaults = table(new BooleanOnlyGrammar()).whereContains("a", "x", SearchOptions.DEFAULT);
        Builder sensitive = table(new BooleanOnlyGrammar()).whereContains("a", "x", SearchOptions.caseSensitive());

        assertEquals(List.of("false|x"), flagless.getBindings());
        assertEquals(List.of("false|x"), defaults.getBindings());
        assertEquals(List.of("true|x"), sensitive.getBindings());
    }

    @Test
    void theBooleanFormOnTheBuilderStillReachesAnOverrideOfTheBooleanSearchPattern() {
        Builder query = table(new BooleanOnlyGrammar()).whereStartsWith("a", "x", true);

        assertEquals(List.of("true|x"), query.getBindings());
    }

    @Test
    void aGrammarOverridingTheOptionsFormWinsOverTheBooleanForm() {
        Builder query = table(new BothFormsGrammar()).whereEndsWith("a", "x", SearchOptions.DEFAULT);

        assertEquals(List.of("options|x"), query.getBindings());
    }

    @Test
    void everyBooleanSearchOverloadIsDeprecatedForRemoval() {
        List<Method> methods = searchMethods(boolean.class);

        assertEquals(12, methods.size());
        for (Method method : methods) {
            Deprecated deprecated = method.getAnnotation(Deprecated.class);
            assertTrue(deprecated != null && deprecated.forRemoval() && "0.5.0".equals(deprecated.since()));
        }
    }

    @Test
    void everyOptionsOverloadIsNotDeprecated() {
        List<Method> methods = searchMethods(SearchOptions.class);

        assertEquals(12, methods.size());
        for (Method method : methods) {
            assertFalse(method.isAnnotationPresent(Deprecated.class));
        }
    }

    @Test
    void searchPatternBooleanIsDeprecatedAndOptionsFormIsNot() throws NoSuchMethodException {
        Method flag = QueryGrammar.class.getMethod("searchPattern", String.class, SearchWhere.Anchor.class,
                boolean.class);
        Method options = QueryGrammar.class.getMethod("searchPattern", String.class, SearchWhere.Anchor.class,
                SearchOptions.class);

        assertTrue(flag.getAnnotation(Deprecated.class).forRemoval());
        assertFalse(options.isAnnotationPresent(Deprecated.class));
    }

    @Test
    void searchWhereBuiltFromOptionsEqualsTheBooleanConstructor() {
        for (boolean caseSensitive : new boolean[] {false, true}) {
            SearchWhere fromFlag = new SearchWhere("and", "a", SearchWhere.Anchor.ENDS_WITH, caseSensitive, true);
            SearchWhere fromOptions = new SearchWhere("and", "a", SearchWhere.Anchor.ENDS_WITH,
                    SearchOptions.DEFAULT.withCaseSensitive(caseSensitive), true);

            assertEquals(fromFlag.options(), fromOptions.options());
            assertEquals(fromFlag.caseSensitive(), fromOptions.caseSensitive());
            assertEquals(fromFlag.column(), fromOptions.column());
            assertEquals(fromFlag.anchor(), fromOptions.anchor());
            assertEquals(fromFlag.not(), fromOptions.not());
            assertEquals(fromFlag.conjunction(), fromOptions.conjunction());
        }
    }

    @Test
    void searchWhereOptionsAndCaseSensitiveAgree() {
        SearchWhere where = new SearchWhere("or", "a", SearchWhere.Anchor.CONTAINS, SearchOptions.caseSensitive(), false);

        assertEquals(SearchOptions.caseSensitive(), where.options());
        assertEquals(true, where.caseSensitive());
    }

    @Test
    void whereLikeIsUnchanged() {
        Builder query = db().table("t").whereLike("a", "50%_!");

        assertEquals("select * from \"t\" where \"a\" like ?", query.toSql());
        assertEquals(List.of("50%_!"), query.getBindings());
    }
}
