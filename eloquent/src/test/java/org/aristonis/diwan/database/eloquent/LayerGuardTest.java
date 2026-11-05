package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.aristonis.diwan.database.eloquent.TestModels.BlogPost;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.Test;

/** Guards the layer chain: a model without casts keeps its plain attribute behaviour, and the files stay small. */
class LayerGuardTest {

    private static final int MAX_LINES = 800;

    private static final String PACKAGE_DIR = "src/main/java/org/aristonis/diwan/database/eloquent";

    /** A post whose key reads as text, so a cast key would differ from the stored one. */
    static final class TextKeyed extends Model {
        static final ModelQuery<TextKeyed> QUERY = ModelQuery.of(TextKeyed.class, TextKeyed::new);

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("id", Casts.string());
        }
    }

    @Test
    void modelWithoutCastsKeepsRawValuesAndLooseDirtyCheck() {
        BlogPost post = new BlogPost();
        Map<String, Object> tags = new java.util.HashMap<>(Map.of("a", 1));
        post.setRawAttributes(row("views", 1, "tags", tags), true);

        post.setAttribute("views", "1");

        assertFalse(post.isDirty("views"));
        assertEquals("1", post.getAttribute("views"));
        assertSame(tags, post.getAttribute("tags"));
        assertEquals(1, post.getOriginal("views"));
        assertTrue(post.getCasts().isEmpty());
        assertEquals(row("views", "1", "tags", Map.of("a", 1)), post.toMap());
    }

    @Test
    void saveQueryUsesTheStoredKey() {
        FakeDb fake = new FakeDb();
        TextKeyed model = TextKeyed.QUERY.on(fake.db)
                .hydrate(Collections.singletonList(row("id", 1L, "name", "a"))).get(0);
        model.setAttribute("name", "b");

        assertTrue(model.save());

        List<Object> bindings = fake.last().bindings();
        assertEquals(1L, bindings.get(bindings.size() - 1));
        assertEquals("1", model.getKey());
    }

    @Test
    void modelReadsRawAtTheThreeSites() throws IOException {
        String model = new String(Files.readAllBytes(sourceDir().resolve("Model.java")), StandardCharsets.UTF_8);

        assertTrue(model.contains("hasOriginal(key) ? getRawOriginal(key) : getKey()"));
        assertTrue(model.contains("return hasRawOriginal(key);"));
        assertTrue(model.contains("putRawAttribute(column, ModelNumbers.add(getRawAttribute(column), amount, up));"));
    }

    @Test
    void relationAndEventLayersSitBetweenSerializationAndGuards() {
        assertSame(ModelSerialization.class, ModelRelations.class.getSuperclass());
        assertSame(ModelRelations.class, ModelManyRelations.class.getSuperclass());
        assertSame(ModelManyRelations.class, ModelThroughRelations.class.getSuperclass());
        assertSame(ModelThroughRelations.class, ModelMorphRelations.class.getSuperclass());
        assertSame(ModelMorphRelations.class, ModelRelationActions.class.getSuperclass());
        assertSame(ModelRelationActions.class, ModelGuards.class.getSuperclass());
        assertSame(ModelGuards.class, ModelEventHooks.class.getSuperclass());
        assertSame(ModelEventHooks.class, ModelSoftDeletes.class.getSuperclass());
        assertSame(ModelSoftDeletes.class, Model.class.getSuperclass());
    }

    @Test
    void scopeLayerSitsBetweenWheresAndClauses() {
        assertSame(EloquentWheres.class, EloquentScopes.class.getSuperclass());
        assertSame(EloquentScopes.class, EloquentClauses.class.getSuperclass());
        assertSame(EloquentClauses.class, EloquentCreators.class.getSuperclass());
        assertSame(EloquentCreators.class, EloquentRelationQueries.class.getSuperclass());
    }

    @Test
    void aggregateLayerSitsBetweenRelationQueriesAndBuilder() {
        assertSame(EloquentRelationQueries.class, EloquentAggregates.class.getSuperclass());
        assertSame(EloquentAggregates.class, EloquentBuilder.class.getSuperclass());
    }

    @Test
    void everyMainFileStaysWithinTheLimit() throws IOException {
        List<String> tooLong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceDir())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                int lines = Files.readAllLines(file, StandardCharsets.UTF_8).size();
                if (lines > MAX_LINES) {
                    tooLong.add(file.getFileName() + " " + lines);
                }
            }
        }

        assertEquals(Collections.emptyList(), tooLong);
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
