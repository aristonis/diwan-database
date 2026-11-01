package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;

/**
 * The subquery source and union layer of {@link Builder}: reading from or selecting a subquery,
 * and joining queries with {@code union}.
 *
 * <p>A subquery in the from or select clause is compiled when it is attached, as Laravel's
 * {@code createSub} does, so later changes to the caller's builder are not seen. A union keeps a
 * private copy of its query, which the grammar compiles with the outer query.
 */
abstract class SubqueryClauses extends JoinClauses {

    SubqueryClauses(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- from ----

    /**
     * Reads from a subquery: {@code from (select ...) as "alias"}. Its bindings replace the from
     * bucket.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder fromSub(Builder query, String as) {
        return addFromSub(SubQuery.of(query), as);
    }

    /**
     * Reads from a subquery built by a consumer.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder fromSub(Consumer<Builder> query, String as) {
        return addFromSub(SubQuery.of(subQuery(query)), as);
    }

    /**
     * Reads from a subquery given as trusted SQL.
     *
     * @param query the subquery's SQL, never {@code null} or blank, never user input
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code query} is blank
     */
    public final Builder fromSub(String query, String as) {
        return addFromSub(SubQuery.of(query), as);
    }

    /**
     * Reads from a subquery; Laravel's {@code from($query, $as)}, the same as
     * {@link Builder#fromSub(Builder, String)}.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder from(Builder query, String as) {
        return fromSub(query, as);
    }

    /**
     * Reads from a subquery built by a consumer; the same as
     * {@link Builder#fromSub(Consumer, String)}.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder from(Consumer<Builder> query, String as) {
        return fromSub(query, as);
    }

    /**
     * Reads from trusted SQL written as given.
     *
     * @param sql the SQL after {@code from}, never {@code null} or blank, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Builder fromRaw(String sql) {
        return fromRaw(sql, Collections.emptyList());
    }

    /**
     * Reads from trusted SQL with bindings; the bindings replace the from bucket. Laravel appends
     * them, so a second call would leave the first call's values behind.
     *
     * @param sql the SQL after {@code from}, never {@code null} or blank, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Builder fromRaw(String sql, List<?> bindings) {
        Expression expression = Expression.raw(sql);
        Objects.requireNonNull(bindings, "bindings");
        this.from = null;
        this.fromExpression = expression;
        return setBindings(bindings, BindingType.FROM);
    }

    // ---- select ----

    /**
     * Adds a subquery to the selection: {@code (select ...) as "alias"}. Its bindings go to the
     * select bucket.
     *
     * @param query the subquery, never {@code null}; compiled now
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder selectSub(Builder query, String as) {
        return addSelectSub(SubQuery.of(query), as);
    }

    /**
     * Adds a subquery built by a consumer to the selection.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder selectSub(Consumer<Builder> query, String as) {
        return addSelectSub(SubQuery.of(subQuery(query)), as);
    }

    /**
     * Adds a subquery given as trusted SQL to the selection.
     *
     * @param query the subquery's SQL, never {@code null} or blank, never user input
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code query} is blank
     */
    public final Builder selectSub(String query, String as) {
        return addSelectSub(SubQuery.of(query), as);
    }

    /**
     * Adds a subquery to the selection under an alias; Laravel's {@code addSelect(['alias' => $query])},
     * the same as {@link Builder#selectSub(Builder, String)}.
     *
     * @param as the alias, never {@code null}
     * @param query the subquery, never {@code null}; compiled now
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder addSelect(String as, Builder query) {
        return selectSub(query, as);
    }

    /**
     * Adds a subquery built by a consumer to the selection under an alias.
     *
     * @param as the alias, never {@code null}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder addSelect(String as, Consumer<Builder> query) {
        return selectSub(query, as);
    }

    // ---- union ----

    /**
     * Joins a query with {@code union}, which drops duplicate rows. From now on, orders, limit and
     * offset apply to the whole union.
     *
     * @param query the query, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder union(Builder query) {
        return addUnion(frozen(query), false);
    }

    /**
     * Joins a query built by a consumer with {@code union}.
     *
     * @param query fills a fresh builder with the query, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder union(Consumer<Builder> query) {
        return addUnion(subQuery(query), false);
    }

    /**
     * Joins a query with {@code union all}, which keeps duplicate rows.
     *
     * @param query the query, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder unionAll(Builder query) {
        return addUnion(frozen(query), true);
    }

    /**
     * Joins a query built by a consumer with {@code union all}.
     *
     * @param query fills a fresh builder with the query, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder unionAll(Consumer<Builder> query) {
        return addUnion(subQuery(query), true);
    }

    private Builder addFromSub(SubQuery sub, String as) {
        Objects.requireNonNull(as, "as");
        return fromRaw("(" + sub.sql + ") as " + grammar.wrapTable(as), sub.bindings);
    }

    private Builder addSelectSub(SubQuery sub, String as) {
        Objects.requireNonNull(as, "as");
        return selectRaw("(" + sub.sql + ") as " + grammar.wrap(as), sub.bindings);
    }

    private Builder addUnion(Builder query, boolean all) {
        unions.add(new Union(query, all));
        bindings.get(BindingType.UNION).addAll(query.getBindings());
        return self();
    }
}
