package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.aristonis.diwan.database.eloquent.TestModels.BlogPost;
import org.aristonis.diwan.database.eloquent.TestModels.Doc;
import org.aristonis.diwan.database.eloquent.TestModels.Event;
import org.aristonis.diwan.database.eloquent.TestModels.Person;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.Test;

class ModelConfigTest {

    @Test
    void defaultsFollowLaravel() {
        User user = new User();

        assertEquals("users", user.getTable());
        assertEquals("id", user.getKeyName());
        assertEquals("int", user.getKeyType());
        assertTrue(user.getIncrementing());
        assertNull(user.getConnectionName());
        assertTrue(user.usesTimestamps());
        assertEquals("created_at", user.getCreatedAtColumn());
        assertEquals("updated_at", user.getUpdatedAtColumn());
        assertEquals(Collections.singletonList("*"), new BlogPost().getGuarded());
        assertEquals(15, user.getPerPage());
        assertEquals("yyyy-MM-dd HH:mm:ss", user.getDateFormat());
        assertEquals(TimestampFormat.TEXT, user.getTimestampFormat());
        assertEquals(UniqueIdKind.NONE, user.getUniqueIdKind());
    }

    @Test
    void tableIsTheSnakePluralOfTheClassName() {
        assertEquals("blog_posts", new BlogPost().getTable());
        assertEquals("blog_posts.id", new BlogPost().getQualifiedKeyName());
        assertEquals("blog_post_id", new BlogPost().getForeignKey());
        assertEquals("blog_posts.title", new BlogPost().qualifyColumn("title"));
        assertEquals("blog_posts.title", new BlogPost().qualifyColumn("blog_posts.title"));
    }

    @Test
    void overridesAreUsed() {
        Person person = new Person();

        assertEquals("people", person.getTable());
        assertEquals("person_id", person.getKeyName());
        assertEquals("people.person_id", person.getQualifiedKeyName());
        assertEquals("person_person_id", person.getForeignKey());
        assertEquals("crm", person.getConnectionName());
        assertEquals(5, person.getPerPage());
    }

    @Test
    void uniqueIdModelsUseStringKeysThatDoNotIncrement() {
        assertEquals("string", new Doc().getKeyType());
        assertFalse(new Doc().getIncrementing());
        assertEquals(UniqueIdKind.ULID, new Event().getUniqueIdKind());
        assertEquals(TimestampFormat.INSTANT, new Event().getTimestampFormat());
        assertNull(new Event().getUpdatedAtColumn());
    }
}
