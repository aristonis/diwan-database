package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.Test;

class VisitPathTest {

    /** A model whose {@code equals} says every instance is the same, so only identity tells them apart. */
    static final class AlwaysEqual extends Model {
        @Override
        public boolean equals(Object other) {
            return other instanceof AlwaysEqual;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    void enterAddsTheModelToThePath() {
        VisitPath path = new VisitPath();
        Model model = new AlwaysEqual();

        boolean entered = path.enter(model);

        assertTrue(entered);
        assertTrue(path.contains(model));
    }

    @Test
    void enterReturnsFalseWhenAlreadyOnThePath() {
        VisitPath path = new VisitPath();
        Model model = new AlwaysEqual();
        path.enter(model);

        boolean second = path.enter(model);

        assertFalse(second);
        assertTrue(path.contains(model));
    }

    @Test
    void pathComparesByIdentityNotEquals() {
        VisitPath path = new VisitPath();
        Model first = new AlwaysEqual();
        Model second = new AlwaysEqual();
        path.enter(first);

        assertTrue(first.equals(second));
        assertFalse(path.contains(second));
        assertTrue(path.enter(second));
    }

    @Test
    void leaveRemovesOnlyThatModel() {
        VisitPath path = new VisitPath();
        Model first = new AlwaysEqual();
        Model second = new AlwaysEqual();
        path.enter(first);
        path.enter(second);

        path.leave(first);

        assertFalse(path.contains(first));
        assertTrue(path.contains(second));
    }

    @Test
    void leaveOfAModelNotOnThePathDoesNothing() {
        VisitPath path = new VisitPath();
        Model model = new AlwaysEqual();

        path.leave(model);

        assertFalse(path.contains(model));
    }

    @Test
    void releasedOnException() {
        VisitPath path = new VisitPath();
        Model model = new AlwaysEqual();

        assertThrows(IllegalStateException.class, () -> walkAndFail(path, model));

        assertFalse(path.contains(model));
        assertTrue(path.enter(model));
    }

    private static void walkAndFail(VisitPath path, Model model) {
        path.enter(model);
        try {
            throw new IllegalStateException("boom");
        } finally {
            path.leave(model);
        }
    }

    @Test
    void doneSetIsSeparateFromThePath() {
        VisitPath path = new VisitPath();
        Model model = new AlwaysEqual();

        path.markDone(model);

        assertTrue(path.isDone(model));
        assertFalse(path.contains(model));
        assertTrue(path.enter(model));
    }

    @Test
    void leaveKeepsTheModelDone() {
        VisitPath path = new VisitPath();
        Model model = new AlwaysEqual();
        path.enter(model);
        path.markDone(model);

        path.leave(model);

        assertFalse(path.contains(model));
        assertTrue(path.isDone(model));
    }

    @Test
    void doneSetComparesByIdentity() {
        VisitPath path = new VisitPath();
        Model first = new AlwaysEqual();
        Model second = new AlwaysEqual();

        path.markDone(first);

        assertFalse(path.isDone(second));
    }

    @Test
    void independentPathsShareNothing() {
        VisitPath first = new VisitPath();
        VisitPath second = new VisitPath();
        Model model = new AlwaysEqual();
        first.enter(model);
        first.markDone(model);

        assertFalse(second.contains(model));
        assertFalse(second.isDone(model));
    }

    @Test
    void nullModelIsRefused() {
        VisitPath path = new VisitPath();

        assertThrows(NullPointerException.class, () -> path.enter(null));
        assertThrows(NullPointerException.class, () -> path.leave(null));
        assertThrows(NullPointerException.class, () -> path.contains(null));
        assertThrows(NullPointerException.class, () -> path.markDone(null));
        assertThrows(NullPointerException.class, () -> path.isDone(null));
    }

    @Test
    void modelsOfNullIsEmpty() {
        assertTrue(VisitPath.modelsOf(null).isEmpty());
    }

    @Test
    void modelsOfAModelIsThatModel() {
        Model model = new AlwaysEqual();

        List<Model> models = VisitPath.modelsOf(model);

        assertEquals(1, models.size());
        assertSame(model, models.get(0));
    }

    @Test
    void modelsOfACollectionKeepsOrderAndSkipsNulls() {
        Model first = new AlwaysEqual();
        Model second = new AlwaysEqual();
        List<Model> items = new ArrayList<>(Arrays.asList(first, null, second));

        List<Model> models = VisitPath.modelsOf(items);

        assertEquals(2, models.size());
        assertSame(first, models.get(0));
        assertSame(second, models.get(1));
    }

    @Test
    void modelsOfReturnsASnapshot() {
        Model first = new AlwaysEqual();
        List<Model> items = new ArrayList<>(Arrays.asList(first));

        List<Model> models = VisitPath.modelsOf(items);
        items.add(new AlwaysEqual());

        assertEquals(1, models.size());
        assertThrows(UnsupportedOperationException.class, () -> models.add(first));
    }

    @Test
    void modelsOfAnotherValueThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VisitPath.modelsOf("text"));

        assertEquals("A relation value must be a model, a collection of models or null, not "
                + "[java.lang.String]", e.getMessage());
    }

    @Test
    void modelsOfACollectionWithANonModelThrows() {
        List<Object> items = new ArrayList<>(Arrays.asList(new AlwaysEqual(), 7));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VisitPath.modelsOf(items));

        assertEquals("A relation collection must hold models only, not [java.lang.Integer]",
                e.getMessage());
    }
}
