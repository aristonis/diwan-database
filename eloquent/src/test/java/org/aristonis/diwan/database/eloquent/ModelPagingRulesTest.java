package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.page.CursorModelPage;
import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.query.QueryCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cursor pages, and chunk, chunkById and lazy reads that carry their own limit and offset. */
class ModelPagingRulesTest {

    /** An account with open mass assignment. */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** An account whose key is cast to text when read. */
    static final class TextKeyAccount extends Model {
        static final ModelQuery<TextKeyAccount> QUERY = ModelQuery.of(TextKeyAccount.class, TextKeyAccount::new);

        @Override
        protected String table() {
            return "accounts";
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Collections.<String, Cast<?>>singletonMap("id", Casts.string());
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;
    private ModelQuery<Account> accounts;

    @BeforeEach
    void open() throws Exception {
        database = SqliteModelDb.open();
        database.sql("create table accounts (id integer primary key autoincrement, name text, email text unique,"
                + " votes integer, created_at text, updated_at text)");
        database.sql("create table orders (id integer primary key autoincrement, account_id integer, total integer)");
        accounts = Account.QUERY.on(database.db);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    private void seedVotes(int... votes) {
        for (int i = 0; i < votes.length; i++) {
            accounts.create(row("name", "user" + (i + 1), "email", "u" + (i + 1) + "@x", "votes", votes[i]));
        }
    }

    private void seed(int count) {
        int[] votes = new int[count];
        Arrays.fill(votes, 1);
        seedVotes(votes);
    }

    /** The model keys of a page as longs, since SQLite reads small integers as Integer. */
    private static List<Object> keysOf(CursorModelPage<?> page) {
        List<Object> keys = new ArrayList<>();
        for (Object key : page.items().modelKeys()) {
            keys.add(((Number) key).longValue());
        }
        return keys;
    }

    private static List<String> names(Iterable<Account> models) {
        List<String> names = new ArrayList<>();
        for (Account account : models) {
            names.add(account.getString("name"));
        }
        return names;
    }

    private static List<List<String>> chunkNames(EloquentBuilder<Account> query, int size) {
        List<List<String>> pages = new ArrayList<>();
        query.chunk(size, models -> {
            pages.add(names(models));
            return true;
        });
        return pages;
    }

    private static List<List<String>> chunkByIdNames(EloquentBuilder<Account> query, int size) {
        List<List<String>> pages = new ArrayList<>();
        query.chunkById(size, models -> {
            pages.add(names(models));
            return true;
        });
        return pages;
    }

    // ---- cursor pages ----

    @Test
    void cursorPagesWalkForwardAndBackByTheirCursors() {
        seed(5);
        EloquentBuilder<Account> ordered = accounts.query().orderBy("id");

        CursorModelPage<Account> first = ordered.cursorPaginate(2);
        CursorModelPage<Account> second = accounts.query().orderBy("id").cursorPaginate(2, first.nextCursor().get());
        CursorModelPage<Account> third = accounts.query().orderBy("id").cursorPaginate(2, second.nextCursor().get());
        CursorModelPage<Account> back = accounts.query().orderBy("id")
                .cursorPaginate(2, third.previousCursor().get());

        assertEquals(Arrays.<Object>asList(1L, 2L), keysOf(first));
        assertFalse(first.previousCursor().isPresent());
        assertEquals(Arrays.<Object>asList(3L, 4L), keysOf(second));
        assertTrue(second.previousCursor().isPresent());
        assertEquals(Arrays.<Object>asList(5L), keysOf(third));
        assertFalse(third.nextCursor().isPresent());
        assertEquals(Arrays.<Object>asList(3L, 4L), keysOf(back));
    }

    @Test
    void cursorPageOfAnEmptyTableHasNoItemsAndNoCursors() {
        CursorModelPage<Account> page = accounts.query().orderBy("id").cursorPaginate(2);

        assertTrue(page.items().isEmpty());
        assertFalse(page.nextCursor().isPresent());
        assertFalse(page.previousCursor().isPresent());
    }

    @Test
    void cursorPageWithoutASizeUsesTheModelsPageSize() {
        seed(20);
        int perPage = new Account().getPerPage();

        CursorModelPage<Account> page = accounts.query().cursorPaginate();

        assertEquals(perPage, page.perPage());
        assertEquals(Math.min(20, perPage), page.items().size());
    }

    @Test
    void cursorPageWithoutAnOrderUsesTheKey() {
        seed(3);

        CursorModelPage<Account> page = accounts.query().cursorPaginate(2);

        assertEquals(Arrays.<Object>asList(1L, 2L), keysOf(page));
        assertTrue(page.nextCursor().isPresent());
    }

    @Test
    void cursorPageKeepsOnlyTheSelectedColumns() {
        seed(3);

        CursorModelPage<Account> page = accounts.query().select("id", "name").orderBy("id").cursorPaginate(2);

        Set<String> columns = new HashSet<>(Arrays.asList("id", "name"));
        assertEquals(columns, page.items().get(0).getAttributes().keySet());
        assertEquals(2, page.items().size());
        assertTrue(page.nextCursor().isPresent());
    }

    @Test
    void cursorPageHonoursWhereClauses() {
        seedVotes(1, 5, 5, 5, 5);

        CursorModelPage<Account> first = accounts.where("votes", ">", 1).orderBy("id").cursorPaginate(3);
        CursorModelPage<Account> second = accounts.where("votes", ">", 1).orderBy("id")
                .cursorPaginate(3, first.nextCursor().get());

        assertEquals(Arrays.<Object>asList(2L, 3L, 4L), keysOf(first));
        assertEquals(Arrays.<Object>asList(5L), keysOf(second));
        assertFalse(second.nextCursor().isPresent());
    }

    @Test
    void cursorPageCanRunNewestFirst() {
        seed(5);

        CursorModelPage<Account> first = accounts.query().orderBy("id", "desc").cursorPaginate(2);
        CursorModelPage<Account> second = accounts.query().orderBy("id", "desc")
                .cursorPaginate(2, first.nextCursor().get());

        assertEquals(Arrays.<Object>asList(5L, 4L), keysOf(first));
        assertEquals(Arrays.<Object>asList(3L, 2L), keysOf(second));
    }

    @Test
    void cursorPageOrderedByTwoColumnsBreaksTiesOnTheSecond() {
        seedVotes(2, 1, 2, 1, 3);

        CursorModelPage<Account> first = accounts.query().orderBy("votes").orderBy("id").cursorPaginate(2);
        CursorModelPage<Account> second = accounts.query().orderBy("votes").orderBy("id")
                .cursorPaginate(2, first.nextCursor().get());
        CursorModelPage<Account> third = accounts.query().orderBy("votes").orderBy("id")
                .cursorPaginate(2, second.nextCursor().get());

        assertEquals(Arrays.<Object>asList(2L, 4L), keysOf(first));
        assertEquals(Arrays.<Object>asList(1L, 3L), keysOf(second));
        assertEquals(Arrays.<Object>asList(5L), keysOf(third));
    }

    @Test
    void cursorPageOfADistinctColumnSkipsRepeatedValues() {
        seedVotes(1, 1, 2, 2, 3);

        CursorModelPage<Account> first = accounts.query().select("votes").distinct().orderBy("votes")
                .cursorPaginate(2);
        CursorModelPage<Account> second = accounts.query().select("votes").distinct().orderBy("votes")
                .cursorPaginate(2, first.nextCursor().get());

        assertEquals(Arrays.asList(1L, 2L),
                Arrays.asList(first.items().get(0).getLong("votes"), first.items().get(1).getLong("votes")));
        assertEquals(1, second.items().size());
        assertEquals(3L, second.items().get(0).getLong("votes"));
    }

    @Test
    void cursorPageOverAJoinReadsTheModelsOwnRows() {
        seed(4);
        database.sql("insert into orders (account_id, total) values (1, 5), (2, 5), (3, 5), (4, 5)");

        CursorModelPage<Account> first = accounts.query().select("accounts.*")
                .join("orders", "orders.account_id", "=", "accounts.id").orderBy("accounts.id").cursorPaginate(3);
        CursorModelPage<Account> second = accounts.query().select("accounts.*")
                .join("orders", "orders.account_id", "=", "accounts.id").orderBy("accounts.id")
                .cursorPaginate(3, first.nextCursor().get());

        assertEquals(Arrays.<Object>asList(1L, 2L, 3L), keysOf(first));
        assertEquals(Arrays.<Object>asList(4L), keysOf(second));
    }

    // ---- chunk, chunkById and lazy with their own limit and offset ----

    @Test
    void chunkStopsAtALimitSmallerThanTheTable() {
        seed(3);

        List<List<String>> pages = chunkNames(accounts.query().orderBy("id").limit(2), 2);

        assertEquals(Arrays.asList(Arrays.asList("user1", "user2")), pages);
    }

    @Test
    void chunkReadsEverythingWhenTheLimitIsLargerThanTheTable() {
        seed(3);

        List<List<String>> pages = chunkNames(accounts.query().orderBy("id").limit(10), 2);

        assertEquals(Arrays.asList(Arrays.asList("user1", "user2"), Arrays.asList("user3")), pages);
    }

    @Test
    void chunkStartsAfterTheOffset() {
        seed(3);

        List<List<String>> pages = chunkNames(accounts.query().orderBy("id").offset(1), 2);

        assertEquals(Arrays.asList(Arrays.asList("user2", "user3")), pages);
    }

    @Test
    void chunkWithAnOffsetPastTheTableReadsNothing() {
        seed(3);

        List<List<String>> pages = chunkNames(accounts.query().orderBy("id").offset(5), 2);

        assertTrue(pages.isEmpty());
    }

    @Test
    void chunkHonoursALimitAndAnOffsetTogether() {
        seed(7);

        List<List<String>> pages = chunkNames(accounts.query().orderBy("id").offset(2).limit(3), 2);

        assertEquals(Arrays.asList(Arrays.asList("user3", "user4"), Arrays.asList("user5")), pages);
    }

    @Test
    void chunkByIdStopsAtALimit() {
        seed(3);

        List<List<String>> pages = chunkByIdNames(accounts.query().limit(2), 2);

        assertEquals(Arrays.asList(Arrays.asList("user1", "user2")), pages);
    }

    @Test
    void chunkByIdStartsAfterTheOffset() {
        seed(3);

        List<List<String>> pages = chunkByIdNames(accounts.query().offset(1), 2);

        assertEquals(Arrays.asList(Arrays.asList("user2", "user3")), pages);
    }

    @Test
    void chunkByIdHonoursALimitAndAnOffsetTogether() {
        seed(7);

        List<List<String>> pages = chunkByIdNames(accounts.query().offset(2).limit(3), 2);

        assertEquals(Arrays.asList(Arrays.asList("user3", "user4"), Arrays.asList("user5")), pages);
    }

    @Test
    void lazyStopsAtALimit() {
        seed(3);

        List<String> names = names(accounts.query().orderBy("id").limit(2).lazy(2));

        assertEquals(Arrays.asList("user1", "user2"), names);
    }

    @Test
    void lazyHonoursALimitAndAnOffsetTogether() {
        seed(7);

        List<String> names = names(accounts.query().orderBy("id").offset(2).limit(3).lazy(2));

        assertEquals(Arrays.asList("user3", "user4", "user5"), names);
    }

    @Test
    void lazyByIdStopsAtALimit() {
        seed(3);

        List<String> names = names(accounts.query().limit(2).lazyById(2));

        assertEquals(Arrays.asList("user1", "user2"), names);
    }

    @Test
    void lazyByIdHonoursALimitAndAnOffsetTogether() {
        seed(7);

        List<String> names = names(accounts.query().offset(2).limit(3).lazyById(2));

        assertEquals(Arrays.asList("user3", "user4", "user5"), names);
    }

    @Test
    void chunkByIdPagesOnTheRawKeyOfAModelThatCastsItsKey() {
        seed(5);
        List<Object> keys = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();

        TextKeyAccount.QUERY.on(database.db).query().chunkById(2, chunk -> {
            sizes.add(chunk.size());
            keys.addAll(chunk.modelKeys());
            return true;
        });

        assertEquals(Arrays.asList(2, 2, 1), sizes);
        assertEquals(Arrays.<Object>asList("1", "2", "3", "4", "5"), keys);
    }

    @Test
    void lazyByIdPagesOnTheRawKeyOfAModelThatCastsItsKey() {
        seed(5);
        List<Object> keys = new ArrayList<>();

        for (TextKeyAccount account : TextKeyAccount.QUERY.on(database.db).query().lazyById(2)) {
            keys.add(account.getKey());
        }

        assertEquals(Arrays.<Object>asList("1", "2", "3", "4", "5"), keys);
    }

    // ---- cursors that stay open ----

    @Test
    void anOpenCursorHoldsItsConnectionUntilItIsClosed() {
        seed(2);
        assertEquals(0, database.heldConnections());

        QueryCursor<Account> cursor = accounts.query().orderBy("id").cursor();
        Iterator<Account> models = cursor.iterator();
        assertTrue(models.hasNext());
        int heldWhileOpen = database.heldConnections();
        cursor.close();

        assertTrue(heldWhileOpen > 0, "an open cursor must hold its connection");
        assertEquals(0, database.heldConnections());
    }
}
