package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.CastColumns;
import org.junit.jupiter.api.Test;

/** Which keys mass assignment sets: underscore keys, mutator names, and a create with nothing to set. */
class ModelMassAssignmentRulesTest {

    /** A model with no fillable list and no guarded keys. */
    static final class Open extends Model {
        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** The accessors that split a full name and a full address over four columns. */
    private static Map<String, Attribute<?>> splittingMutators() {
        Map<String, Attribute<?>> accessors = new HashMap<>();
        accessors.put("full_name", Attribute.<String>set((model, value, attributes) -> {
            String[] parts = value.split(" ", 2);
            return CastColumns.of(row("first_name", parts[0], "last_name", parts[1]));
        }));
        accessors.put("full_address", Attribute.<String>set((model, value, attributes) -> {
            String[] parts = value.split(", ", 2);
            return CastColumns.of(row("address_line_one", parts[0], "address_line_two", parts[1]));
        }));
        return accessors;
    }

    /** A model that lists its two mutator names as fillable. */
    static final class FillableMutatorPerson extends Model {
        @Override
        protected List<String> fillable() {
            return Arrays.asList("full_name", "full_address");
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return splittingMutators();
        }
    }

    /** A model that guards only its key and has two mutators. */
    static final class GuardedMutatorPerson extends Model {
        @Override
        protected List<String> guarded() {
            return Collections.singletonList("id");
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return splittingMutators();
        }
    }

    /** A model on a table whose columns all have defaults. */
    static final class Blank extends Model {
        static final ModelQuery<Blank> QUERY = ModelQuery.of(Blank.class, Blank::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    @Test
    void keysStartingWithAnUnderscoreAreNotMassAssigned() {
        Open model = new Open();

        model.fill(row("_method", "PUT", "name", "Ann"));

        assertFalse(model.hasAttribute("_method"));
        assertEquals("Ann", model.getAttribute("name"));
    }

    @Test
    void aMutatorNameListedAsFillableIsFilledThroughItsMutator() {
        FillableMutatorPerson person = new FillableMutatorPerson();

        person.fill(row("id", 1, "full_name", "John Doe", "full_address", "123 Main Street, Anytown"));

        assertNull(person.getAttribute("id"));
        assertEquals("John", person.getAttribute("first_name"));
        assertEquals("Doe", person.getAttribute("last_name"));
        assertEquals("123 Main Street", person.getAttribute("address_line_one"));
        assertEquals("Anytown", person.getAttribute("address_line_two"));
    }

    @Test
    void guardingTheKeyStillLetsMutatorNamesThrough() {
        GuardedMutatorPerson person = new GuardedMutatorPerson();

        person.fill(row("id", 1, "full_name", "John Doe", "full_address", "123 Main Street, Anytown"));

        assertNull(person.getAttribute("id"));
        assertEquals("John", person.getAttribute("first_name"));
        assertEquals("Doe", person.getAttribute("last_name"));
        assertEquals("123 Main Street", person.getAttribute("address_line_one"));
        assertEquals("Anytown", person.getAttribute("address_line_two"));
    }

    @Test
    void createWithNoAttributesInsertsARowOfColumnDefaults() throws Exception {
        try (SqliteModelDb database = SqliteModelDb.open()) {
            database.sql("create table blanks (id integer primary key autoincrement, note text default 'none')");

            Blank created = Blank.QUERY.on(database.db).create(Collections.<String, Object>emptyMap());

            assertTrue(created.exists());
            assertEquals(1L, ((Number) created.getKey()).longValue());
            assertEquals(1L, ((Number) database.scalar("select count(*) from blanks")).longValue());
            assertEquals("none", database.scalar("select note from blanks where id = 1"));
        }
    }
}
