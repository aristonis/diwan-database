package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Comment;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Country;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Memo;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Ticket;
import org.aristonis.diwan.database.eloquent.SoftDeleteSeamTest.Probe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Soft delete, force delete and restore on one model: exact SQL, attribute state, failures. */
class SoftDeleteModelTest {

    private static final String NOW_TEXT = "2026-11-05 10:15:30";
    private static final String LATER_TEXT = "2026-11-06 08:00:00";
    // save() writes the dirty columns in the order the row loaded them, as Laravel does.
    private static final String RESTORE_SQL =
            "update \"posts\" set \"updated_at\" = ?, \"deleted_at\" = ? where \"id\" = ?";
    private static final String SOFT_SQL = "update \"posts\" set \"deleted_at\" = ?, \"updated_at\" = ? where \"id\" = ?";

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private Post livePost() {
        return fixtures.posts().query().find(1).get();
    }

    private Post trashedPost() {
        return fixtures.posts().query().withTrashed().find(4).get();
    }

    private Object cell(String table, String column, int id) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select \"" + column + "\" as v from " + table + " where id = " + id, Collections.emptyList());
        assertEquals(1, rows.size(), "the row must exist");
        return rows.get(0).get("v");
    }

    private int rowCount(String table, int id) {
        return fixtures.real.select("select id from " + table + " where id = " + id, Collections.emptyList())
                .size();
    }

    @Test
    void unsavedModelIsNotDeleted() {
        Post post = fixtures.posts().newModel();
        fixtures.log.clear();

        assertFalse(post.delete());

        assertTrue(fixtures.log.all().isEmpty());
        assertFalse(post.exists());
    }

    @Test
    void vetoedDeleteWritesNothing() {
        Probe probe = Probe.QUERY.on(fixtures.db).query().find(1).get();
        probe.vetoDeleting = true;
        fixtures.log.clear();

        assertFalse(probe.delete());

        assertTrue(fixtures.log.writes().isEmpty());
        assertNull(cell("posts", "deleted_at", 1));
        assertNull(probe.getAttribute("deleted_at"));
    }

    @Test
    void softDeleteWritesOneUpdate() {
        Post post = livePost();
        fixtures.log.clear();

        assertTrue(post.delete());

        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals(SOFT_SQL, writes.get(0).sql());
        assertEquals(Arrays.<Object>asList(NOW_TEXT, NOW_TEXT, post.getKey()), writes.get(0).bindings());
        assertTrue(post.exists());
        assertTrue(post.trashed());
        assertFalse(post.isDirty("deleted_at"));
        assertFalse(post.isDirty("updated_at"));
        assertEquals(NOW_TEXT, cell("posts", "deleted_at", 1));
        assertEquals(NOW_TEXT, cell("posts", "updated_at", 1));
    }

    @Test
    void noTimestampsOnlyDeletedAt() {
        Memo memo = fixtures.memos().query().find(1).get();
        fixtures.log.clear();

        assertTrue(memo.delete());

        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals("update \"memos\" set \"deleted_at\" = ? where \"id\" = ?", writes.get(0).sql());
        assertEquals(Arrays.<Object>asList(NOW_TEXT, memo.getKey()), writes.get(0).bindings());
    }

    @Test
    void withoutTimestampsWritesOnlyDeletedAt() {
        Post post = livePost();
        fixtures.log.clear();

        boolean deleted = Model.withoutTimestamps(post::delete);

        assertTrue(deleted);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals("update \"posts\" set \"deleted_at\" = ? where \"id\" = ?", writes.get(0).sql());
        assertNull(cell("posts", "updated_at", 1));
    }

    @Test
    void deleteTwiceMovesTheMoment() {
        Post post = livePost();
        post.delete();
        post.setClock(ModelClock.fixed(Instant.parse("2026-11-06T08:00:00Z")));

        assertTrue(post.delete());

        assertTrue(post.exists());
        assertEquals(LATER_TEXT, cell("posts", "deleted_at", 1));
        assertFalse(post.isDirty());
    }

    @Test
    void deleteOfAMissingRowReturnsTrue() {
        Post post = livePost();
        fixtures.real.statement("delete from posts where id = 1");
        fixtures.log.clear();

        assertTrue(post.delete());

        assertEquals(1, fixtures.log.writes().size());
        assertTrue(post.exists());
        assertEquals(0, rowCount("posts", 1));
    }

    @Test
    void customDeletedAtColumn() {
        Ticket open = fixtures.tickets().query().find(1).get();
        fixtures.log.clear();

        assertTrue(open.delete());

        assertEquals("removed_on", open.getDeletedAtColumn());
        assertEquals("tickets.removed_on", open.getQualifiedDeletedAtColumn());
        assertEquals("update \"tickets\" set \"removed_on\" = ?, \"updated_at\" = ? where \"id\" = ?",
                fixtures.log.writes().get(0).sql());
        assertEquals(NOW_TEXT, cell("tickets", "removed_on", 1));
        Ticket closed = fixtures.tickets().query().onlyTrashed().find(2).get();
        assertTrue(closed.trashed());
        assertTrue(closed.restore());
        assertNull(cell("tickets", "removed_on", 2));
    }

    @Test
    void nullColumnIsRefused() {
        NullColumn model = NullColumn.QUERY.on(fixtures.db).newModel();

        IllegalStateException failure = assertThrows(IllegalStateException.class, model::getDeletedAtColumn);

        assertTrue(failure.getMessage().contains(NullColumn.class.getName()));
        assertThrows(IllegalStateException.class, model::trashed);
        assertThrows(IllegalStateException.class, model::getQualifiedDeletedAtColumn);
    }

    @Test
    void trashedReadsTheCastColumn() {
        Post trashed = trashedPost();
        Post live = livePost();

        assertTrue(trashed.trashed());
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), trashed.getInstant("deleted_at"));
        assertFalse(live.trashed());
    }

    @Test
    void failedSoftDeleteKeepsTheModelClean() {
        Post post = livePost();
        fixtures.real.statement("create trigger block_update before update on posts"
                + " begin select raise(abort, 'locked'); end");

        assertThrows(QueryException.class, post::delete);

        assertNull(post.getAttribute("deleted_at"));
        assertNull(post.getAttribute("updated_at"));
        assertFalse(post.trashed());
        assertFalse(post.isDirty());
        assertTrue(post.exists());
        assertNull(cell("posts", "deleted_at", 1));
    }

    @Test
    void forceDeleteClearsTheFlagInFinally() {
        Post post = livePost();
        fixtures.log.clear();

        assertTrue(post.forceDelete());

        assertFalse(post.exists());
        assertFalse(post.isForceDeleting());
        assertEquals(0, rowCount("posts", 1));
        assertEquals("delete from \"posts\" where \"id\" = ?", fixtures.log.writes().get(0).sql());
        assertEquals(Collections.singletonList(post.getKey()), fixtures.log.writes().get(0).bindings());

        Post failing = fixtures.posts().query().find(2).get();
        fixtures.real.statement("create trigger block_delete before delete on posts"
                + " begin select raise(abort, 'locked'); end");
        assertThrows(QueryException.class, failing::forceDelete);
        assertFalse(failing.isForceDeleting());
    }

    @Test
    void forceDeleteNeedsSoftDeletes() {
        Country country = fixtures.countries().query().find(1).get();

        IllegalStateException failure = assertThrows(IllegalStateException.class, country::forceDelete);

        assertTrue(failure.getMessage().contains(Country.class.getName()));
        assertThrows(IllegalStateException.class, country::restore);
        assertEquals(1, rowCount("countries", 1));
    }

    @Test
    void plainDeleteOfANonSoftModelRemovesTheRow() {
        Country country = fixtures.countries().query().find(2).get();

        assertTrue(country.delete());

        assertFalse(country.exists());
        assertEquals(0, rowCount("countries", 2));
    }

    @Test
    void restoreClearsDeletedAt() {
        Post post = trashedPost();
        fixtures.log.clear();

        assertTrue(post.restore());

        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals(RESTORE_SQL, writes.get(0).sql());
        assertEquals(Arrays.<Object>asList(NOW_TEXT, null, post.getKey()), writes.get(0).bindings());
        assertNull(post.getAttribute("deleted_at"));
        assertFalse(post.trashed());
        assertNull(cell("posts", "deleted_at", 4));
    }

    @Test
    void restoreOfALiveModelWritesNothing() {
        Post post = livePost();
        fixtures.log.clear();

        assertTrue(post.restore());

        assertTrue(fixtures.log.writes().isEmpty());
    }

    @Test
    void restoreAfterForceDeleteIsAQuirk() {
        Post post = trashedPost();
        post.forceDelete();
        fixtures.log.clear();

        assertTrue(post.restore());

        assertEquals(1, fixtures.log.writes().size());
        assertTrue(post.exists());
        assertEquals(0, rowCount("posts", 4));
    }

    @Test
    void refreshSeesTrashedRows() {
        Post post = livePost();
        post.delete();

        post.refresh();
        Optional<Post> fresh = post.fresh();

        assertTrue(post.trashed());
        assertTrue(fresh.isPresent());
        assertTrue(fresh.get().trashed());
        assertTrue(trashedPost().fresh().get().trashed());
    }

    @Test
    void savingATrashedModelWorks() {
        Post post = trashedPost();
        post.setAttribute("title", "Renamed");
        fixtures.log.clear();

        assertTrue(post.save());

        Statement update = fixtures.log.writes().get(0);
        assertTrue(update.sql().endsWith("where \"id\" = ?"));
        assertFalse(update.sql().contains("is null"));
        assertEquals("Renamed", cell("posts", "title", 4));
        assertTrue(post.trashed());
    }

    @Test
    void softDeleteTouchesOwners() {
        Comment comment = fixtures.comments().query().find(1).get();
        Object ownerStampBefore = cell("posts", "updated_at", 1);
        fixtures.log.clear();

        assertTrue(comment.delete());

        int touch = -1;
        int soft = -1;
        List<Statement> writes = fixtures.log.writes();
        for (int i = 0; i < writes.size(); i++) {
            String sql = writes.get(i).sql();
            if (touch < 0 && sql.startsWith("update \"posts\"")) {
                touch = i;
            }
            if (soft < 0 && sql.startsWith("update \"comments\"")) {
                soft = i;
            }
        }
        assertTrue(touch >= 0 && soft >= 0, "both updates must run: " + writes);
        assertTrue(touch < soft, "the owner is touched before the soft update");
        // The owner is stamped by its own entry's clock, not the comment's fixed one.
        assertNotEquals(ownerStampBefore, cell("posts", "updated_at", 1));
        assertNotNull(cell("comments", "deleted_at", 1));
    }

    /** A model that soft-deletes but names no deleted-at column. */
    static final class NullColumn extends Model {
        static final ModelQuery<NullColumn> QUERY = ModelQuery.of(NullColumn.class, NullColumn::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected String deletedAtColumn() {
            return null;
        }
    }
}
