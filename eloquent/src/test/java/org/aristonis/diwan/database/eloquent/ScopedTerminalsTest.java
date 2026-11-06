package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.junit.jupiter.api.AfterEach;
import org.aristonis.diwan.database.query.QueryCursor;
import org.junit.jupiter.api.Test;

/**
 * The leak guard for global scopes. Guard one runs every terminal of a tenant-scoped builder and
 * checks each statement; a reflection check makes a new public terminal fail until it is in the
 * table. Guard two reads the builder-layer sources and fails on any use of the raw query field that is
 * not on an explicit allowlist, so a terminal cannot reach the database unscoped.
 */
class ScopedTerminalsTest {

    private static final String TENANT_SQL = "\"tenant_id\" = ?";

    private static final String PACKAGE_DIR = "src/main/java/org/aristonis/diwan/database/eloquent";

    private static final List<String> BUILDER_LAYERS = Arrays.asList("EloquentWheres", "EloquentScopes",
            "EloquentClauses", "EloquentCreators", "EloquentRelationQueries", "EloquentAggregates",
            "EloquentBuilder");

    private static final Pattern NOISE = Pattern.compile(
            "/\\*.*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'", Pattern.DOTALL);

    private static final Pattern WORD = Pattern.compile("\\bquery\\b");

    private static final Pattern ALLOWED_CALL = Pattern.compile("^\\.(?:where\\w*|orWhere\\w*|order\\w*|reorder|"
            + "select\\w*|addSelect|distinct|groupBy|having|join|leftJoin|limit|take|offset|skip|forPage|latest|"
            + "oldest|inRandomOrder|getOrders|getUnionOrders|getWheres|getJoins|clone|lockForUpdate|sharedLock|lock)\\b");

    /** Public methods that run no query of their own or only insert; named so a new one must be classified. */
    private static final Set<String> NOT_A_SCOPED_READ = new HashSet<>(Arrays.asList(
            "hydrate", "getModel", "getEagerLoads", "removedScopes",
            "make", "create", "forceCreate",
            "createOrFirst", "createOrRestore",
            // Writes that skip scopes, as in Laravel, where global scopes never apply to inserts.
            "insertOrIgnore", "upsert"));

    private static final Set<String> INSPECTION = new HashSet<>(Arrays.asList("toBase", "toSql", "getBindings"));

    /** One terminal: what to run, and the fewest reads it must make (paged terminals need two pages). */
    private static final class Entry {
        final Consumer<EloquentBuilder<Post>> run;
        final int minReads;

        Entry(int minReads, Consumer<EloquentBuilder<Post>> run) {
            this.minReads = minReads;
            this.run = run;
        }
    }

    @AfterEach
    void clearScopes() {
        ScopeFixtures.clearScopes();
    }

    private static void drain(Iterable<?> items) {
        for (Object ignored : items) {
            assertTrue(ignored != null);
        }
    }

    /** Every terminal whose statements must all carry the tenant predicate, keyed by name and variant. */
    private static Map<String, Entry> terminals() {
        Map<String, Entry> table = new LinkedHashMap<>();
        table.put("get", new Entry(1, q -> q.get()));
        table.put("first", new Entry(1, q -> q.first()));
        table.put("firstOrFail", new Entry(1, q -> q.firstOrFail()));
        table.put("firstOr", new Entry(1, q -> q.firstOr(() -> null)));
        table.put("firstWhere", new Entry(1, q -> q.firstWhere("title", "Post 1")));
        table.put("sole", new Entry(1, q -> q.where("id", 1).sole()));
        table.put("find(id)", new Entry(1, q -> q.find(1)));
        table.put("find(ids)", new Entry(1, q -> q.find(Arrays.asList(1, 2))));
        table.put("findMany", new Entry(1, q -> q.findMany(Arrays.asList(1, 2))));
        table.put("findOrFail", new Entry(1, q -> q.findOrFail(1)));
        table.put("findOrNew", new Entry(1, q -> q.findOrNew(1)));
        table.put("findOr", new Entry(1, q -> q.findOr(1, () -> null)));
        table.put("value", new Entry(1, q -> q.value("title")));
        table.put("pluck", new Entry(1, q -> q.pluck("title")));
        table.put("count", new Entry(1, q -> q.count()));
        table.put("exists", new Entry(1, q -> q.exists()));
        table.put("toBase.sum", new Entry(1, q -> q.toBase().sum("views")));
        table.put("toBase.max", new Entry(1, q -> q.toBase().max("id")));
        table.put("chunk", new Entry(2, q -> q.chunk(2, page -> true)));
        table.put("chunkById", new Entry(2, q -> q.chunkById(2, page -> true)));
        table.put("each", new Entry(2, q -> q.each(post -> true, 2)));
        table.put("lazy", new Entry(2, q -> drain(q.lazy(2))));
        table.put("lazyById", new Entry(2, q -> drain(q.lazyById(2))));
        table.put("cursor", new Entry(1, q -> {
            try (QueryCursor<Post> cursor = q.cursor()) {
                drain(cursor);
            }
        }));
        table.put("paginate", new Entry(2, q -> q.paginate(2)));
        table.put("simplePaginate", new Entry(1, q -> q.simplePaginate(2)));
        table.put("cursorPaginate", new Entry(1, q -> q.cursorPaginate(2)));
        table.put("update", new Entry(0, q -> q.update(Collections.singletonMap("title", "x"))));
        table.put("increment", new Entry(0, q -> q.increment("views")));
        table.put("decrement", new Entry(0, q -> q.decrement("views")));
        table.put("delete", new Entry(0, q -> q.delete()));
        table.put("forceDelete", new Entry(0, q -> q.forceDelete()));
        table.put("restore", new Entry(0, q -> q.restore()));
        return table;
    }

    /** Terminals that start with a lookup; their first statement must carry the tenant predicate. */
    private static Map<String, Consumer<EloquentBuilder<Post>>> lookups() {
        Map<String, Consumer<EloquentBuilder<Post>>> table = new LinkedHashMap<>();
        table.put("firstOrNew", q -> q.firstOrNew(row("title", "Post 1")));
        table.put("firstOrCreate", q -> q.firstOrCreate(row("title", "Post 1")));
        table.put("updateOrCreate", q -> q.updateOrCreate(row("title", "Post 1"), row("views", 0)));
        table.put("restoreOrCreate", q -> q.restoreOrCreate(row("title", "Post 4")));
        return table;
    }

    private static String nameOf(String label) {
        Matcher match = Pattern.compile("^[A-Za-z]+").matcher(label);
        assertTrue(match.find());
        return match.group();
    }

    private static void assertCarriesTenant(String label, Statement statement) {
        assertTrue(statement.sql().contains(TENANT_SQL), label + " sent no tenant predicate: " + statement);
        assertTrue(statement.bindings().contains(ScopeFixtures.TENANT_ID),
                label + " sent no tenant binding: " + statement);
    }

    @Test
    void everyTerminalAppliesTheTenantScope() throws Exception {
        for (Map.Entry<String, Entry> terminal : terminals().entrySet()) {
            try (ScopeFixtures fixtures = ScopeFixtures.open()) {
                fixtures.seed();
                EloquentBuilder<Post> query = fixtures.posts().query();

                terminal.getValue().run.accept(query);

                List<Statement> statements = fixtures.log.all();
                assertFalse(statements.isEmpty(), terminal.getKey() + " ran no statement");
                assertTrue(fixtures.log.reads().size() >= terminal.getValue().minReads,
                        terminal.getKey() + " ran fewer pages than expected: " + statements);
                for (Statement statement : statements) {
                    assertCarriesTenant(terminal.getKey(), statement);
                }
            }
        }
    }

    @Test
    void lookupTerminalsStartWithTheTenantScope() throws Exception {
        for (Map.Entry<String, Consumer<EloquentBuilder<Post>>> lookup : lookups().entrySet()) {
            try (ScopeFixtures fixtures = ScopeFixtures.open()) {
                fixtures.seed();

                lookup.getValue().accept(fixtures.posts().query());

                List<Statement> statements = fixtures.log.all();
                assertFalse(statements.isEmpty(), lookup.getKey() + " ran no statement");
                assertTrue(statements.get(0).isRead(), lookup.getKey() + " did not start with a read");
                assertCarriesTenant(lookup.getKey(), statements.get(0));
            }
        }
    }

    @Test
    void inspectionTerminalsShowTheTenantScope() throws Exception {
        try (ScopeFixtures fixtures = ScopeFixtures.open()) {
            EloquentBuilder<Post> query = fixtures.posts().query();

            assertTrue(query.toSql().contains(TENANT_SQL));
            assertTrue(query.getBindings().contains(ScopeFixtures.TENANT_ID));
            assertTrue(query.toBase().toSql().contains(TENANT_SQL));
            assertTrue(query.toBase().getBindings().contains(ScopeFixtures.TENANT_ID));
        }
    }

    @Test
    void everyPublicTerminalIsInTheTableOrNamedAsNotScoped() {
        Set<String> known = new HashSet<>(INSPECTION);
        known.addAll(NOT_A_SCOPED_READ);
        for (String label : terminals().keySet()) {
            known.add(nameOf(label));
        }
        for (String label : lookups().keySet()) {
            known.add(nameOf(label));
        }
        Set<String> unclassified = new TreeSet<>();
        for (Method method : EloquentBuilder.class.getMethods()) {
            boolean isBuilderStep = EloquentBuilder.class.isAssignableFrom(method.getReturnType());
            if (method.getDeclaringClass() == Object.class || method.isSynthetic() || method.isBridge()
                    || Modifier.isStatic(method.getModifiers()) || isBuilderStep) {
                continue;
            }
            if (!known.contains(method.getName())) {
                unclassified.add(method.getName());
            }
        }

        assertEquals(Collections.emptySet(), unclassified,
                "add each new terminal to terminals() or lookups(), or name it in NOT_A_SCOPED_READ");
    }

    @Test
    void rawEscapeHatchesAreUnscoped() throws Exception {
        try (ScopeFixtures fixtures = ScopeFixtures.open()) {
            fixtures.seed();

            List<Object> viaQuery = new ArrayList<>();
            fixtures.posts().query().query(base -> viaQuery.addAll(base.get()));
            List<?> viaBase = fixtures.posts().newModel().newBaseQuery().get();

            assertEquals(6, viaQuery.size());
            assertEquals(6, viaBase.size());
            for (Statement statement : fixtures.log.reads()) {
                assertFalse(statement.sql().contains("tenant_id"), statement.toString());
                assertFalse(statement.sql().contains("deleted_at"), statement.toString());
            }
        }
    }

    @Test
    void builderLayerSourcesNeverReadTheRawQuery() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String layer : BUILDER_LAYERS) {
            Path file = sourceDir().resolve(layer + ".java");
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            violations.addAll(rawQueryViolations(layer + ".java", source));
        }

        assertEquals(Collections.emptyList(), violations,
                "classify the new use: a terminal must go through scoped(), a clause adder joins the allowlist");
    }

    @Test
    void guardFlagsATerminalOnTheRawQuery() {
        assertEquals(1, rawQueryViolations("EloquentBuilder.java", "return query.get();").size());
        assertEquals(1, rawQueryViolations("EloquentBuilder.java", "int n = query.update(values);").size());
        assertEquals(1, rawQueryViolations("EloquentBuilder.java", "return mapped(query);").size());
        assertEquals(1, rawQueryViolations("EloquentBuilder.java", "return this.query.where(a);").size());
        assertEquals(1, rawQueryViolations("EloquentCreators.java", "return work.query;").size());
        assertEquals(1, rawQueryViolations("EloquentCreators.java", "return query;").size());
    }

    @Test
    void guardAcceptsClauseAddersGettersAndDeclarations() {
        String clean = String.join("\n",
                "final Builder query;",
                "this.query = Objects.requireNonNull(query, \"query\");",
                "super(source, model, query);",
                "query.where(column, value);",
                "query.orderBy(column);",
                "if (query.getOrders().isEmpty()) { }",
                "return b == query ? query.clone() : b;",
                "query(base -> base.limit(1));",
                "// query.get() inside a comment",
                "/* query.get() inside a block",
                "   comment */",
                "String text = \"query.get()\";");

        assertEquals(Collections.emptyList(), rawQueryViolations("EloquentClauses.java", clean));
    }

    @Test
    void guardAllowsTheWorkingCopyOnlyInTheScopesLayer() {
        String scoped = String.join("\n",
                "if (scopes.isEmpty()) {",
                "    return query;",
                "}",
                "int split = work.query.getWheres().size();",
                "return work.query;");

        assertEquals(Collections.emptyList(), rawQueryViolations("EloquentScopes.java", scoped));
        assertFalse(rawQueryViolations("EloquentClauses.java", scoped).isEmpty());
    }

    private static String stripNoise(String source) {
        StringBuilder out = new StringBuilder();
        Matcher noise = NOISE.matcher(source);
        int last = 0;
        while (noise.find()) {
            out.append(source, last, noise.start());
            String text = noise.group();
            if (text.startsWith("/*")) {
                out.append(text.replaceAll("[^\\n]", ""));
            } else if (text.startsWith("\"")) {
                out.append("\"\"");
            }
            last = noise.end();
        }
        out.append(source.substring(last));
        return out.toString();
    }

    /** Lists each use of the bare word {@code query} that is not on the allowlist. */
    static List<String> rawQueryViolations(String fileName, String source) {
        List<String> violations = new ArrayList<>();
        String[] lines = stripNoise(source).split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher word = WORD.matcher(lines[i]);
            while (word.find()) {
                if (!isAllowedUse(fileName, lines[i], word.start(), word.end())) {
                    violations.add(fileName + ":" + (i + 1) + ": " + lines[i].trim());
                }
            }
        }
        return violations;
    }

    private static boolean isAllowedUse(String fileName, String line, int start, int end) {
        String before = line.substring(0, start);
        String after = line.substring(end);
        boolean isScopesLayer = "EloquentScopes.java".equals(fileName);
        if (line.matches("^\\s*(?:import|package)\\s.*")) {
            return true;
        }
        // The documented raw escape hatch, query(Consumer<Builder>), hands the unscoped builder out.
        if ("EloquentClauses.java".equals(fileName) && line.trim().equals("change.accept(query);")) {
            return true;
        }
        if (line.matches("^\\s*this\\.query\\s*=[^=].*")) {
            return true;
        }
        if (after.matches("^\\s*\\(.*")) {
            return true;
        }
        if (before.matches(".*\\bBuilder\\s+$")) {
            return true;
        }
        if (before.matches(".*\\bthis\\.$")) {
            return after.matches("^\\s*=[^=].*");
        }
        if (before.endsWith(".")) {
            return isScopesLayer && before.matches(".*\\bwork\\.$");
        }
        if (before.matches(".*\\bsuper\\([^)]*$") || ALLOWED_CALL.matcher(after).find()) {
            return true;
        }
        if (after.matches("^\\s*(==|!=).*") || before.matches(".*(==|!=)\\s*$")) {
            return true;
        }
        return isScopesLayer && "return query;".equals(line.trim());
    }

    private static Path sourceDir() {
        for (String root : new String[] {".", "eloquent", "diwan-database/eloquent"}) {
            Path candidate = Paths.get(root, PACKAGE_DIR);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return fail("eloquent main sources not found from " + Paths.get("").toAbsolutePath());
    }
}
