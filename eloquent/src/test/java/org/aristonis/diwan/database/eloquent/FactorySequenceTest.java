package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.Sequence;
import org.junit.jupiter.api.Test;

/** Sequences cycle over the models, restart on every call, and cross-join into one entry per tuple. */
class FactorySequenceTest {

    /** A post with two fillable columns. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected List<String> fillable() {
            return List.of("title", "body");
        }
    }

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<Post> posts = Post.QUERY.on(fake.db);
    private final Factory<Post> base = Factory.of(posts, () -> row("title", "base", "body", "kept"));

    private static List<Object> titles(List<Post> models) {
        List<Object> titles = new ArrayList<>();
        for (Post post : models) {
            titles.add(post.getAttribute("title"));
        }
        return titles;
    }

    private static List<Object> rawTitles(List<Map<String, Object>> raws) {
        List<Object> titles = new ArrayList<>();
        for (Map<String, Object> raw : raws) {
            titles.add(raw.get("title"));
        }
        return titles;
    }

    private static Sequence ab() {
        return Sequence.of(row("title", "a"), row("title", "b"));
    }

    @Test
    void aSequenceOfTwoCyclesOverFiveModels() {
        List<Post> made = base.sequence(ab()).makeMany(5);

        assertEquals(List.of("a", "b", "a", "b", "a"), titles(made));
        assertEquals("kept", made.get(0).getAttribute("body"));
    }

    @Test
    void aFunctionEntryReceivesTheModelIndex() {
        Sequence byIndex = Sequence.byIndex(index -> row("title", "n" + index));

        List<Post> made = base.sequence(byIndex).makeMany(3);

        assertEquals(List.of("n0", "n1", "n2"), titles(made));
    }

    @Test
    void forEachSequenceSetsTheCount() {
        List<Post> made = base.forEachSequence(row("title", "a"), row("title", "b"), row("title", "c")).makeMany();

        assertEquals(List.of("a", "b", "c"), titles(made));
    }

    @Test
    void anEmptySequenceThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, Sequence::of);
        assertThrows(IllegalArgumentException.class, Sequence::byIndex);
    }

    @Test
    void andAddsAnEntryToANewSequence() {
        Sequence one = Sequence.of(row("title", "a"));

        Sequence two = one.and(row("title", "b")).and(index -> row("title", "c" + index));

        assertEquals(1, one.size());
        assertEquals(3, two.size());
        assertEquals(List.of("a", "b", "c2"), titles(base.sequence(two).makeMany(3)));
    }

    @Test
    void aNullFunctionResultThrowsWithTheIndex() {
        Sequence broken = Sequence.byIndex(index -> row("title", "a"), index -> row("title", "b"), index -> null);
        Factory<Post> sequenced = base.sequence(broken);

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> sequenced.makeMany(3));

        assertTrue(thrown.getMessage().contains("[2]"), thrown.getMessage());
    }

    @Test
    void twoCreateLikeCallsStartFromTheFirstEntry() {
        Factory<Post> sequenced = base.sequence(ab());

        List<Object> first = titles(sequenced.makeMany(3));
        List<Object> second = titles(sequenced.makeMany(3));

        assertEquals(List.of("a", "b", "a"), first);
        assertEquals(first, second);
        assertEquals(rawTitles(sequenced.rawMany(3)), rawTitles(sequenced.rawMany(3)));
        assertEquals(first, rawTitles(sequenced.rawMany(3)));
    }

    @Test
    void aSingleMakeAfterManyStartsFromTheFirstEntry() {
        Factory<Post> sequenced = base.sequence(ab());

        sequenced.makeMany(3);

        assertEquals("a", sequenced.make().getAttribute("title"));
    }

    @Test
    void theFactoryObjectIsUnchangedBetweenCalls() {
        Factory<Post> sequenced = base.sequence(ab());

        List<Object> before = titles(sequenced.makeMany(4));
        sequenced.make();
        sequenced.raw();
        List<Object> after = titles(sequenced.makeMany(4));

        assertEquals(before, after);
        assertEquals("base", base.make().getAttribute("title"));
    }

    @Test
    void crossJoinOrdersFirstListSlowest() {
        Sequence cross = Sequence.crossJoin(
                List.of(row("title", "a1"), row("title", "a2")),
                List.of(row("body", "b1"), row("body", "b2")));

        List<Map<String, Object>> raws = base.sequence(cross).rawMany(4);

        assertEquals(4, cross.size());
        assertEquals(row("title", "a1", "body", "b1"), raws.get(0));
        assertEquals(row("title", "a1", "body", "b2"), raws.get(1));
        assertEquals(row("title", "a2", "body", "b1"), raws.get(2));
        assertEquals(row("title", "a2", "body", "b2"), raws.get(3));
    }

    @Test
    void crossJoinLaterListWinsOnADuplicateKey() {
        Sequence cross = Sequence.crossJoin(
                List.of(row("title", "from first", "body", "first body")),
                List.of(row("title", "from second")));

        Post post = base.sequence(cross).make();

        assertEquals("from second", post.getAttribute("title"));
        assertEquals("first body", post.getAttribute("body"));
    }

    @Test
    void crossJoinWithNoListOrAnEmptyListThrows() {
        List<Map<String, Object>> empty = Collections.emptyList();
        List<Map<String, Object>> one = List.of(row("title", "a"));

        assertThrows(IllegalArgumentException.class, Sequence::crossJoin);
        assertThrows(IllegalArgumentException.class, () -> Sequence.crossJoin(one, empty));
        assertThrows(IllegalArgumentException.class, () -> Sequence.crossJoin(empty));
    }

    @Test
    void crossJoinUsedWithForEachSequenceMakesFourModels() {
        Sequence cross = Sequence.crossJoin(
                List.of(row("title", "a1"), row("title", "a2")),
                List.of(row("body", "b1"), row("body", "b2")));

        List<Post> made = base.sequence(cross).makeMany(cross.size());

        assertEquals(4, made.size());
        assertEquals(List.of("a1", "a1", "a2", "a2"), titles(made));
    }

    @Test
    void crossJoinSizeOverflowThrows() {
        List<Map<String, Object>> large = Collections.nCopies(65_536, row("title", "a"));
        List<Map<String, Object>> larger = Collections.nCopies(65_536, row("body", "b"));

        assertThrows(IllegalArgumentException.class, () -> Sequence.crossJoin(large, larger));
    }
}
