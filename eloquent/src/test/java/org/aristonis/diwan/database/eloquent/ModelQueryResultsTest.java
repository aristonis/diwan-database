package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reading results of a model query: pluck, subquery lists, aggregates, model keys and bulk deletes. */
class ModelQueryResultsTest {

    /** An account with open mass assignment. */
    static final class Account extends Model {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** An account whose key is read as text. */
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

    /** A member keyed by member_id. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected String primaryKey() {
            return "member_id";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A person whose column name holds a space. */
    static final class Person extends Model {
        static final ModelQuery<Person> QUERY = ModelQuery.of(Person.class, Person::new);

        @Override
        protected String table() {
            return "people";
        }

        @Override
        protected boolean timestamps() {
            return false;
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

    @Test
    void pluckReadsOneColumnOfEveryMatchingRow() {
        seedVotes(1, 2, 3);

        List<Object> names = accounts.where("votes", ">", 1).orderBy("id").pluck("name");

        assertEquals(Arrays.<Object>asList("user2", "user3"), names);
    }

    @Test
    void pluckOverAJoinReadsTheJoinedColumn() {
        seedVotes(1, 1);
        database.sql("insert into orders (account_id, total) values (1, 9), (2, 4)");

        List<Object> totals = accounts.query().join("orders", "orders.account_id", "=", "accounts.id")
                .orderBy("orders.id").pluck("orders.total");

        assertEquals(Arrays.<Object>asList(9L, 4L), asLongs(totals));
    }

    @Test
    void pluckReadsAColumnWhoseNameHoldsASpace() {
        database.sql("create table people (id integer primary key autoincrement, \"full name\" text)");
        ModelQuery<Person> people = Person.QUERY.on(database.db);
        people.create(row("full name", "Ann Lee"));
        people.create(row("full name", "Bob Ray"));

        List<Object> names = people.query().orderBy("id").pluck("full name");

        assertEquals(Arrays.<Object>asList("Ann Lee", "Bob Ray"), names);
    }

    @Test
    void whereInTakesASubqueryAsItsList() {
        seedVotes(1, 1, 1);
        database.sql("insert into orders (account_id, total) values (1, 9), (3, 8), (2, 1)");

        List<Object> names = accounts.query()
                .whereIn("id", sub -> sub.select("account_id").from("orders").where("total", ">", 5))
                .orderBy("id").pluck("name");

        assertEquals(Arrays.<Object>asList("user1", "user3"), names);
    }

    @Test
    void minMaxSumAndAvgReadTheModelsRows() {
        seedVotes(1, 2, 3, 4);

        Totals totals = new Totals(accounts.query());

        assertEquals(1L, totals.min("votes"));
        assertEquals(4L, totals.max("votes"));
        assertEquals(10L, totals.sum("votes"));
        assertEquals(2.5, totals.avg("votes"));
    }

    @Test
    void aggregatesRespectTheQueryConstraints() {
        seedVotes(1, 2, 3, 4);

        Totals big = new Totals(accounts.where("votes", ">", 2));

        assertEquals(3L, big.min("votes"));
        assertEquals(7L, big.sum("votes"));
    }

    @Test
    void minAndMaxOfADateTimeColumnReturnTheStoredValues() {
        database.sql("insert into accounts (name, email, created_at) values"
                + " ('a', 'a@x', '2020-01-01 00:00:00'), ('b', 'b@x', '2021-06-01 12:30:00')");

        EloquentBuilder<Account> query = accounts.query();

        assertEquals("2021-06-01 12:30:00", query.toBase().max("created_at").get());
        assertEquals("2020-01-01 00:00:00", query.toBase().min("created_at").get());
    }

    @Test
    void modelKeysListTheKeysOfTheResults() {
        seedVotes(1, 2, 3);

        List<Object> keys = accounts.query().orderBy("id").get().modelKeys();

        assertEquals(Arrays.<Object>asList(1L, 2L, 3L), asLongs(keys));
    }

    @Test
    void modelKeysFollowTheCollectionOrder() {
        seedVotes(1, 2, 3);

        ModelCollection<Account> results = accounts.query().orderBy("id", "desc").get();

        assertEquals(Arrays.<Object>asList(3L, 2L, 1L), asLongs(results.modelKeys()));
        assertTrue(results.find(2L).isPresent());
    }

    @Test
    void modelKeysUseACustomPrimaryKey() {
        database.sql("create table members (member_id integer primary key autoincrement, name text)");
        ModelQuery<Member> members = Member.QUERY.on(database.db);
        members.create(row("name", "Ann"));
        members.create(row("name", "Bob"));

        List<Object> keys = members.query().orderBy("member_id").get().modelKeys();

        assertEquals(Arrays.<Object>asList(1L, 2L), asLongs(keys));
    }

    @Test
    void modelKeysUseACastPrimaryKey() {
        seedVotes(1, 2);

        List<Object> keys = TextKeyAccount.QUERY.on(database.db).query().orderBy("id").get().modelKeys();

        assertEquals(Arrays.<Object>asList("1", "2"), keys);
    }

    @Test
    void modelKeysOfAConstrainedQueryListOnlyTheMatches() {
        seedVotes(1, 2, 3);

        List<Object> keys = accounts.where("votes", ">", 1).orderBy("id").get().modelKeys();

        assertEquals(Arrays.<Object>asList(2L, 3L), asLongs(keys));
    }

    @Test
    void modelKeysOfAJoinedQueryListTheModelsKeys() {
        seedVotes(1, 1, 1);
        database.sql("insert into orders (account_id, total) values (2, 9), (3, 8)");

        List<Object> keys = accounts.query().select("accounts.*")
                .join("orders", "orders.account_id", "=", "accounts.id").orderBy("accounts.id").get().modelKeys();

        assertEquals(Arrays.<Object>asList(2L, 3L), asLongs(keys));
    }

    @Test
    void deleteWithOrderAndLimitRemovesOnlyThoseRows() {
        seedVotes(1, 2, 3, 4, 5);

        int deleted = accounts.query().orderBy("id").limit(2).delete();

        assertEquals(2, deleted);
        assertEquals(Arrays.<Object>asList(3L, 4L, 5L), asLongs(accounts.query().orderBy("id").get().modelKeys()));
    }

    /** Copies numeric values as longs, since SQLite reads small integers as Integer. */
    private static List<Object> asLongs(List<?> values) {
        List<Object> longs = new ArrayList<>();
        for (Object value : values) {
            longs.add(((Number) value).longValue());
        }
        return longs;
    }

    /** Reads the four aggregates through the query a model builder wraps. */
    private static final class Totals {
        private final EloquentBuilder<Account> query;

        Totals(EloquentBuilder<Account> query) {
            this.query = query;
        }

        Object min(String column) {
            return ((Number) query.toBase().min(column).get()).longValue();
        }

        Object max(String column) {
            return ((Number) query.toBase().max(column).get()).longValue();
        }

        Object sum(String column) {
            return query.toBase().sum(column).longValue();
        }

        Object avg(String column) {
            return query.toBase().avg(column).get().doubleValue();
        }
    }
}
