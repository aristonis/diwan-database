package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.junit.jupiter.api.Test;

/** A factory builds unsaved models from a definition, states, values and a count, and touches no database. */
class FactoryDefinitionTest {

    /** A post with two fillable columns; {@code secret} is guarded. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected List<String> fillable() {
            return List.of("title", "body");
        }
    }

    /** The subclass form: the definition comes from an overridden method. */
    private static final class PostFactory extends Factory<Post> {
        PostFactory(ModelQuery<Post> query) {
            super(query);
        }

        @Override
        protected Map<String, Object> definition() {
            return row("title", "from subclass", "body", "text");
        }
    }

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<Post> posts = Post.QUERY.on(fake.db);

    private Factory<Post> factory(Object... pairs) {
        return Factory.of(posts, () -> row(pairs));
    }

    @Test
    void factoryOfBuildsAModelFromTheDefinition() {
        Post post = factory("title", "Hello", "body", "World").make();

        assertEquals("Hello", post.getAttribute("title"));
        assertEquals("World", post.getAttribute("body"));
    }

    @Test
    void aSubclassOverridingDefinitionBuildsAModel() {
        Post post = new PostFactory(posts).make();

        assertEquals("from subclass", post.getAttribute("title"));
        assertEquals("text", post.getAttribute("body"));
    }

    @Test
    void fluentCallsDoNotChangeTheReceiver() {
        AtomicInteger hooked = new AtomicInteger();
        Factory<Post> base = factory("title", "a");

        Factory<Post> derived = base.state(row("title", "b")).count(3).afterMaking(m -> hooked.incrementAndGet())
                .set("body", "z");

        assertEquals("a", base.make().getAttribute("title"));
        assertNull(base.make().getAttribute("body"));
        assertEquals(1, base.makeMany().size());
        assertEquals(0, hooked.get());
        assertEquals(3, derived.makeMany().size());
        assertEquals(3, hooked.get());
    }

    @Test
    void oneFactoryMakesTwiceWithIndependentResults() {
        Factory<Post> base = factory("title", "a");

        Post first = base.make();
        Post second = base.make();
        first.setAttribute("title", "changed");

        assertNotSame(first, second);
        assertEquals("a", second.getAttribute("title"));
    }

    @Test
    void buildingAFactoryRunsNoQuery() {
        factory("title", "a").state(row("body", "b")).count(2).set("title", "c").afterMaking(m -> { });

        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void laterStateWinsOverEarlierState() {
        Post post = factory("title", "base").state(row("title", "first")).state(row("title", "second")).make();

        assertEquals("second", post.getAttribute("title"));
    }

    @Test
    void aUnaryOperatorStateReceivesTheAttributesSoFar() {
        Post post = factory("title", "base").state(row("body", "earlier")).state(attributes -> {
            Map<String, Object> next = new HashMap<>();
            next.put("body", attributes.get("title") + "+" + attributes.get("body"));
            return next;
        }).make();

        assertEquals("base+earlier", post.getAttribute("body"));
        assertEquals("base", post.getAttribute("title"));
    }

    @Test
    void setOverridesADefinitionKey() {
        Post post = factory("title", "base", "body", "kept").set("title", "set").make();

        assertEquals("set", post.getAttribute("title"));
        assertEquals("kept", post.getAttribute("body"));
    }

    @Test
    void aSupplierValueIsCalledOncePerModel() {
        AtomicInteger counter = new AtomicInteger();
        Supplier<Object> next = counter::incrementAndGet;

        List<Post> made = factory("title", next).makeMany(3);

        assertEquals(1, made.get(0).getAttribute("title"));
        assertEquals(2, made.get(1).getAttribute("title"));
        assertEquals(3, made.get(2).getAttribute("title"));
    }

    @Test
    void aFunctionValueReceivesTheAttributesSoFar() {
        Function<Map<String, Object>, Object> shout = attributes -> attributes.get("title") + "!";

        Post post = factory("title", "hey", "body", shout).make();

        assertEquals("hey!", post.getAttribute("body"));
    }

    @Test
    void aPlainValueIsUsedAsIs() {
        List<String> list = new ArrayList<>(List.of("x"));

        Post post = factory("title", 42, "body", list).make();

        assertEquals(42, post.getAttribute("title"));
        assertEquals(list, post.getAttribute("body"));
    }

    @Test
    void aFixedValueDefinitionNeedsNoLibrary() {
        Factory<Post> fixed = Factory.of(posts, () -> row("title", "fixed", "body", 7L));

        assertEquals(7L, fixed.make().getAttribute("body"));
        assertEquals("fixed", fixed.make().getAttribute("title"));
    }

    @Test
    void aClassCastExceptionFromAFunctionIsRethrownAsIllegalArgument() {
        Function<Object, Object> wrong = attributes -> (String) attributes;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> factory("title", "secret-value", "body", wrong).make());

        assertTrue(thrown.getMessage().contains("body"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Post.class.getName()), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("secret-value"), thrown.getMessage());
        assertInstanceOf(ClassCastException.class, thrown.getCause());
    }

    @Test
    void aClassCastExceptionFromASupplierIsRethrownTheSame() {
        Supplier<Object> wrong = () -> {
            Object number = 5;
            return (String) number;
        };

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> factory("title", "secret-value", "body", wrong).make());

        assertTrue(thrown.getMessage().contains("body"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Post.class.getName()), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("secret-value"), thrown.getMessage());
        assertInstanceOf(ClassCastException.class, thrown.getCause());
    }

    @Test
    void otherExceptionsFromAValueArePropagatedUnchanged() {
        Supplier<Object> failing = () -> {
            throw new IllegalStateException("boom");
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> factory("title", failing).make());

        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void makeReturnsAnUnsavedModelAndFillsAGuardedAttribute() {
        Post post = factory("title", "a", "secret", "guarded value").make();

        assertTrue(fake.calls.isEmpty());
        assertFalse(post.exists());
        assertEquals("guarded value", post.getAttribute("secret"));
    }

    @Test
    void makeManyReturnsDistinctModels() {
        List<Post> made = factory("title", "a").makeMany(3);

        assertEquals(3, made.size());
        assertNotSame(made.get(0), made.get(1));
        assertNotSame(made.get(1), made.get(2));
        assertNotSame(made.get(0), made.get(2));
    }

    @Test
    void countThenMakeManyReturnsDistinctModels() {
        List<Post> made = factory("title", "a").count(3).makeMany();

        assertEquals(3, made.size());
        assertNotSame(made.get(0), made.get(1));
        assertNotSame(made.get(1), made.get(2));
    }

    @Test
    void countBelowOneMakesNoModel() {
        AtomicInteger hooked = new AtomicInteger();
        AtomicInteger supplied = new AtomicInteger();
        Supplier<Object> counted = supplied::incrementAndGet;
        Factory<Post> base = factory("title", counted).afterMaking(m -> hooked.incrementAndGet());

        assertTrue(base.count(0).makeMany().isEmpty());
        assertTrue(base.count(-1).makeMany().isEmpty());
        assertTrue(base.makeMany(0).isEmpty());
        assertEquals(0, hooked.get());
        assertEquals(0, supplied.get());
    }

    @Test
    void makeManyWithNoCountBuildsOne() {
        List<Post> made = factory("title", "a").makeMany();

        assertEquals(1, made.size());
    }
}
