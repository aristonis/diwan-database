package org.aristonis.diwan.database.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.Internal;

/**
 * The state layer of {@link Builder}: fields, bindings, getters, copying and compiling to SQL.
 *
 * <p>Package-private: the public methods are documented as {@link Builder}'s own, and links
 * always target {@code Builder}. The builder is split into layers only to keep each file small.
 */
abstract class BuilderState {

    static final String AND = "and";
    static final String OR = "or";
    static final String NOT = " not";
    static final String ASC = "asc";
    static final String DESC = "desc";
    static final String DEFAULT_KEY = "id";
    static final String DEFAULT_TIMESTAMP = "created_at";
    static final int DEFAULT_PER_PAGE = 15;

    final Connection connection;
    final QueryGrammar grammar;
    final Processor processor;
    final EnumMap<BindingType, List<Object>> bindings = new EnumMap<>(BindingType.class);
    List<Object> columns = new ArrayList<>();
    boolean distinct;
    String from;
    Expression fromExpression;
    List<JoinClause> joins = new ArrayList<>();
    List<Where> wheres = new ArrayList<>();
    List<Object> groups = new ArrayList<>();
    List<Having> havings = new ArrayList<>();
    List<Order> orders = new ArrayList<>();
    Integer limit;
    Integer offset;
    Aggregate aggregate;
    List<Union> unions = new ArrayList<>();
    List<Order> unionOrders = new ArrayList<>();
    Integer unionLimit;
    Integer unionOffset;

    /** Creates the shared state; every argument is checked for {@code null}. */
    BuilderState(Connection connection, QueryGrammar grammar, Processor processor) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.grammar = Objects.requireNonNull(grammar, "grammar");
        this.processor = Objects.requireNonNull(processor, "processor");
        for (BindingType type : BindingType.values()) {
            bindings.put(type, new ArrayList<>());
        }
    }

    /** Returns this builder as its public type, so every fluent layer returns {@code Builder}. */
    abstract Builder self();

    /**
     * Creates the empty builder that Builder's {@code clone()} and {@code newQuery()} fill.
     *
     * @return a new, empty builder on the same connection, grammar and processor, never {@code null}
     */
    protected abstract Builder newInstance();

    // ---- bindings and SQL ----

    /**
     * Adds a value to a binding bucket. An enum is bound by its name.
     *
     * @param value the value, may be {@code null}
     * @param type the bucket, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public final Builder addBinding(Object value, BindingType type) {
        bindings.get(Objects.requireNonNull(type, "type")).add(Clauses.castBinding(value));
        return self();
    }

    /**
     * Adds values to a binding bucket, in order.
     *
     * @param values the values, never {@code null}, may contain {@code null}
     * @param type the bucket, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder addBindings(List<?> values, BindingType type) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(type, "type");
        for (Object value : values) {
            addBinding(value, type);
        }
        return self();
    }

    /**
     * Replaces the values of one binding bucket.
     *
     * @param values the values, never {@code null}, may contain {@code null}
     * @param type the bucket, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder setBindings(List<?> values, BindingType type) {
        Objects.requireNonNull(values, "values");
        bindings.get(Objects.requireNonNull(type, "type")).clear();
        return addBindings(values, type);
    }

    /**
     * Returns every binding, flattened in {@link BindingType} order, which is the order of the
     * {@code ?} marks in {@link Builder#toSql()}. Values are as given, before the grammar prepares them.
     *
     * @return the bindings, unmodifiable, never {@code null}, may contain {@code null}
     */
    public final List<Object> getBindings() {
        List<Object> flat = new ArrayList<>();
        for (List<Object> bucket : bindings.values()) {
            flat.addAll(bucket);
        }
        return Collections.unmodifiableList(flat);
    }

    /**
     * Returns the bindings by bucket.
     *
     * @return a snapshot holding every bucket in {@link BindingType} order, unmodifiable, never
     *     {@code null}
     */
    public final Map<BindingType, List<Object>> getRawBindings() {
        EnumMap<BindingType, List<Object>> copy = new EnumMap<>(BindingType.class);
        for (Map.Entry<BindingType, List<Object>> entry : bindings.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Compiles the query to a select statement.
     *
     * @return the SQL with {@code ?} marks, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    public final String toSql() {
        return grammar.compileSelect(self());
    }

    /**
     * Compiles the query with its bindings written in as literals, for display and logging only.
     *
     * <p>Never run the result: escaping a literal is weaker than binding it. Literals assume
     * standard SQL strings, where a backslash is an ordinary character, as on SQLite and on
     * PostgreSQL with {@code standard_conforming_strings} on.
     *
     * @return the SQL with values inlined, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause or cannot
     *     write a binding as a literal
     * @throws IllegalArgumentException if a string binding holds a NUL character
     */
    public final String toRawSql() {
        return grammar.substituteBindingsIntoRawSql(toSql(), grammar.prepareBindings(getBindings()));
    }

    /**
     * Wraps trusted SQL so it is written as given; a shortcut for {@link Expression#raw}.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @return the expression, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Expression raw(String sql) {
        return Expression.raw(sql);
    }

    // ---- state for grammars ----

    /**
     * Returns the connection.
     *
     * @return the connection, never {@code null}
     */
    public final Connection getConnection() {
        return connection;
    }

    /**
     * Returns the grammar.
     *
     * @return the grammar, never {@code null}
     */
    public final QueryGrammar getGrammar() {
        return grammar;
    }

    /**
     * Returns the processor.
     *
     * @return the processor, never {@code null}
     */
    public final Processor getProcessor() {
        return processor;
    }

    /**
     * Returns the selected columns.
     *
     * @return the columns, each a {@code String} or {@link Expression}, unmodifiable, never
     *     {@code null}; empty when none were selected, which compiles to {@code *}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Object> getColumns() {
        return Collections.unmodifiableList(new ArrayList<>(columns));
    }

    /**
     * Returns whether the query returns only distinct rows.
     *
     * @return {@code true} if {@link Builder#distinct()} was called
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final boolean isDistinct() {
        return distinct;
    }

    /**
     * Returns the table.
     *
     * @return the table as given, possibly with {@code " as alias"}, or {@code null} if none or if
     *     the query reads from a subquery or raw SQL ({@link Builder#getFromExpression()})
     */
    public final String getFrom() {
        return from;
    }

    /**
     * Returns the subquery or raw SQL the query reads from, set by {@link Builder#fromSub} or
     * {@link Builder#fromRaw}.
     *
     * @return the SQL written after {@code from}, empty when the query reads a table or nothing
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final Optional<Expression> getFromExpression() {
        return Optional.ofNullable(fromExpression);
    }

    /**
     * Fails when the query has no table to write to. A subquery or raw SQL source compiles to
     * {@code update (select ...)}, which no database accepts, and the grammars need the table name
     * to quote; refusing here gives a clear message instead of a failure inside the grammar.
     */
    final void requireTable() {
        if (from == null) {
            throw new IllegalStateException(fromExpression == null
                    ? "write needs a table name; the query has no table"
                    : "write needs a table name; the query reads from a subquery");
        }
    }

    /**
     * Tells whether the query has joins, without copying them as {@code getJoins()} does.
     *
     * @return {@code true} if at least one join was added
     */
    public final boolean hasJoins() {
        return !joins.isEmpty();
    }

    /**
     * Returns the joins.
     *
     * @return copies of the joins in the order added, unmodifiable, never {@code null}; changing a
     *     copy never changes this builder
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<JoinClause> getJoins() {
        List<JoinClause> copies = new ArrayList<>(joins.size());
        for (JoinClause join : joins) {
            copies.add(join.clone());
        }
        return Collections.unmodifiableList(copies);
    }

    /**
     * Returns the where conditions.
     *
     * @return the conditions in the order added, unmodifiable, never {@code null}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Where> getWheres() {
        return Collections.unmodifiableList(new ArrayList<>(wheres));
    }

    /**
     * Returns the group by entries.
     *
     * @return the entries, each a {@code String} or {@link Expression}, unmodifiable, never
     *     {@code null}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Object> getGroups() {
        return Collections.unmodifiableList(new ArrayList<>(groups));
    }

    /**
     * Returns the having conditions.
     *
     * @return the conditions in the order added, unmodifiable, never {@code null}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Having> getHavings() {
        return Collections.unmodifiableList(new ArrayList<>(havings));
    }

    /**
     * Returns the orders.
     *
     * @return the orders in the order added, unmodifiable, never {@code null}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Order> getOrders() {
        return Collections.unmodifiableList(new ArrayList<>(orders));
    }

    /**
     * Returns the row limit.
     *
     * @return the limit, empty if none
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final OptionalInt getLimit() {
        return limit == null ? OptionalInt.empty() : OptionalInt.of(limit);
    }

    /**
     * Returns the number of rows skipped.
     *
     * @return the offset, empty if none
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final OptionalInt getOffset() {
        return offset == null ? OptionalInt.empty() : OptionalInt.of(offset);
    }

    /**
     * Returns the aggregate the query computes instead of its columns.
     *
     * @return the aggregate, empty for a plain select
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final Optional<Aggregate> getAggregate() {
        return Optional.ofNullable(aggregate);
    }

    /**
     * Returns the queries joined by {@code union} or {@code union all}.
     *
     * @return the unions in the order added, unmodifiable, never {@code null}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Union> getUnions() {
        return Collections.unmodifiableList(new ArrayList<>(unions));
    }

    /**
     * Returns the orders that apply to the whole union: those added after the first union.
     *
     * @return the orders in the order added, unmodifiable, never {@code null}
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final List<Order> getUnionOrders() {
        return Collections.unmodifiableList(new ArrayList<>(unionOrders));
    }

    /**
     * Returns the row limit of the whole union: one set after the first union.
     *
     * @return the limit, empty if none
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final OptionalInt getUnionLimit() {
        return unionLimit == null ? OptionalInt.empty() : OptionalInt.of(unionLimit);
    }

    /**
     * Returns the number of rows the whole union skips: an offset set after the first union.
     *
     * @return the offset, empty if none
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    public final OptionalInt getUnionOffset() {
        return unionOffset == null ? OptionalInt.empty() : OptionalInt.of(unionOffset);
    }

    /** Sets the aggregate; the execution methods set it on a clone and never on the caller's builder. */
    void setAggregate(String function, List<Object> aggregateColumns) {
        this.aggregate = new Aggregate(function, aggregateColumns);
    }

    /** Returns a clone that computes its columns instead of the aggregate. */
    Builder withoutAggregate() {
        Builder copy = self().clone();
        copy.aggregate = null;
        return copy;
    }

    /**
     * Returns a plain, empty builder on the same connection, grammar and processor for a
     * subquery; Laravel's {@code forSubQuery}. Never a join clause, even when called on one.
     */
    Builder forSubQuery() {
        return new Builder(connection, grammar, processor);
    }

    /** Runs a consumer on a fresh subquery builder and returns it. */
    Builder subQuery(Consumer<Builder> callback) {
        Objects.requireNonNull(callback, "query");
        Builder query = forSubQuery();
        callback.accept(query);
        return query;
    }

    /**
     * Takes the private copy a clause keeps of a subquery: the caller may go on changing its own
     * builder, and neither the SQL nor the bindings of this query follow. Laravel copies the
     * bindings when the subquery is attached but compiles its SQL later, so the two can drift
     * apart; copying the whole builder at attach keeps them in step.
     */
    static Builder frozen(Builder query) {
        return Objects.requireNonNull(query, "query").clone();
    }

    Builder bindUnlessExpression(Object value, BindingType type) {
        if (!(value instanceof Expression)) {
            bindings.get(type).add(value);
        }
        return self();
    }

    static List<Object> checkedPair(List<?> values) {
        Clauses.requireTwo(Objects.requireNonNull(values, "values"), "A between");
        List<Object> copy = new ArrayList<>(2);
        for (Object value : values) {
            copy.add(Clauses.checkValue(value));
        }
        return copy;
    }

    static List<Object> checkColumns(Object[] columns) {
        List<Object> checked = new ArrayList<>(columns.length);
        for (Object column : columns) {
            Objects.requireNonNull(column, "column");
            if (!(column instanceof String) && !(column instanceof Expression)) {
                throw new IllegalArgumentException("A column is a String or an Expression, not "
                        + column.getClass().getSimpleName());
            }
            checked.add(column);
        }
        return checked;
    }
}
