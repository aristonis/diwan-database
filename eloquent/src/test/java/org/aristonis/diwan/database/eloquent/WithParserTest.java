package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.junit.jupiter.api.Test;

class WithParserTest {

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db);

    @Test
    void dotPathRegistersParentsWithNoOp() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("posts.comments.author");

        assertEquals(Arrays.asList("posts", "posts.comments", "posts.comments.author"),
                new ArrayList<>(parsed.keySet()));
        assertSame(WithParser.NO_OP, parsed.get("posts"));
        assertSame(WithParser.NO_OP, parsed.get("posts.comments"));
    }

    @Test
    void parentAlreadyConstrainedIsKept() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("posts:id,title", "posts.comments");

        EloquentBuilder<User> query = users.query();
        parsed.get("posts").accept(query);

        assertEquals("select \"id\", \"title\" from \"users\"", query.toSql());
        assertSame(WithParser.NO_OP, parsed.get("posts.comments"));
    }

    @Test
    void columnsAfterColonBecomeSelect() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("author: id , name");
        EloquentBuilder<User> query = users.query();

        parsed.get("author").accept(query);

        assertEquals(1, parsed.size());
        assertEquals("select \"id\", \"name\" from \"users\"", query.toSql());
    }

    @Test
    void nestedNameWithColumnsSplitsAtColon() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("posts.author:id");

        assertEquals(Arrays.asList("posts", "posts.author"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void emptyColumnListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WithParser.parse("author:"));
        assertThrows(IllegalArgumentException.class, () -> WithParser.parse("author: , "));
        assertThrows(IllegalArgumentException.class, () -> WithParser.parse("author:id,,name"));
    }

    @Test
    void nullPathIsRefused() {
        assertThrows(NullPointerException.class, () -> WithParser.parse("a", null));
    }

    @Test
    void sameNameTwiceRunsBothConstraintsInOrder() {
        List<String> calls = new ArrayList<>();
        Map<String, Consumer<EloquentBuilder<?>>> target = new LinkedHashMap<>();
        Map<String, Consumer<EloquentBuilder<?>>> first = new LinkedHashMap<>();
        first.put("posts", q -> calls.add("first"));
        Map<String, Consumer<EloquentBuilder<?>>> second = new LinkedHashMap<>();
        second.put("posts", q -> calls.add("second"));

        WithParser.merge(target, WithParser.parse(first));
        WithParser.merge(target, WithParser.parse(second));
        target.get("posts").accept(users.query());

        assertEquals(Arrays.asList("first", "second"), calls);
    }

    @Test
    void sameNameTwiceInOneCallCombinesSelects() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("posts:id", "posts:title");
        EloquentBuilder<User> query = users.query();

        parsed.get("posts").accept(query);

        assertEquals("select \"title\" from \"users\"", query.toSql());
    }

    @Test
    void callOrderIsKept() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("roles", "posts.comments", "avatar");

        assertEquals(Arrays.asList("roles", "posts", "posts.comments", "avatar"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void mapFormExpandsDotsAndKeepsColonInName() {
        Map<String, Consumer<EloquentBuilder<?>>> given = new LinkedHashMap<>();
        Consumer<EloquentBuilder<?>> constraint = q -> q.where("approved", true);
        given.put("posts.comments", constraint);

        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse(given);

        assertEquals(Arrays.asList("posts", "posts.comments"), new ArrayList<>(parsed.keySet()));
        assertSame(constraint, parsed.get("posts.comments"));
    }

    @Test
    void resultIsModifiable() {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse("posts");

        parsed.remove("posts");

        assertTrue(parsed.isEmpty());
    }

    @Test
    void accessParseWithUsesTheParser() {
        ModelQuery<?> touched = User.QUERY;

        Map<String, Consumer<EloquentBuilder<?>>> parsed = Access.eloquent().parseWith("posts.comments");

        assertEquals(Arrays.asList("posts", "posts.comments"), new ArrayList<>(parsed.keySet()));
        assertSame(User.QUERY, touched);
    }
}
