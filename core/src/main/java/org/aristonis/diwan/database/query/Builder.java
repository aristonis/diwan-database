package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import org.aristonis.diwan.database.Connection;

/**
 * Builds one query fluently, a hand port of Laravel's {@code Illuminate\Database\Query\Builder}.
 *
 * <pre>{@code
 * String sql = db.table("users").select("id", "name").where("active", true).orderBy("name").toSql();
 * // select "id", "name" from "users" where "active" = ? order by "name" asc
 * }</pre>
 *
 * <p>Method names and behavior follow Laravel. PHP's optional arguments become overloads: a
 * two-argument {@code where(column, value)} compares with {@code =}, a nested group is a
 * {@code Consumer<Builder>}, and a map of conditions is a {@code Map}. As in Laravel:
 * <ul>
 *   <li>a {@code null} value with {@code =} or {@code <=>} becomes {@code is null}, and with
 *       {@code !=} or {@code <>} becomes {@code is not null}; with any other known operator it is
 *       rejected;</li>
 *   <li>an operator the builder and grammar do not know is taken as the value, compared with
 *       {@code =}, so an operator never reaches the SQL unchecked;</li>
 *   <li>a negative limit is ignored and a negative offset becomes zero.</li>
 * </ul>
 *
 * <p>Values are always bound as {@code ?}; an {@link Expression} value is written as given.
 * Identifiers are always quoted by the grammar. Methods ending in {@code Raw} write trusted SQL
 * as given and must never receive user input. An enum value is bound by its name.
 *
 * <p>A subquery is a {@code Builder}, a {@code Consumer<Builder>} that fills a fresh one, or, where
 * a method says so, trusted SQL text. It is copied or compiled when it is attached, with its
 * bindings in the bucket of the clause that holds it, so changing it afterwards changes nothing
 * here. Joins are {@link JoinClause}s; after the first {@link #union(Builder)}, orders, limit and
 * offset apply to the whole union.
 *
 * <p>A builder is mutable and not safe to share between threads. Every method that returns
 * {@code Builder} changes this builder and returns it. {@link #clone()} gives an independent copy.
 * Lists returned by the getters are unmodifiable views of a snapshot.
 */
public class Builder extends PageQueries {


    /**
     * Creates an empty builder. Applications start from a {@link QueryFactory} instead.
     *
     * @param connection the connection, never {@code null}
     * @param grammar the grammar, never {@code null}
     * @param processor the processor, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    @Override
    final Builder self() {
        return this;
    }

    /**
     * Creates the empty builder that {@link #clone()} and {@link #newQuery()} fill; a subclass
     * returns its own type.
     *
     * @return a new, empty builder on the same connection, grammar and processor, never {@code null}
     */
    @Override
    protected Builder newInstance() {
        return new Builder(getConnection(), getGrammar(), getProcessor());
    }

    /**
     * Returns an independent copy: changing either builder, including a nested group, a join or
     * a union, never changes the other. Joins, unions and subqueries are private copies that no
     * builder ever changes, so the copy shares them and only the lists are copied.
     *
     * @return the copy, never {@code null}
     */
    @Override
    public Builder clone() {
        Builder copy = newInstance();
        for (BindingType type : BindingType.values()) {
            copy.bindings.get(type).addAll(bindings.get(type));
        }
        copy.columns = new ArrayList<>(columns);
        copy.distinct = distinct;
        copy.from = from;
        copy.fromExpression = fromExpression;
        copy.joins = new ArrayList<>(joins);
        copy.wheres = new ArrayList<>(wheres.size());
        for (Where where : wheres) {
            copy.wheres.add(where instanceof Where.Nested
                    ? new Where.Nested(where.conjunction(), ((Where.Nested) where).builder().clone()) : where);
        }
        copy.groups = new ArrayList<>(groups);
        copy.havings = new ArrayList<>(havings.size());
        for (Having having : havings) {
            copy.havings.add(having instanceof Having.Nested
                    ? new Having.Nested(having.conjunction(), ((Having.Nested) having).builder().clone()) : having);
        }
        copy.orders = new ArrayList<>(orders);
        copy.limit = limit;
        copy.offset = offset;
        copy.aggregate = aggregate;
        copy.unions = new ArrayList<>(unions);
        copy.unionOrders = new ArrayList<>(unionOrders);
        copy.unionLimit = unionLimit;
        copy.unionOffset = unionOffset;
        return copy;
    }

    /**
     * Returns a new, empty builder on the same connection, grammar and processor.
     *
     * @return the new builder, never {@code null}
     */
    public Builder newQuery() {
        return newInstance();
    }
}
