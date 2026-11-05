package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Tag;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Local scopes applied with {@code scope} and {@code scopes}: grouping of the clauses they add,
 * arguments, order and relations. Uses the tag table, seeded with php and java and one extra go row.
 */
class LocalScopeTest {

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures = ScopeFixtures.open();
        fixtures.seed();
        fixtures.insert("tags", "id", 3, "name", "go");
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private static List<Long> idsOf(Iterable<? extends Model> models) {
        List<Long> ids = new ArrayList<>();
        for (Model model : models) {
            ids.add(Long.parseLong(String.valueOf(model.getKey())));
        }
        return ids;
    }

    private static List<String> stringsOf(List<Object> values) {
        List<String> strings = new ArrayList<>();
        for (Object value : values) {
            strings.add(String.valueOf(value));
        }
        return strings;
    }

    @Test
    void scopeAfterOrWhereIsGrouped() {
        EloquentBuilder<Tag> query = fixtures.tags().query()
                .where("name", "php")
                .orWhere("name", "go")
                .scope(builder -> builder.where("id", ">", 1));

        assertEquals(Collections.singletonList(3L), idsOf(query.orderBy("id").get()));
    }

    @Test
    void chainedScopesGroupSeparately() {
        EloquentBuilder<Tag> query = fixtures.tags().query()
                .where("name", "php")
                .orWhere("name", "go")
                .scope(builder -> builder.where("id", ">", 1))
                .scope(builder -> builder.where("id", "<", 4).orWhere("name", "java"));

        assertEquals(Collections.singletonList(3L), idsOf(query.orderBy("id").get()));
    }

    @Test
    void scopeWithArgumentsBindsInOrder() {
        int lowest = 2;
        int highest = 3;
        EloquentBuilder<Tag> query = fixtures.tags().query()
                .scope(builder -> builder.where("id", ">=", lowest).where("id", "<=", highest));

        assertEquals(Arrays.asList("2", "3"), stringsOf(query.getBindings()));
        assertEquals(Arrays.asList(2L, 3L), idsOf(query.orderBy("id").get()));
    }

    @Test
    void nullScopeIsRefusedNamingTheArgument() {
        EloquentBuilder<Tag> query = fixtures.tags().query();

        NullPointerException refused = assertThrows(NullPointerException.class, () -> query.scope(null));

        assertEquals("scope", refused.getMessage());
    }

    @Test
    void nullListOfScopesIsRefusedNamingTheArgument() {
        EloquentBuilder<Tag> query = fixtures.tags().query();

        NullPointerException refused = assertThrows(NullPointerException.class, () -> query.scopes(null));

        assertEquals("locals", refused.getMessage());
    }

    @Test
    void scopeOnARelationAppliesToTheRelationQueryAndReturnsTheRelation() {
        User user = fixtures.users().find(1).orElseThrow(() -> new AssertionError("user 1 is seeded"));
        HasMany<Post> posts = user.posts();

        HasMany<Post> scoped = posts.scope(builder -> builder.where("title", "Post 2"));

        assertSame(posts, scoped);
        assertEquals(Collections.singletonList(2L), idsOf(scoped.get()));
    }

    @Test
    void filterPipelineKeepsTheOrInsideTheFirstGroup() {
        EloquentBuilder<Tag> query = fixtures.tags().query()
                .where("name", "php")
                .orWhere("name", "go")
                .scope(builder -> builder.where("id", ">", 1))
                .scope(builder -> builder.where("id", "<", 4));

        assertEquals(Collections.singletonList(3L), idsOf(query.orderBy("id").get()));
    }

    @Test
    void scopesAppliesTheListInOrder() {
        List<Consumer<EloquentBuilder<Tag>>> locals = new ArrayList<>();
        locals.add(builder -> builder.where("id", ">=", 2));
        locals.add(builder -> builder.where("id", "<=", 3));

        EloquentBuilder<Tag> query = fixtures.tags().query().scopes(locals);

        assertEquals(Arrays.asList("2", "3"), stringsOf(query.getBindings()));
    }

    @Test
    void scopeAddingNothingLeavesTheSqlUnchanged() {
        EloquentBuilder<Tag> query = fixtures.tags().query().where("name", "php");
        String before = query.toSql();

        query.scope(builder -> {
            // Adds no clause.
        });

        assertEquals(before, query.toSql());
    }

    @Test
    void emptyListOfScopesChangesNothing() {
        EloquentBuilder<Tag> query = fixtures.tags().query().where("name", "php");
        String before = query.toSql();

        query.scopes(new ArrayList<>());

        assertEquals(before, query.toSql());
    }
}
