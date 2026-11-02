package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.Test;

class RelationMapTest {

    static class Animal extends Model {
    }

    static final class Dog extends Animal {
    }

    private static <M extends Model> RelationFactory<M> none() {
        return model -> null;
    }

    @Test
    void namesKeepInsertionOrder() {
        RelationMap<Dog> map = RelationMap.of(Dog.class)
                .add("owner", none()).add("toys", none()).add("bowl", none());

        assertEquals(Arrays.asList("owner", "toys", "bowl"), map.names());
        assertSame(Dog.class, map.modelClass());
    }

    @Test
    void addReturnsANewMap() {
        RelationMap<Dog> empty = RelationMap.of(Dog.class);

        RelationMap<Dog> one = empty.add("owner", none());

        assertNotSame(empty, one);
        assertTrue(empty.names().isEmpty());
        assertEquals(Collections.singletonList("owner"), one.names());
    }

    @Test
    void duplicateNameIsRefused() {
        RelationMap<Dog> map = RelationMap.of(Dog.class).add("owner", none());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> map.add("owner", none()));

        assertTrue(e.getMessage().contains("[owner]"));
        assertEquals(Collections.singletonList("owner"), map.names());
    }

    @Test
    void factoryForASupertypeIsAccepted() {
        RelationFactory<Animal> forAnimals = animal -> null;

        RelationMap<Dog> map = RelationMap.of(Dog.class).add("owner", forAnimals);

        assertEquals(Collections.singletonList("owner"), map.names());
    }

    @Test
    void namesAreUnmodifiable() {
        List<String> names = RelationMap.of(Dog.class).add("owner", none()).names();

        assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> names.remove(0));
    }

    @Test
    void emptyMapIsForModel() {
        RelationMap<Model> empty = RelationMap.empty();

        assertSame(Model.class, empty.modelClass());
        assertTrue(empty.names().isEmpty());
    }

    @Test
    void nullArgumentsAreRefused() {
        RelationMap<Dog> map = RelationMap.of(Dog.class);

        assertThrows(NullPointerException.class, () -> RelationMap.of(null));
        assertThrows(NullPointerException.class, () -> map.add(null, none()));
        assertThrows(NullPointerException.class, () -> map.add("owner", null));
    }

    @Test
    void notFoundExceptionCarriesItsParts() {
        RelationNotFoundException e = new RelationNotFoundException(Dog.class, "tail", Arrays.asList("owner", "toys"));

        assertSame(Dog.class, e.getModelClass());
        assertEquals("tail", e.getRelation());
        assertEquals(Arrays.asList("owner", "toys"), e.getKnownRelations());
        assertEquals("Call to undefined relationship [tail] on model [" + Dog.class.getName() + "]."
                + " Known: owner, toys", e.getMessage());
    }

    @Test
    void notFoundMessageCapsKnownNamesAtTwenty() {
        List<String> known = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            known.add("r" + i);
        }

        RelationNotFoundException e = new RelationNotFoundException(Dog.class, "x", known);

        assertTrue(e.getMessage().endsWith("r18, r19, and 5 more"), e.getMessage());
        assertTrue(!e.getMessage().contains("r20"));
        assertEquals(25, e.getKnownRelations().size());
        assertThrows(UnsupportedOperationException.class, () -> e.getKnownRelations().add("y"));
    }

    @Test
    void notFoundWithNoKnownNames() {
        RelationNotFoundException e = new RelationNotFoundException(Dog.class, "x", Collections.emptyList());

        assertTrue(e.getMessage().endsWith(" Known: none"), e.getMessage());
    }

    @Test
    void invalidRelationExceptionCarriesItsParts() {
        InvalidRelationException e = new InvalidRelationException(Dog.class, "owner", "the factory returned null");

        assertSame(Dog.class, e.getModelClass());
        assertEquals("owner", e.getRelation());
        assertEquals("the factory returned null", e.getReason());
        assertEquals("Invalid relation [owner] on model [" + Dog.class.getName() + "]: the factory returned null",
                e.getMessage());
        assertTrue(e instanceof IllegalStateException);
    }

    @Test
    void invalidRelationWithoutANameNamesTheMap() {
        InvalidRelationException e = new InvalidRelationException(Dog.class, null, "the map is for another class");

        assertEquals("Invalid relations() on model [" + Dog.class.getName() + "]: the map is for another class",
                e.getMessage());
    }

    @Test
    void invalidRelationRethrownKeepsTheMessage() {
        InvalidRelationException first = new InvalidRelationException(Dog.class, "owner", "bad");

        InvalidRelationException again = new InvalidRelationException(first);

        assertEquals(first.getMessage(), again.getMessage());
        assertSame(first, again.getCause());
        assertEquals("owner", again.getRelation());
        assertSame(Dog.class, again.getModelClass());
        assertEquals("bad", again.getReason());
    }

    @Test
    void lazyLoadingViolationCarriesItsParts() {
        LazyLoadingViolationException e = new LazyLoadingViolationException(Dog.class, "owner");

        assertSame(Dog.class, e.getModelClass());
        assertEquals("owner", e.getRelation());
        assertEquals("Attempted to lazy load [owner] on model [" + Dog.class.getName()
                + "] but lazy loading is disabled.", e.getMessage());
    }
}
