package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.exception.MassAssignmentException;
import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.aristonis.diwan.database.eloquent.TestModels.BlogPost;
import org.aristonis.diwan.database.eloquent.TestModels.Doc;
import org.aristonis.diwan.database.eloquent.TestModels.Note;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelGuardsTest {

    @AfterEach
    void restore() {
        Model.preventSilentlyDiscardingAttributes(false);
    }

    @Test
    void fillSetsFillableKeysAndSilentlyDropsOthers() {
        User user = new User();

        user.fill(row("name", "Ann", "is_admin", true));

        assertEquals(row("name", "Ann"), user.getAttributes());
        assertTrue(user.isFillable("name"));
        assertFalse(user.isFillable("is_admin"));
    }

    @Test
    void aTotallyGuardedModelRefusesWithLaravelsMessage() {
        BlogPost post = new BlogPost();

        assertTrue(post.totallyGuarded());
        MassAssignmentException e = assertThrows(MassAssignmentException.class,
                () -> post.fill(row("title", "x")));
        assertEquals("Add [title] to fillable property to allow mass assignment on ["
                + BlogPost.class.getName() + "].", e.getMessage());
    }

    @Test
    void preventingSilentDiscardsNamesEveryDroppedKey() {
        Model.preventSilentlyDiscardingAttributes(true);
        User user = new User();

        MassAssignmentException e = assertThrows(MassAssignmentException.class,
                () -> user.fill(row("name", "Ann", "role", "x", "level", 2)));
        assertEquals("Add fillable property [role, level] to allow mass assignment on ["
                + User.class.getName() + "].", e.getMessage());
    }

    @Test
    void anInstanceCanPreventSilentDiscardsOnItsOwn() {
        User strict = new User() {
            @Override
            protected boolean preventsSilentlyDiscardingAttributes() {
                return true;
            }
        };

        assertThrows(MassAssignmentException.class, () -> strict.fill(row("role", "x")));
    }

    @Test
    void guardedListsAreCaseInsensitiveAndOtherKeysAreFillable() {
        Note note = new Note();

        note.fill(row("body", "hi", "secret", "s", "id", 9, "_token", "t", "a.b", 1));

        assertEquals(row("body", "hi"), note.getAttributes());
        assertTrue(note.isGuarded("SECRET"));
        assertFalse(note.isGuarded("body"));
        assertFalse(note.totallyGuarded());
    }

    @Test
    void anEmptyGuardedListAllowsEverything() {
        Doc doc = new Doc();

        doc.fill(row("anything", 1));

        assertFalse(doc.isGuarded("anything"));
        assertEquals(row("anything", 1), doc.getAttributes());
    }

    @Test
    void forceFillBypassesGuards() {
        BlogPost post = new BlogPost();

        post.forceFill(row("title", "x", "id", 3));

        assertEquals(row("title", "x", "id", 3), post.getAttributes());
        assertFalse(Model.isUnguarded());
    }

    @Test
    void unguardedRestoresTheGuardEvenWhenTheCallbackThrows() {
        assertThrows(IllegalStateException.class, () -> Model.unguarded(() -> {
            assertTrue(Model.isUnguarded());
            throw new IllegalStateException("boom");
        }));
        assertFalse(Model.isUnguarded());
        Map<String, Object> filled = Model.unguarded(() -> new BlogPost().fill(row("a", 1)).getAttributes());
        assertEquals(row("a", 1), filled);
    }

    @Test
    void fillReturnsTheModel() {
        User user = new User();

        assertTrue(user.fill(row("name", "Ann")) == user);
        assertTrue(user.forceFill(row("x", 1)) == user);
    }
}
