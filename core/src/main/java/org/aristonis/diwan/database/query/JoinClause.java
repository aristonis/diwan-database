package org.aristonis.diwan.database.query;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;

/**
 * The conditions of one join, a port of Laravel's {@code JoinClause}: a {@link Builder} whose
 * where clause compiles after {@code on}.
 *
 * <pre>{@code
 * db.table("users").join("contacts", j -> j.on("users.id", "=", "contacts.id").where("contacts.active", true));
 * // select * from "users" inner join "contacts" on "users"."id" = "contacts"."id" and "contacts"."active" = ?
 * }</pre>
 *
 * <p>{@code on} compares two columns; every where form compares with a value, and its bindings
 * are copied into the outer query's join bucket when the join is attached. A nested group, from
 * {@link #on(Consumer)} or {@link #where(Consumer)}, is another join clause, so it compiles in
 * parentheses without {@code on}. A subquery inside a join condition is a plain {@code Builder}.
 *
 * <p>Only {@code on}, {@code orOn}, {@code where}, {@code orWhere}, {@code whereNull} and
 * {@code whereIn} return {@code JoinClause}; every other where form returns {@code Builder}, so
 * put those last in a chain. A self-typed {@code Builder<B>} was rejected: this class
 * extends {@code Builder}, which would already have fixed the type parameter, so it could not
 * narrow it.
 *
 * <p>A right join needs SQLite 3.39 or later (Android 14 and later ships it); on an older SQLite
 * the database's own error passes through as a {@link org.aristonis.diwan.database.QueryException}.
 *
 * <p>Mutable and not thread-safe, like every builder. The outer query keeps a private copy, so
 * changing a clause after the join was added changes nothing.
 */
public final class JoinClause extends Builder {

    private final String type;
    private final Object table;

    /** Creates an empty join of a type ({@code inner}, {@code left}, {@code right}, {@code cross}). */
    JoinClause(Connection connection, QueryGrammar grammar, Processor processor, String type, Object table) {
        super(connection, grammar, processor);
        this.type = type;
        this.table = table;
    }

    /**
     * Returns the join type.
     *
     * @return {@code inner}, {@code left}, {@code right} or {@code cross}, never {@code null}
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the joined table.
     *
     * @return the table name as given, possibly with {@code " as alias"}, or an {@link Expression}
     *     holding {@code (subquery) as "alias"}; never {@code null}
     */
    public Object getTable() {
        return table;
    }

    /**
     * Creates an empty join clause of the same type and table.
     *
     * @return the new clause, never {@code null}
     */
    @Override
    protected JoinClause newInstance() {
        return new JoinClause(getConnection(), getGrammar(), getProcessor(), type, table);
    }

    /**
     * Returns an independent copy of the same type and table.
     *
     * @return the copy, never {@code null}
     */
    @Override
    public JoinClause clone() {
        return (JoinClause) super.clone();
    }

    /**
     * Returns a new, empty join clause of the same type and table.
     *
     * @return the new clause, never {@code null}
     */
    @Override
    public JoinClause newQuery() {
        return newInstance();
    }

    // ---- on ----

    /**
     * Adds a condition comparing two columns with {@code =}.
     *
     * @param first the first column name, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this clause
     * @throws NullPointerException if an argument is {@code null}
     */
    public JoinClause on(String first, String second) {
        whereColumn(first, second);
        return this;
    }

    /**
     * Adds a condition comparing two columns.
     *
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}; an unknown one is taken as the second
     *     column, compared with {@code =}
     * @param second the second column name, never {@code null}
     * @return this clause
     * @throws NullPointerException if an argument is {@code null}
     */
    public JoinClause on(String first, String operator, String second) {
        whereColumn(first, operator, second);
        return this;
    }

    /**
     * Adds a group of conditions in parentheses; an empty group adds nothing.
     *
     * @param group fills a fresh join clause with the group's conditions, never {@code null}
     * @return this clause
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public JoinClause on(Consumer<JoinClause> group) {
        Objects.requireNonNull(group, "group");
        whereNested(query -> group.accept((JoinClause) query), AND);
        return this;
    }

    /**
     * Adds an {@code or} condition comparing two columns with {@code =}.
     *
     * @param first the first column name, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this clause
     * @throws NullPointerException if an argument is {@code null}
     */
    public JoinClause orOn(String first, String second) {
        orWhereColumn(first, second);
        return this;
    }

    /**
     * Adds an {@code or} condition comparing two columns.
     *
     * @param first the first column name, never {@code null}
     * @param operator the operator, as for {@link #on(String, String, String)}
     * @param second the second column name, never {@code null}
     * @return this clause
     * @throws NullPointerException if an argument is {@code null}
     */
    public JoinClause orOn(String first, String operator, String second) {
        orWhereColumn(first, operator, second);
        return this;
    }

    /**
     * Adds an {@code or} group of conditions in parentheses; an empty group adds nothing.
     *
     * @param group fills a fresh join clause with the group's conditions, never {@code null}
     * @return this clause
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public JoinClause orOn(Consumer<JoinClause> group) {
        Objects.requireNonNull(group, "group");
        whereNested(query -> group.accept((JoinClause) query), OR);
        return this;
    }

    // ---- covariant where forms ----

    /**
     * Adds a condition comparing a column with a value using {@code =}; the value is bound.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this clause
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map or array
     */
    @Override
    public JoinClause where(String column, Object value) {
        super.where(column, value);
        return this;
    }

    /**
     * Adds a condition comparing a column with a value; the value is bound.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this clause
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with an operator that cannot
     *     compare with {@code null}, or is a list, map or array
     */
    @Override
    public JoinClause where(String column, String operator, Object value) {
        super.where(column, operator, value);
        return this;
    }

    /**
     * Adds a group of {@code column = value} conditions.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this clause
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map or array
     */
    @Override
    public JoinClause where(Map<String, ?> conditions) {
        super.where(conditions);
        return this;
    }

    /**
     * Adds a group of conditions in parentheses; the group's builder is a join clause.
     *
     * @param group fills a fresh builder with the group's conditions, never {@code null}
     * @return this clause
     * @throws NullPointerException if {@code group} is {@code null}
     */
    @Override
    public JoinClause where(Consumer<Builder> group) {
        super.where(group);
        return this;
    }

    /**
     * Adds an {@code or} condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this clause
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map or array
     */
    @Override
    public JoinClause orWhere(String column, Object value) {
        super.orWhere(column, value);
        return this;
    }

    /**
     * Adds an {@code or} condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this clause
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with an operator that cannot
     *     compare with {@code null}, or is a list, map or array
     */
    @Override
    public JoinClause orWhere(String column, String operator, Object value) {
        super.orWhere(column, operator, value);
        return this;
    }

    /**
     * Adds an {@code or} group of {@code column = value} conditions.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this clause
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map or array
     */
    @Override
    public JoinClause orWhere(Map<String, ?> conditions) {
        super.orWhere(conditions);
        return this;
    }

    /**
     * Adds an {@code or} group of conditions in parentheses.
     *
     * @param group fills a fresh builder with the group's conditions, never {@code null}
     * @return this clause
     * @throws NullPointerException if {@code group} is {@code null}
     */
    @Override
    public JoinClause orWhere(Consumer<Builder> group) {
        super.orWhere(group);
        return this;
    }

    /**
     * Adds a condition that each column is {@code null}.
     *
     * @param columns the column names, none {@code null}
     * @return this clause
     * @throws NullPointerException if a column is {@code null}
     */
    @Override
    public JoinClause whereNull(String... columns) {
        super.whereNull(columns);
        return this;
    }

    /**
     * Adds a condition that a column is in a list; the values are bound.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link Builder#whereIn(String, Collection)}
     * @return this clause
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public JoinClause whereIn(String column, Collection<?> values) {
        super.whereIn(column, values);
        return this;
    }
}
