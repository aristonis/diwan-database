package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelCursor;
import org.aristonis.diwan.database.eloquent.ModelPage;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pivot chaperone of a belongsToMany relation: the names it guesses or checks, the links it
 * sets on every read path, the re-pointing of eager loads, and the query and serialization
 * behaviour around it.
 */
class PivotChaperoneTest {

    /** A user whose simple class name is {@code User}, which the chaperone reads when guessing a name. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

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
            return RelationMap.of(User.class).add("ownerRoles", User::ownerRoles);
        }

        BelongsToMany<BtmModels.Role> ownerRoles() {
            return belongsToMany(BtmModels.Role.class, OwnerPivot.class, "owner_id", "role_id").chaperone();
        }
    }

    /** A user with several relation methods, each over a different pivot configuration. */
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
                    .add("plainMemberships", Member::plainMemberships)
                    .add("chaperonedMemberships", Member::chaperonedMemberships)
                    .add("onlyUserRoles", Member::onlyUserRoles)
                    .add("noLinkRoles", Member::noLinkRoles);
        }

        BelongsToMany<BtmModels.Role> plainMemberships() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Membership.class, "user_id", "role_id");
        }

        BelongsToMany<BtmModels.Role> chaperonedMemberships() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Membership.class, "user_id", "role_id")
                    .chaperone();
        }

        BelongsToMany<BtmModels.Role> onlyUserRoles() {
            return belongsToMany(BtmModels.Role.class, OnlyUserPivot.class, "user_id", "role_id").chaperone();
        }

        BelongsToMany<BtmModels.Role> noLinkRoles() {
            return belongsToMany(BtmModels.Role.class, NoRelationPivot.class, "user_id", "role_id").chaperone();
        }
    }

    /** A pivot that names only its {@code user} relation. */
    public static final class OnlyUserPivot extends Pivot {
        static final ModelQuery<OnlyUserPivot> QUERY = ModelQuery.of(OnlyUserPivot.class, OnlyUserPivot::new);

        public OnlyUserPivot() {
        }

        @Override
        protected String table() {
            return "role_user";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(OnlyUserPivot.class).add("user", OnlyUserPivot::user);
        }

        public BelongsTo<BtmModels.User> user() {
            return belongsTo(BtmModels.User.class, "user_id");
        }
    }

    /** A pivot with no relation at all, so no side can be guessed. */
    public static final class NoRelationPivot extends Pivot {
        static final ModelQuery<NoRelationPivot> QUERY = ModelQuery.of(NoRelationPivot.class, NoRelationPivot::new);

        public NoRelationPivot() {
        }

        @Override
        protected String table() {
            return "role_user";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A pivot whose foreign key is {@code owner_id}, so the declaring side is guessed from the class name. */
    public static final class OwnerPivot extends Pivot {
        static final ModelQuery<OwnerPivot> QUERY = ModelQuery.of(OwnerPivot.class, OwnerPivot::new);

        public OwnerPivot() {
        }

        @Override
        protected String table() {
            return "role_owner";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(OwnerPivot.class)
                    .add("user", OwnerPivot::user)
                    .add("role", OwnerPivot::role);
        }

        public BelongsTo<User> user() {
            return belongsTo(User.class, "owner_id");
        }

        public BelongsTo<BtmModels.Role> role() {
            return belongsTo(BtmModels.Role.class, "role_id");
        }
    }

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    private Member member(int id) {
        return Member.QUERY.on(db.db).findOrFail(id);
    }

    private static BtmModels.Role loaded(Model parent, String relation, int index) {
        return (BtmModels.Role) ((List<?>) parent.getRelation(relation)).get(index);
    }

    private static void assertLinked(Model parent, BtmModels.Role role) {
        Pivot pivot = Pivot.of(role);
        assertNotNull(pivot);
        assertSame(parent, pivot.getRelation("user"));
        assertSame(role, pivot.getRelation("role"));
    }

    private static void assertUnlinked(BtmModels.Role role) {
        assertFalse(Pivot.of(role).relationLoaded("user"));
    }

    // ---- names: no-op, check, guess

    @Test
    void noopWithoutUsing() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> roles = ann.roles().chaperone().get();

        assertEquals(2, roles.size());
        for (BtmModels.Role role : roles) {
            assertUnlinked(role);
        }
    }

    @Test
    void guessBothSides() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> roles = ann.memberships().chaperone().get();

        assertEquals(2, roles.size());
        for (BtmModels.Role role : roles) {
            assertLinked(ann, role);
        }
    }

    @Test
    void explicitNames() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> roles = ann.memberships().chaperone("user", "role").get();

        for (BtmModels.Role role : roles) {
            assertLinked(ann, role);
        }
    }

    @Test
    void badNameRejected() {
        BtmModels.User ann = user(1);
        BelongsToMany<BtmModels.Role> relation = ann.memberships();
        db.clearLog();

        RelationNotFoundException failure =
                assertThrows(RelationNotFoundException.class, () -> relation.chaperone("nope"));

        assertSame(BtmModels.Membership.class, failure.getModelClass());
        assertEquals("nope", failure.getRelation());
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void oneSideOnly() {
        Member ann = member(1);

        ModelCollection<BtmModels.Role> roles = ann.onlyUserRoles().get();

        assertEquals(2, roles.size());
        for (BtmModels.Role role : roles) {
            Pivot pivot = Pivot.of(role);
            assertSame(ann, pivot.getRelation("user"));
            assertFalse(pivot.relationLoaded("role"));
        }
    }

    @Test
    void noneFoundQuiet() {
        Member ann = member(1);

        ModelCollection<BtmModels.Role> roles = ann.noLinkRoles().get();

        assertEquals(2, roles.size());
        for (BtmModels.Role role : roles) {
            assertNotNull(Pivot.of(role));
            assertUnlinked(role);
        }
    }

    @Test
    void classNameCandidate() {
        db.real.statement("create table role_owner (owner_id integer, role_id integer)");
        db.seed("role_owner", Map.of("owner_id", 1, "role_id", 1));
        db.seed("role_owner", Map.of("owner_id", 1, "role_id", 2));
        User owner = User.QUERY.on(db.db).findOrFail(1);

        ModelCollection<BtmModels.Role> roles = owner.ownerRoles().get();

        assertEquals(2, roles.size());
        for (BtmModels.Role role : roles) {
            assertLinked(owner, role);
        }
    }

    // ---- read paths

    @Test
    void lazyLinks() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> roles = ann.memberships().chaperone().get();

        assertSame(ann, Pivot.of(roles.get(0)).getRelation("user"));
        assertSame(roles.get(0), Pivot.of(roles.get(0)).getRelation("role"));
        assertSame(ann, Pivot.of(roles.get(1)).getRelation("user"));
        assertSame(roles.get(1), Pivot.of(roles.get(1)).getRelation("role"));
    }

    @Test
    void eagerLinksRealParent() {
        ModelCollection<Member> members = Member.QUERY.on(db.db).query()
                .with("chaperonedMemberships").orderBy("id").get();

        Member ann = members.get(0);
        Member bob = members.get(1);
        assertEquals(2, ((List<?>) ann.getRelation("chaperonedMemberships")).size());
        assertLinked(ann, loaded(ann, "chaperonedMemberships", 0));
        assertLinked(ann, loaded(ann, "chaperonedMemberships", 1));
        assertLinked(bob, loaded(bob, "chaperonedMemberships", 0));
    }

    @Test
    void sharedRoleLinksOwn() {
        ModelCollection<Member> members = Member.QUERY.on(db.db).query()
                .with("chaperonedMemberships").orderBy("id").get();

        Member ann = members.get(0);
        Member bob = members.get(1);
        BtmModels.Role annsRole = loaded(ann, "chaperonedMemberships", 0);
        BtmModels.Role bobsRole = loaded(bob, "chaperonedMemberships", 0);
        assertNotSame(annsRole, bobsRole);
        assertSame(ann, Pivot.of(annsRole).getRelation("user"));
        assertSame(bob, Pivot.of(bobsRole).getRelation("user"));
    }

    @Test
    void everyReadPath() {
        BtmModels.User ann = user(1);
        List<BtmModels.Role> chunked = new ArrayList<>();
        List<BtmModels.Role> visited = new ArrayList<>();
        List<BtmModels.Role> lazily = new ArrayList<>();
        List<BtmModels.Role> cursored = new ArrayList<>();

        BtmModels.Role first = ann.memberships().chaperone().first().orElseThrow();
        BtmModels.Role found = ann.memberships().chaperone().find(2).orElseThrow();
        ann.memberships().chaperone().chunk(1, models -> {
            chunked.addAll(models);
            return true;
        });
        ann.memberships().chaperone().each(role -> {
            visited.add(role);
            return true;
        });
        for (BtmModels.Role role : ann.memberships().chaperone().lazy()) {
            lazily.add(role);
        }
        try (ModelCursor<BtmModels.Role> rows = ann.memberships().chaperone().cursor()) {
            for (BtmModels.Role role : rows) {
                cursored.add(role);
            }
        }
        ModelPage<BtmModels.Role> page = ann.memberships().chaperone().paginate(10);

        assertLinked(ann, first);
        assertLinked(ann, found);
        assertEquals(2, chunked.size());
        assertEquals(2, visited.size());
        assertEquals(2, lazily.size());
        assertEquals(2, cursored.size());
        assertEquals(2, page.items().size());
        for (List<BtmModels.Role> path : List.of(chunked, visited, lazily, cursored)) {
            for (BtmModels.Role role : path) {
                assertLinked(ann, role);
            }
        }
        for (BtmModels.Role role : page.items()) {
            assertLinked(ann, role);
        }
    }

    @Test
    void withoutChaperone() {
        BtmModels.User ann = user(1);
        ModelCollection<BtmModels.Role> linked = ann.memberships().chaperone().get();

        ModelCollection<BtmModels.Role> unlinked = ann.memberships().chaperone().withoutChaperone().get();

        for (BtmModels.Role role : unlinked) {
            assertUnlinked(role);
        }
        assertLinked(ann, linked.get(0));
        assertLinked(ann, linked.get(1));
    }

    @Test
    void reenable() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> roles = ann.memberships().chaperone().withoutChaperone().chaperone().get();

        for (BtmModels.Role role : roles) {
            assertLinked(ann, role);
        }
    }

    @Test
    void configuredInMethod() {
        Member ann = member(1);

        ModelCollection<BtmModels.Role> lazy = ann.relatedMany("chaperonedMemberships");
        Member eagerAnn = Member.QUERY.on(db.db).query().with("chaperonedMemberships").orderBy("id").get().get(0);

        for (BtmModels.Role role : lazy) {
            assertLinked(ann, role);
        }
        assertLinked(eagerAnn, loaded(eagerAnn, "chaperonedMemberships", 0));
    }

    @Test
    void orderMatters() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> chaperoneFirst = ann.roles().chaperone()
                .using(BtmModels.Membership.class).get();
        ModelCollection<BtmModels.Role> usingFirst = ann.roles()
                .using(BtmModels.Membership.class).chaperone().get();

        for (BtmModels.Role role : chaperoneFirst) {
            assertTrue(Pivot.of(role) instanceof BtmModels.Membership);
            assertUnlinked(role);
        }
        for (BtmModels.Role role : usingFirst) {
            assertLinked(ann, role);
        }
    }

    @Test
    void relationLoadedTrue() {
        BtmModels.User ann = user(1);
        Pivot pivot = Pivot.of(ann.memberships().chaperone().get().get(0));

        assertTrue(pivot.relationLoaded("user"));
        db.clearLog();
        Model owner = pivot.related("user");
        assertSame(ann, owner);
        assertTrue(db.sql().isEmpty());
    }

    // ---- queries and serialization

    @Test
    void noExtraQueries() {
        BtmModels.User ann = user(1);
        db.clearLog();
        ann.memberships().get();
        List<String> plain = db.sql();
        db.clearLog();

        ann.memberships().chaperone().get();

        assertEquals(plain, db.sql());
        db.clearLog();
        Member.QUERY.on(db.db).query().with("plainMemberships").get();
        List<String> plainEager = db.sql();
        db.clearLog();

        Member.QUERY.on(db.db).query().with("chaperonedMemberships").get();

        assertEquals(plainEager, db.sql());
    }

    @Test
    void serializationEnds() {
        Member ann = Member.QUERY.on(db.db).query().with("chaperonedMemberships").orderBy("id").get().get(0);
        BtmModels.Role role = loaded(ann, "chaperonedMemberships", 0);

        Map<String, Object> map = role.toMap();
        String json = role.toJson();

        assertTrue(map.containsKey("pivot"));
        Map<?, ?> pivot = (Map<?, ?>) map.get("pivot");
        assertTrue(pivot.containsKey("user"));
        assertTrue(json.contains("\"pivot\""), json);
    }
}
