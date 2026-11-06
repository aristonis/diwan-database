package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.ConnectionResolver;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InversePivotTest {

    /** A parent with a has-many side, used by the unchanged has-many check. */
    static final class Shelf extends Model {
        static final ModelQuery<Shelf> QUERY = ModelQuery.of(Shelf.class, Shelf::new);

        HasMany<Book> books() {
            return hasMany(Book.class, "shelf_id");
        }
    }

    /** A child that declares {@code shelf}. */
    static final class Book extends Model {
        static final ModelQuery<Book> QUERY = ModelQuery.of(Book.class, Book::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Book.class).add("shelf", m -> m.belongsTo(Shelf.class, "shelf_id"));
        }
    }

    /** A pivot that declares no relations, so its relation map is empty. */
    private static final class PlainJoin extends Pivot {
        static final ModelQuery<PlainJoin> QUERY = ModelQuery.of(PlainJoin.class, PlainJoin::new);
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
    }

    @AfterEach
    void close() throws SQLException {
        ConnectionResolver.resetDefault();
        db.close();
    }

    private static BtmModels.Membership pivot() {
        return Access.eloquent().newRegistered(BtmModels.Membership.class, null);
    }

    @Test
    void checkAcceptsDeclaredName() {
        assertEquals("role", InverseRelations.checkPivot(pivot(), "role"));
    }

    @Test
    void checkRejectsUnknownNameWithKnownNames() {
        RelationNotFoundException e = assertThrows(RelationNotFoundException.class,
                () -> InverseRelations.checkPivot(pivot(), "owner"));

        assertSame(BtmModels.Membership.class, e.getModelClass());
        assertEquals("owner", e.getRelation());
        assertEquals(Arrays.asList("user", "role"), e.getKnownRelations());
    }

    @Test
    void checkNullNameThrowsNpe() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> InverseRelations.checkPivot(pivot(), null));

        assertEquals("name", e.getMessage());
    }

    @Test
    void guessFromKeyColumn() {
        assertEquals("user", InverseRelations.guessPivot(pivot(), "user_id", BtmModels.User.QUERY.newModel()));
        assertEquals("role", InverseRelations.guessPivot(pivot(), "role_id", BtmModels.Role.QUERY.newModel()));
    }

    @Test
    void guessFromClassNameWhenKeyDoesNotMatch() {
        assertEquals("user", InverseRelations.guessPivot(pivot(), "owner_id", BtmModels.User.QUERY.newModel()));
    }

    @Test
    void guessReturnsNullWhenNoCandidateDeclared() {
        assertNull(InverseRelations.guessPivot(pivot(), "group_id", BtmModels.Permission.QUERY.newModel()));
    }

    @Test
    void guessDropsEmptyAndDuplicateCandidates() {
        // The key "id" against key name "id" leaves an empty candidate, which is dropped.
        assertEquals("role", InverseRelations.guessPivot(pivot(), "id", BtmModels.Role.QUERY.newModel()));
        // "role_id" and the class name both give "role"; the duplicate is dropped.
        assertEquals("role", InverseRelations.guessPivot(pivot(), "role_id", BtmModels.Role.QUERY.newModel()));
        assertNull(InverseRelations.guessPivot(pivot(), "id", BtmModels.Permission.QUERY.newModel()));
    }

    @Test
    void pivotWithoutRelationsGivesNullAndThrows() {
        PlainJoin sample = Access.eloquent().newRegistered(PlainJoin.class, null);

        assertNull(InverseRelations.guessPivot(sample, "user_id", BtmModels.User.QUERY.newModel()));
        RelationNotFoundException e = assertThrows(RelationNotFoundException.class,
                () -> InverseRelations.checkPivot(sample, "user"));
        assertEquals(Collections.emptyList(), e.getKnownRelations());
    }

    @Test
    void existingHasManyChaperoneBehaviourUnchanged() {
        HasMany<Book> books = Shelf.QUERY.on(db.db).newModel().books();

        assertEquals("shelf", InverseRelations.check(books, "shelf"));
    }
}
