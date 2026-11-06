package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChaperoneTest {

    private RelationDb db;

    /** A user whose notes and card name it {@code user}, so the inverse is guessed from {@code user_id}. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Member.class)
                    .add("notes", Member::notes)
                    .add("linkedNotes", Member::linkedNotes)
                    .add("card", Member::card)
                    .add("linkedCard", Member::linkedCard);
        }

        HasMany<Note> notes() {
            return hasMany(Note.class, "user_id");
        }

        HasMany<Note> linkedNotes() {
            return hasMany(Note.class, "user_id").chaperone();
        }

        HasOne<Card> card() {
            return hasOne(Card.class, "user_id");
        }

        HasOne<Card> linkedCard() {
            return hasOne(Card.class, "user_id").withDefault().chaperone();
        }
    }

    /** A post that records whether its {@code user} was linked when its save began. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        private boolean isLinkedWhenSaving;

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> fillable() {
            return Arrays.asList("title", "user_id");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Note.class).add("user", Note::user);
        }

        @Override
        protected boolean saving() {
            isLinkedWhenSaving = relationLoaded("user");
            return true;
        }

        BelongsTo<Member> user() {
            return belongsTo(Member.class, "user_id");
        }
    }

    /** A profile that names its owner {@code user}. */
    static final class Card extends Model {
        static final ModelQuery<Card> QUERY = ModelQuery.of(Card.class, Card::new);

        @Override
        protected String table() {
            return "profiles";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Card.class).add("user", Card::user);
        }

        BelongsTo<Member> user() {
            return belongsTo(Member.class, "user_id");
        }
    }

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        if (Arrays.asList(Member.QUERY, Note.QUERY, Card.QUERY).contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("users", Map.of("id", 3, "name", "Cid"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
        db.seed("profiles", Map.of("id", 1, "user_id", 1, "bio", "hi"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Member member(int id) {
        return Member.QUERY.on(db.db).findOrFail(id);
    }

    private static void assertAllLinkedTo(Model parent, List<? extends Model> children) {
        assertFalse(children.isEmpty());
        for (Model child : children) {
            assertSame(parent, child.getRelation("user"));
        }
    }

    @Test
    void lazyHasManyLinksParent() {
        Member ann = member(1);

        ModelCollection<Note> notes = ann.notes().chaperone().get();

        assertEquals(2, notes.size());
        assertAllLinkedTo(ann, notes);
    }

    @Test
    void lazyGetResultsLinksParent() {
        Member ann = member(1);

        assertAllLinkedTo(ann, ann.notes().chaperone().getResults());
    }

    @Test
    void explicitName() {
        RelationModels.register();
        User ann = User.QUERY.on(db.db).findOrFail(1);

        ModelCollection<Post> posts = ann.posts().chaperone("author").get();

        assertEquals(2, posts.size());
        for (Post post : posts) {
            assertSame(ann, post.getRelation("author"));
        }
    }

    @Test
    void badNameRejected() {
        Member ann = member(1);
        db.clearLog();

        RelationNotFoundException failure =
                assertThrows(RelationNotFoundException.class, () -> ann.notes().chaperone("nope"));

        assertEquals("nope", failure.getRelation());
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void inverseIsAlias() {
        Member ann = member(1);

        assertAllLinkedTo(ann, ann.notes().inverse().get());
        assertAllLinkedTo(ann, ann.notes().inverse("user").get());
        assertThrows(RelationNotFoundException.class, () -> ann.notes().inverse("nope"));
    }

    @Test
    void make() {
        Member ann = member(1);

        Note note = ann.notes().chaperone().make(Map.of("title", "new"));

        assertSame(ann, note.getRelation("user"));
        assertFalse(note.exists());
    }

    @Test
    void writePaths() {
        Member ann = member(1);
        Note saved = Note.QUERY.on(db.db).newModel();
        saved.setAttribute("title", "s");
        Note first = Note.QUERY.on(db.db).newModel();
        first.setAttribute("title", "m1");
        Note second = Note.QUERY.on(db.db).newModel();
        second.setAttribute("title", "m2");

        Note created = ann.notes().chaperone().create(Map.of("title", "c"));
        List<Note> createdMany = ann.notes().chaperone().createMany(
                Arrays.asList(Map.of("title", "c1"), Map.of("title", "c2")));
        ann.notes().chaperone().save(saved);
        List<Note> savedMany = ann.notes().chaperone().saveMany(Arrays.asList(first, second));

        assertTrue(created.exists());
        assertSame(ann, created.getRelation("user"));
        assertAllLinkedTo(ann, createdMany);
        assertSame(ann, saved.getRelation("user"));
        assertAllLinkedTo(ann, savedMany);
    }

    @Test
    void saveLinksBeforeTheSaveRuns() {
        Member ann = member(1);
        Note note = Note.QUERY.on(db.db).newModel();
        note.setAttribute("title", "s");

        ann.notes().chaperone().save(note);

        assertTrue(note.isLinkedWhenSaving);
        assertTrue(note.exists());
    }

    @Test
    void writeWithoutChaperoneLinksNothing() {
        Member ann = member(1);

        Note made = ann.notes().make(Map.of("title", "new"));
        Note created = ann.notes().create(Map.of("title", "c"));

        assertFalse(made.relationLoaded("user"));
        assertFalse(created.relationLoaded("user"));
        assertFalse(created.isLinkedWhenSaving);
    }

    @Test
    void hasOneLazy() {
        Member ann = member(1);
        Member bob = member(2);

        Card found = ann.card().chaperone().getResults();
        Card viaFirst = ann.card().chaperone().first().orElseThrow();

        assertSame(ann, found.getRelation("user"));
        assertSame(ann, viaFirst.getRelation("user"));
        assertNull(bob.card().chaperone().getResults());
        assertTrue(bob.card().chaperone().first().isEmpty());
    }

    @Test
    void defaultModelLinked() {
        Member bob = member(2);

        Card lazyDefault = bob.linkedCard().getResults();
        List<Member> members = Member.QUERY.on(db.db).query().with("linkedCard").orderBy("id").get();

        assertFalse(lazyDefault.exists());
        assertSame(bob, lazyDefault.getRelation("user"));
        Member eagerBob = members.get(1);
        Card eagerDefault = (Card) eagerBob.getRelation("linkedCard");
        assertFalse(eagerDefault.exists());
        assertSame(eagerBob, eagerDefault.getRelation("user"));
    }

    @Test
    void eagerMatchLinksRealParent() {
        List<Member> members = Member.QUERY.on(db.db).query().with("linkedNotes", "linkedCard").orderBy("id").get();

        Member ann = members.get(0);
        Member bob = members.get(1);
        @SuppressWarnings("unchecked")
        List<Note> annNotes = (List<Note>) ann.getRelation("linkedNotes");
        @SuppressWarnings("unchecked")
        List<Note> bobNotes = (List<Note>) bob.getRelation("linkedNotes");
        assertEquals(2, annNotes.size());
        assertEquals(1, bobNotes.size());
        assertAllLinkedTo(ann, annNotes);
        assertAllLinkedTo(bob, bobNotes);
        Card card = (Card) ann.getRelation("linkedCard");
        assertTrue(card.exists());
        assertSame(ann, card.getRelation("user"));
        // The blank parent the eager relation is built on is never linked: every note holds the
        // one eager-loaded parent instance.
        Object linked = annNotes.get(0).getRelation("user");
        assertSame(ann, linked);
        assertSame(linked, annNotes.get(1).getRelation("user"));
    }

    @Test
    void unmatchedParentUntouched() {
        List<Member> members = Member.QUERY.on(db.db).query().with("linkedNotes").orderBy("id").get();

        Member cid = members.get(2);

        assertTrue(((List<?>) cid.getRelation("linkedNotes")).isEmpty());
        assertFalse(cid.relationLoaded("user"));
    }

    @Test
    void withoutChaperone() {
        Member ann = member(1);

        ModelCollection<Note> notes = ann.notes().chaperone().withoutChaperone().get();
        ModelCollection<Note> viaInverse = ann.notes().inverse().withoutInverse().get();
        ModelCollection<Note> fromMethod = ann.linkedNotes().withoutChaperone().get();

        assertFalse(notes.get(0).relationLoaded("user"));
        assertFalse(viaInverse.get(0).relationLoaded("user"));
        assertFalse(fromMethod.get(0).relationLoaded("user"));
    }

    @Test
    void reenable() {
        Member ann = member(1);

        ModelCollection<Note> notes = ann.notes().chaperone().withoutChaperone().chaperone().get();

        assertAllLinkedTo(ann, notes);
    }

    @Test
    void noExtraQueries() {
        Member ann = member(1);
        db.clearLog();
        ann.notes().get();
        ann.card().getResults();
        Member.QUERY.on(db.db).query().with("notes", "card").get();
        List<String> plain = db.sql();
        db.clearLog();

        ann.notes().chaperone().get();
        ann.card().chaperone().getResults();
        Member.QUERY.on(db.db).query().with("linkedNotes", "linkedCard").get();
        List<String> chaperoned = db.sql();

        assertEquals(plain.size(), chaperoned.size());
        assertEquals(plain, chaperoned);
    }

    @Test
    void relationLoadedTrue() {
        Member ann = member(1);

        ModelCollection<Note> notes = ann.notes().chaperone().get();

        assertTrue(notes.get(0).relationLoaded("user"));
        db.clearLog();
        assertSame(ann, notes.get(0).related("user"));
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void configuredInMethod() {
        Member ann = member(1);

        ModelCollection<Note> lazyNotes = ann.relatedMany("linkedNotes");
        @SuppressWarnings("unchecked")
        HasMany<Note> byName = (HasMany<Note>) ann.relation("linkedNotes");
        Note made = byName.make(Map.of("title", "new"));
        Member eagerAnn = Member.QUERY.on(db.db).query().with("linkedNotes").orderBy("id").get().get(0);

        assertAllLinkedTo(ann, lazyNotes);
        assertSame(ann, made.getRelation("user"));
        @SuppressWarnings("unchecked")
        List<Note> eagerNotes = (List<Note>) eagerAnn.getRelation("linkedNotes");
        assertAllLinkedTo(eagerAnn, eagerNotes);
    }

    @Test
    void rebuiltPerUse() {
        Member ann = member(1);

        HasMany<Note> earlier = ann.linkedNotes();
        earlier.withoutChaperone();
        HasMany<Note> fresh = ann.linkedNotes();
        ModelCollection<Note> next = ann.linkedNotes().get();

        assertNull(earlier.inverseName());
        assertEquals("user", fresh.inverseName());
        assertAllLinkedTo(ann, next);
    }

    @Test
    void lazyWithoutChaperoneLinksNothing() {
        Member ann = member(1);

        ModelCollection<Note> notes = ann.notes().get();
        Card card = ann.card().getResults();

        assertFalse(notes.get(0).relationLoaded("user"));
        assertFalse(card.relationLoaded("user"));
    }
}
