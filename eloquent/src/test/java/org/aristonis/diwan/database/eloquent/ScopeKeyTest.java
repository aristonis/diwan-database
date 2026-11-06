package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.scope.Scope;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The keys global scopes are registered under: lambdas need a name, the soft-delete key is fixed. */
class ScopeKeyTest {

    private static final String SOFT_KEY = "softDeleting";
    private static final String NAME_HINT = "addGlobalScope(name, scope)";

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void lambdaWithoutNameIsRefusedAndRegistryUnchanged() {
        Scope lambda = (builder, model) -> builder.where("name", "php");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Tag.QUERY.addGlobalScope(lambda));

        assertTrue(refused.getMessage().contains(NAME_HINT), refused.getMessage());
        assertTrue(Tag.QUERY.getGlobalScopes().isEmpty());
    }

    @Test
    void anonymousScopeWithoutNameIsRefused() {
        Scope anonymous = new Scope() {
            @Override
            public void apply(EloquentBuilder<?> builder, Model model) {
                builder.where("name", "php");
            }
        };

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Tag.QUERY.addGlobalScope(anonymous));

        assertTrue(refused.getMessage().contains(NAME_HINT), refused.getMessage());
        assertTrue(Tag.QUERY.getGlobalScopes().isEmpty());
    }

    @Test
    void lambdaWithExplicitNameIsAccepted() {
        Tag.QUERY.addGlobalScope("named", (Scope) (builder, model) -> builder.where("name", "php"));

        assertTrue(Tag.QUERY.hasGlobalScope("named"));
    }

    @Test
    void softDeletingScopeKeyIsTheFixedConstant() {
        assertEquals(SOFT_KEY, SoftDeletingScope.NAME);
    }

    @Test
    void softDeletingScopeInstanceRegistersUnderTheConstantKey() {
        Tag.QUERY.addGlobalScope(new SoftDeletingScope());

        assertTrue(Tag.QUERY.hasGlobalScope(SoftDeletingScope.NAME));
        assertTrue(Tag.QUERY.hasGlobalScope(SoftDeletingScope.class));
        assertTrue(Tag.QUERY.getGlobalScope(SOFT_KEY) instanceof SoftDeletingScope);
    }

    @Test
    void withoutGlobalScopeByClassRemovesTheSoftDeleteFilter() {
        assertEquals(3, fixtures.posts().query().get().size(), "the trashed post is hidden by default");

        EloquentBuilder<Post> everything = fixtures.posts().query().withoutGlobalScope(SoftDeletingScope.class);

        assertEquals(Collections.singletonList(SOFT_KEY), everything.removedScopes());
        assertEquals(4, everything.get().size(), "the trashed post of the same tenant is included");
    }

    @Test
    void withoutGlobalScopeByInstanceRemovesTheSoftDeleteFilter() {
        EloquentBuilder<Post> everything = fixtures.posts().query().withoutGlobalScope(new SoftDeletingScope());

        assertEquals(Collections.singletonList(SOFT_KEY), everything.removedScopes());
        assertEquals(4, everything.get().size());
    }

    @Test
    void twoIdenticalLambdasWithDifferentNamesCoexist() {
        Consumer<EloquentBuilder<?>> same = builder -> builder.where("name", "php");

        Tag.QUERY.addGlobalScope("first", same).addGlobalScope("second", same);

        assertEquals(Arrays.asList("first", "second"), new ArrayList<>(Tag.QUERY.getGlobalScopes().keySet()));
        String sql = fixtures.tags().query().toSql();
        assertEquals(2, occurrences(sql, "\"name\" = ?"), sql);
    }
}
