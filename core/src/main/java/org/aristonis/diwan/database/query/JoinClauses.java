package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;

/**
 * The join layer of {@link Builder}: inner, left, right and cross joins on a table or a subquery.
 *
 * <p>Each join is a {@link JoinClause}. The outer query keeps a private copy of it, and the
 * clause's bindings go to the join bucket when it is attached, after the bindings of a joined
 * subquery, which is the order of the {@code ?} marks.
 */
abstract class JoinClauses extends OrderClauses {

    static final String INNER = "inner";
    static final String LEFT = "left";
    static final String RIGHT = "right";
    static final String CROSS = "cross";

    JoinClauses(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- inner ----

    /**
     * Adds an inner join on two columns: {@code inner join "t" on "a" = "b"}.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}; an unknown one is taken as the second
     *     column, compared with {@code =}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder join(String table, String first, String operator, String second) {
        return addJoin(requireTable(table), INNER, onColumns(first, operator, second));
    }

    /**
     * Adds an inner join whose conditions a consumer adds to a {@link JoinClause}.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder join(String table, Consumer<JoinClause> on) {
        return addJoin(requireTable(table), INNER, on);
    }

    /**
     * Adds an inner join comparing a column with a bound value: {@code inner join "t" on "a" = ?}.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param first the column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code table}, {@code first} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the value is not allowed, as for {@code where}
     */
    public final Builder joinWhere(String table, String first, String operator, Object second) {
        return addJoin(requireTable(table), INNER, onValue(first, operator, second));
    }

    /**
     * Adds an inner join on a subquery: {@code inner join (select ...) as "alias" on "a" = "b"}.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder joinSub(Builder query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(query), as, INNER, onColumns(first, operator, second));
    }

    /**
     * Adds an inner join on a subquery built by a consumer.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder joinSub(Consumer<Builder> query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, INNER, onColumns(first, operator, second));
    }

    /**
     * Adds an inner join on a subquery given as trusted SQL.
     *
     * @param query the subquery's SQL, never {@code null} or blank, never user input
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code query} is blank
     */
    public final Builder joinSub(String query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(query), as, INNER, onColumns(first, operator, second));
    }

    /**
     * Adds an inner join on a subquery with conditions a consumer adds.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder joinSub(Builder query, String as, Consumer<JoinClause> on) {
        return addJoinSub(SubQuery.of(query), as, INNER, on);
    }

    /**
     * Adds an inner join on a subquery built by a consumer, with conditions another consumer adds.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder joinSub(Consumer<Builder> query, String as, Consumer<JoinClause> on) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, INNER, on);
    }

    // ---- left ----

    /**
     * Adds a left join on two columns.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param first the first column name, never {@code null}
     * @param operator the operator, as for {@link Builder#join(String, String, String, String)}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder leftJoin(String table, String first, String operator, String second) {
        return addJoin(requireTable(table), LEFT, onColumns(first, operator, second));
    }

    /**
     * Adds a left join whose conditions a consumer adds.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder leftJoin(String table, Consumer<JoinClause> on) {
        return addJoin(requireTable(table), LEFT, on);
    }

    /**
     * Adds a left join comparing a column with a bound value.
     *
     * @param table the table name, never {@code null}
     * @param first the column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the value, as for {@link Builder#joinWhere}
     * @return this builder
     * @throws NullPointerException if {@code table}, {@code first} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the value is not allowed, as for {@code where}
     */
    public final Builder leftJoinWhere(String table, String first, String operator, Object second) {
        return addJoin(requireTable(table), LEFT, onValue(first, operator, second));
    }

    /**
     * Adds a left join on a subquery.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder leftJoinSub(Builder query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(query), as, LEFT, onColumns(first, operator, second));
    }

    /**
     * Adds a left join on a subquery built by a consumer.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder leftJoinSub(Consumer<Builder> query, String as, String first, String operator,
            String second) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, LEFT, onColumns(first, operator, second));
    }

    /**
     * Adds a left join on a subquery with conditions a consumer adds.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder leftJoinSub(Builder query, String as, Consumer<JoinClause> on) {
        return addJoinSub(SubQuery.of(query), as, LEFT, on);
    }

    /**
     * Adds a left join on a subquery built by a consumer, with conditions another consumer adds.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder leftJoinSub(Consumer<Builder> query, String as, Consumer<JoinClause> on) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, LEFT, on);
    }

    /**
     * Adds a left join on a subquery given as trusted SQL.
     *
     * @param query the subquery's SQL, never {@code null} or blank, never user input
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code query} is blank
     */
    public final Builder leftJoinSub(String query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(query), as, LEFT, onColumns(first, operator, second));
    }

    // ---- right ----

    /**
     * Adds a right join on two columns. SQLite supports it from 3.39; see {@link JoinClause}.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param first the first column name, never {@code null}
     * @param operator the operator, as for {@link Builder#join(String, String, String, String)}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder rightJoin(String table, String first, String operator, String second) {
        return addJoin(requireTable(table), RIGHT, onColumns(first, operator, second));
    }

    /**
     * Adds a right join whose conditions a consumer adds.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder rightJoin(String table, Consumer<JoinClause> on) {
        return addJoin(requireTable(table), RIGHT, on);
    }

    /**
     * Adds a right join comparing a column with a bound value.
     *
     * @param table the table name, never {@code null}
     * @param first the column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the value, as for {@link Builder#joinWhere}
     * @return this builder
     * @throws NullPointerException if {@code table}, {@code first} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the value is not allowed, as for {@code where}
     */
    public final Builder rightJoinWhere(String table, String first, String operator, Object second) {
        return addJoin(requireTable(table), RIGHT, onValue(first, operator, second));
    }

    /**
     * Adds a right join on a subquery.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder rightJoinSub(Builder query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(query), as, RIGHT, onColumns(first, operator, second));
    }

    /**
     * Adds a right join on a subquery built by a consumer.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder rightJoinSub(Consumer<Builder> query, String as, String first, String operator,
            String second) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, RIGHT, onColumns(first, operator, second));
    }

    /**
     * Adds a right join on a subquery with conditions a consumer adds.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder rightJoinSub(Builder query, String as, Consumer<JoinClause> on) {
        return addJoinSub(SubQuery.of(query), as, RIGHT, on);
    }

    /**
     * Adds a right join on a subquery built by a consumer, with conditions another consumer adds.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @param on adds the join's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder rightJoinSub(Consumer<Builder> query, String as, Consumer<JoinClause> on) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, RIGHT, on);
    }

    /**
     * Adds a right join on a subquery given as trusted SQL.
     *
     * @param query the subquery's SQL, never {@code null} or blank, never user input
     * @param as the alias, never {@code null}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code query} is blank
     */
    public final Builder rightJoinSub(String query, String as, String first, String operator, String second) {
        return addJoinSub(SubQuery.of(query), as, RIGHT, onColumns(first, operator, second));
    }

    // ---- cross ----

    /**
     * Adds a cross join without conditions: {@code cross join "t"}.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @return this builder
     * @throws NullPointerException if {@code table} is {@code null}
     */
    public final Builder crossJoin(String table) {
        return addJoin(requireTable(table), CROSS, on -> { });
    }

    /**
     * Adds a cross join with a condition on two columns, as Laravel allows.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder crossJoin(String table, String first, String operator, String second) {
        return addJoin(requireTable(table), CROSS, onColumns(first, operator, second));
    }

    /**
     * Adds a cross join on a subquery: {@code cross join (select ...) as "alias"}.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder crossJoinSub(Builder query, String as) {
        return addJoinSub(SubQuery.of(query), as, CROSS, on -> { });
    }

    /**
     * Adds a cross join on a subquery built by a consumer.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder crossJoinSub(Consumer<Builder> query, String as) {
        return addJoinSub(SubQuery.of(subQuery(query)), as, CROSS, on -> { });
    }

    /**
     * Adds a cross join on a subquery given as trusted SQL.
     *
     * @param query the subquery's SQL, never {@code null} or blank, never user input
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code query} is blank
     */
    public final Builder crossJoinSub(String query, String as) {
        return addJoinSub(SubQuery.of(query), as, CROSS, on -> { });
    }

    // ---- helpers ----

    private static String requireTable(String table) {
        return Objects.requireNonNull(table, "table");
    }

    private static Consumer<JoinClause> onColumns(String first, String operator, String second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(second, "second");
        return join -> join.on(first, operator, second);
    }

    private static Consumer<JoinClause> onValue(String first, String operator, Object second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(operator, "operator");
        return join -> join.where(first, operator, second);
    }

    /**
     * Writes the subquery as {@code (sql) as "alias"}, puts its bindings in the join bucket first,
     * then adds the join; Laravel's {@code joinSub}.
     */
    private Builder addJoinSub(SubQuery sub, String as, String type, Consumer<JoinClause> on) {
        Objects.requireNonNull(as, "as");
        Objects.requireNonNull(on, "on");
        Expression table = Expression.raw("(" + sub.sql + ") as " + grammar.wrapTable(as));
        return addJoin(table, type, on, sub.bindings);
    }

    /**
     * Fills a new join clause, keeps a private copy of it so later changes to the caller's clause
     * are not seen, and copies its bindings into the join bucket.
     */
    Builder addJoin(Object table, String type, Consumer<JoinClause> on) {
        return addJoin(table, type, on, Collections.emptyList());
    }

    private Builder addJoin(Object table, String type, Consumer<JoinClause> on, List<Object> leading) {
        Objects.requireNonNull(on, "on");
        JoinClause join = new JoinClause(connection, grammar, processor, type, table);
        on.accept(join);
        JoinClause kept = join.clone();
        joins.add(kept);
        bindings.get(BindingType.JOIN).addAll(leading);
        bindings.get(BindingType.JOIN).addAll(kept.getBindings());
        return self();
    }
}
