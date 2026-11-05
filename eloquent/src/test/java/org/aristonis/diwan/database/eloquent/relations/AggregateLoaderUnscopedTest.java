package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code loadCount}, {@code loadSum} and {@code loadMissing} on models already in memory: the model
 * itself is never filtered out by its own scopes (it may be trashed or outside the tenant), while the
 * related rows it counts still obey the related scopes.
 */
class AggregateLoaderUnscopedTest {

    private static final Consumer<EloquentBuilder<?>> LIFT = q -> q.withTrashed();

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

    private static int number(Model model, String attribute) {
        Object value = model.getAttribute(attribute);
        assertNotNull(value, attribute + " must be loaded");
        return ((Number) value).intValue();
    }

    private static List<Long> ids(Iterable<? extends Model> models) {
        List<Long> ids = new ArrayList<>();
        for (Model model : models) {
            ids.add(Long.parseLong(String.valueOf(model.getKey())));
        }
        Collections.sort(ids);
        return ids;
    }

    private Post outsideTheTenant(long id) {
        return fixtures.posts().query().withoutGlobalScope(ScopeFixtures.TENANT).where("id", id).first().get();
    }

    private User trashedAnn() {
        User ann = fixtures.users().findOrFail(1);
        assertTrue(ann.delete());
        fixtures.log.clear();
        return ann;
    }

    @Test
    void loadCountOnATrashedModelStillCounts() {
        User ann = trashedAnn();

        ann.loadCount("posts");

        assertEquals(2, number(ann, "posts_count"));
    }

    @Test
    void loadSumOnATrashedModelStillSums() {
        fixtures.real.statement("update posts set views = 5 where id in (1, 2)");
        fixtures.real.statement("update posts set views = 100 where id = 4");
        User ann = trashedAnn();

        ann.loadSum("posts", "views");

        assertEquals(10, number(ann, "posts_sum_views"));
    }

    @Test
    void loadCountOnAModelOutsideTheTenantScopeStillCounts() {
        fixtures.insert("post_tag", "post_id", 5, "tag_id", 1);
        Post other = outsideTheTenant(5);

        other.loadCount("tags");

        assertEquals(1, number(other, "tags_count"));
    }

    @Test
    void loadCountedSubQueryStillFiltersTheRelatedRows() {
        User ann = trashedAnn();

        ann.loadCount("posts");
        int plain = number(ann, "posts_count");
        ann.loadCount(Collections.<String, Consumer<EloquentBuilder<?>>>singletonMap("posts", LIFT));

        assertEquals(2, plain);
        assertEquals(3, number(ann, "posts_count"));
    }

    @Test
    void loadCountedSubQueryHidesTrashedAndOtherTenantRows() {
        User ann = fixtures.users().findOrFail(1);

        ann.loadCount("posts");

        assertEquals(2, number(ann, "posts_count"));
    }

    @Test
    void loadMissingOnATrashedModelLoadsTheRelation() {
        User ann = trashedAnn();

        ann.loadMissing("posts");

        assertTrue(ann.relationLoaded("posts"));
        assertEquals(Arrays.asList(1L, 2L), ids(ann.relatedMany("posts")));
    }

    @Test
    void loadMissingOnAModelOutsideTheTenantScopeLoadsTheRelation() {
        Post other = outsideTheTenant(5);

        other.loadMissing("user");

        Model owner = other.related("user");
        assertNotNull(owner);
        assertEquals("3", String.valueOf(owner.getKey()));
    }

    @Test
    void collectionLoadCountKeepsTrashedMembers() {
        ModelCollection<User> users = fixtures.users().query().get();
        assertTrue(users.get(0).delete());
        fixtures.log.clear();

        users.loadCount("posts");

        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (User user : users) {
            counts.put(Long.parseLong(String.valueOf(user.getKey())), number(user, "posts_count"));
        }
        assertEquals(Integer.valueOf(2), counts.get(1L));
        assertEquals(Integer.valueOf(1), counts.get(2L));
        assertEquals(Integer.valueOf(0), counts.get(3L));
    }
}
