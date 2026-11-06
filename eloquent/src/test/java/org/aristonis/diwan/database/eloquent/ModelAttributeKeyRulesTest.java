package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What counts as an attribute name, and what a model keeps outside its attributes. */
class ModelAttributeKeyRulesTest {

    /** A model with open mass assignment and one Java field that is not a column. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);
        String scratch;

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
    void aNumericAttributeKeyIsAccepted() {
        Note note = new Note();

        note.setAttribute("0", "bar");

        assertEquals("bar", note.getAttribute("0"));
        assertTrue(note.hasAttribute("0"));
        assertEquals("bar", note.getAttributes().get("0"));
    }

    @Test
    void attributeNamesAreCaseSensitive() {
        Note note = new Note();

        note.setAttribute("Name", "Ann");

        assertEquals("Ann", note.getAttribute("Name"));
        assertNull(note.getAttribute("name"));
        assertFalse(note.hasAttribute("NAME"));
        assertEquals(Collections.singleton("Name"), note.getAttributes().keySet());
    }

    @Test
    void aJavaFieldOfTheModelIsNotSavedAsAColumn() throws Exception {
        try (SqliteModelDb database = SqliteModelDb.open()) {
            database.sql("create table notes (id integer primary key autoincrement, title text)");
            ModelQuery<Note> notes = Note.QUERY.on(database.db);
            Note note = notes.make(row("title", "First"));
            note.scratch = "never stored";

            assertTrue(note.save());
            Note reloaded = notes.findOrFail(1);

            assertEquals("First", reloaded.getAttribute("title"));
            assertNull(reloaded.scratch);
            assertFalse(reloaded.hasAttribute("scratch"));
            assertEquals(1L, ((Number) database.scalar("select count(*) from notes")).longValue());
        }
    }
}
