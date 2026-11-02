package org.aristonis.diwan.database.eloquent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Small models the unit tests share. */
final class TestModels {

    private TestModels() {
    }

    /** A user with two fillable columns and default timestamps. */
    static class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected List<String> fillable() {
            return Arrays.asList("name", "email");
        }
    }

    /** A model with every default: guarded {@code *}, table {@code blog_posts}. */
    static final class BlogPost extends Model {
        static final ModelQuery<BlogPost> QUERY = ModelQuery.of(BlogPost.class, BlogPost::new);
    }

    /** A model that guards only {@code id} and {@code secret}. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected List<String> guarded() {
            return Arrays.asList("id", "Secret");
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A UUID-keyed model. */
    static class Doc extends Model {
        static final ModelQuery<Doc> QUERY = ModelQuery.of(Doc.class, Doc::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A ULID-keyed model storing timestamps as instants, with no updated_at column. */
    static final class Event extends Model {
        static final ModelQuery<Event> QUERY = ModelQuery.of(Event.class, Event::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.ULID;
        }

        @Override
        protected TimestampFormat timestampFormat() {
            return TimestampFormat.INSTANT;
        }

        @Override
        protected String updatedAtColumn() {
            return null;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model that names its table, key and connection. */
    static final class Person extends Model {
        static final ModelQuery<Person> QUERY = ModelQuery.of(Person.class, Person::new);

        @Override
        protected String table() {
            return "people";
        }

        @Override
        protected String primaryKey() {
            return "person_id";
        }

        @Override
        protected String connectionName() {
            return "crm";
        }

        @Override
        protected int perPage() {
            return 5;
        }
    }
}
