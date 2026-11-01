package org.aristonis.diwan.database.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles a {@link Builder} into SQL for one database, a hand port of Laravel's
 * {@code Illuminate\Database\Query\Grammars\Grammar} and the quoting helpers of its parent
 * {@code Illuminate\Database\Grammar}.
 *
 * <p>The base class writes Laravel's generic SQL. Each database module extends it and overrides
 * the {@code compile} and {@code where} methods its database writes differently. Laravel
 * dispatches a where clause by building a method name from a string; here
 * {@link #compileWhere(Builder, Where)} branches on the {@link Where} type, and each branch
 * calls a protected method with Laravel's name, which is the method to override.
 *
 * <p>A grammar is built for one table prefix, the prefix of the connection it serves, and adds it
 * to every table name it writes. Instances hold no other state and are safe to share between
 * threads, unless a subclass says otherwise.
 *
 * @implSpec Every value is written as {@code ?} through {@link #parameter(Object)} unless it is an
 *     {@link Expression}, and every identifier is quoted through {@link #wrap(Object)} or
 *     {@link #wrapTable(Object)}. An override must keep both rules: they are what keeps user input
 *     out of the SQL text. Methods added in later versions come with a body, so existing grammars
 *     keep compiling.
 */
public abstract class QueryGrammar extends JoinUnionGrammar {

    /** The keyword and space that open a compiled having clause, cut off when the clause is nested. */
    private static final int HAVING_LENGTH = "having ".length();

    /**
     * Creates a grammar.
     *
     * @param tablePrefix the table prefix of the connection this grammar serves, never
     *     {@code null}, empty for none
     * @throws NullPointerException if {@code tablePrefix} is {@code null}
     */
    protected QueryGrammar(String tablePrefix) {
        super(Objects.requireNonNull(tablePrefix, "tablePrefix"));
    }

    /**
     * Returns the table prefix this grammar adds to table names.
     *
     * @return the prefix, never {@code null}, empty for none
     */
    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * Returns the operators this grammar accepts on top of the builder's own list.
     *
     * @implSpec The base returns an empty set, as Laravel's base grammar does.
     *
     * @return the lower-case operators, unmodifiable, never {@code null}
     */
    public Set<String> getOperators() {
        return Collections.emptySet();
    }

    // ---- select ----

    /**
     * Compiles a select statement. Components come in Laravel's fixed order: aggregate, columns,
     * from, joins, wheres, groups, havings, orders, limit, offset; then the unions with their own
     * orders, limit and offset. An aggregate over a query with unions or havings runs on it as a
     * subquery.
     *
     * @param query the query, never {@code null}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException if a clause is not supported
     */
    public String compileSelect(Builder query) {
        if (query.getAggregate().isPresent() && (!query.getHavings().isEmpty() || !query.unions.isEmpty())) {
            return compileUnionAggregate(query);
        }
        List<Object> columns = query.getColumns().isEmpty() ? Collections.<Object>singletonList("*") : query.getColumns();
        String sql = concatenate(compileComponents(query, columns)).trim();
        return query.unions.isEmpty() ? sql : wrapUnion(sql) + " " + compileUnions(query);
    }

    /**
     * Compiles each select component that is set, in order.
     *
     * @param query the query, never {@code null}
     * @param columns the columns to select, never empty
     * @return the compiled components, some possibly empty, never {@code null}
     */
    protected List<String> compileComponents(Builder query, List<Object> columns) {
        List<String> sql = new ArrayList<>();
        query.getAggregate().ifPresent(aggregate -> sql.add(compileAggregate(query, aggregate)));
        sql.add(compileColumns(query, columns));
        if (query.getFrom() != null || query.fromExpression != null) {
            sql.add(compileFrom(query, query.getFrom() != null ? query.getFrom() : query.fromExpression));
        }
        if (!query.joins.isEmpty()) {
            sql.add(compileJoins(query, query.joins));
        }
        sql.add(compileWheres(query));
        if (!query.getGroups().isEmpty()) {
            sql.add(compileGroups(query, query.getGroups()));
        }
        if (!query.getHavings().isEmpty()) {
            sql.add(compileHavings(query));
        }
        sql.add(compileOrders(query, query.getOrders()));
        query.getLimit().ifPresent(limit -> sql.add(compileLimit(query, limit)));
        query.getOffset().ifPresent(offset -> sql.add(compileOffset(query, offset)));
        return sql;
    }

    /**
     * Compiles an aggregate select: {@code select count(*) as "aggregate"}.
     *
     * @param query the query, never {@code null}
     * @param aggregate the aggregate, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String compileAggregate(Builder query, Aggregate aggregate) {
        String column = columnize(aggregate.columns());
        if (query.isDistinct() && !"*".equals(column)) {
            column = "distinct " + column;
        }
        return "select " + aggregate.function() + "(" + column + ") as " + wrap("aggregate");
    }

    /**
     * Compiles the select list; empty when the query computes an aggregate instead.
     *
     * @param query the query, never {@code null}
     * @param columns the columns, never empty
     * @return the SQL, never {@code null}, may be empty
     */
    protected String compileColumns(Builder query, List<Object> columns) {
        if (query.getAggregate().isPresent()) {
            return "";
        }
        return (query.isDistinct() ? "select distinct " : "select ") + columnize(columns);
    }

    /**
     * Compiles the from clause.
     *
     * @param query the query, never {@code null}
     * @param table the table as given to the builder, or the {@link Expression} of a subquery or
     *     raw SQL, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String compileFrom(Builder query, Object table) {
        return "from " + wrapTable(table);
    }

    /**
     * Compiles an aggregate over a query with having clauses or unions, which has to run on a
     * subquery so the havings filter groups, or the union combines rows, before they are counted.
     *
     * @param query the query, never {@code null}, with an aggregate
     * @return the SQL, never {@code null}
     */
    protected String compileUnionAggregate(Builder query) {
        Aggregate aggregate = query.getAggregate()
                .orElseThrow(() -> new IllegalStateException("The query computes no aggregate"));
        return compileAggregate(query, aggregate) + " from (" + compileSelect(query.withoutAggregate()) + ") as "
                + wrapTable("temp_table");
    }

    /**
     * Compiles the unions after the first part: each part, then the union's orders, limit and
     * offset.
     *
     * @param query the query, never {@code null}, with at least one union
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    protected String compileUnions(Builder query) {
        StringBuilder sql = new StringBuilder();
        for (Union union : query.unions) {
            sql.append(compileUnion(union));
        }
        if (!query.unionOrders.isEmpty()) {
            sql.append(' ').append(compileOrders(query, query.unionOrders));
        }
        query.getUnionLimit().ifPresent(limit -> sql.append(' ').append(compileLimit(query, limit)));
        query.getUnionOffset().ifPresent(offset -> sql.append(' ').append(compileOffset(query, offset)));
        return sql.toString().trim();
    }

    /**
     * Compiles a query that tells whether any row matches.
     *
     * @param query the query, never {@code null}
     * @return the SQL, never {@code null}
     */
    public String compileExists(Builder query) {
        return "select exists(" + compileSelect(query) + ") as " + wrap("exists");
    }

    // ---- group, having, order, limit ----

    /**
     * Compiles the group by clause.
     *
     * @param query the query, never {@code null}
     * @param groups the entries, each a {@code String} or {@link Expression}, never empty
     * @return the SQL, never {@code null}
     */
    protected String compileGroups(Builder query, List<Object> groups) {
        return "group by " + columnize(groups);
    }

    /**
     * Compiles the having clause.
     *
     * @param query the query, never {@code null}, with at least one having condition
     * @return the SQL, never {@code null}
     */
    protected String compileHavings(Builder query) {
        List<String> sql = new ArrayList<>();
        for (Having having : query.getHavings()) {
            sql.add(having.conjunction() + " " + compileHaving(having));
        }
        return "having " + removeLeadingBoolean(String.join(" ", sql));
    }

    /**
     * Compiles one having condition, without its conjunction.
     *
     * @param having the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String compileHaving(Having having) {
        if (having instanceof Having.Raw) {
            return ((Having.Raw) having).sql();
        } else if (having instanceof Having.Between) {
            return compileHavingBetween((Having.Between) having);
        } else if (having instanceof Having.Null) {
            return wrap(((Having.Null) having).column()) + " is null";
        } else if (having instanceof Having.NotNull) {
            return wrap(((Having.NotNull) having).column()) + " is not null";
        } else if (having instanceof Having.Nested) {
            return compileNestedHavings((Having.Nested) having);
        } else if (having instanceof Having.Basic) {
            return compileBasicHaving((Having.Basic) having);
        }
        throw new IllegalStateException("Unknown having condition " + having.getClass().getName());
    }

    /**
     * Compiles a column compared with a value in a having clause.
     *
     * @param having the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String compileBasicHaving(Having.Basic having) {
        return wrap(having.column()) + " " + having.operator() + " " + parameter(having.value());
    }

    /**
     * Compiles a {@code between} having condition.
     *
     * @param having the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String compileHavingBetween(Having.Between having) {
        return wrap(having.column()) + " " + between(having.not()) + " " + parameter(having.values().get(0))
                + " and " + parameter(having.values().get(1));
    }

    /**
     * Compiles a group of having conditions in parentheses.
     *
     * @param having the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String compileNestedHavings(Having.Nested having) {
        return "(" + compileHavings(having.builder()).substring(HAVING_LENGTH) + ")";
    }

    /**
     * Compiles the order by clause.
     *
     * @param query the query, never {@code null}
     * @param orders the orders, never {@code null}
     * @return the SQL, or an empty string if there are no orders
     */
    protected String compileOrders(Builder query, List<Order> orders) {
        if (orders.isEmpty()) {
            return "";
        }
        List<String> sql = new ArrayList<>(orders.size());
        for (Order order : orders) {
            if (order instanceof Order.Raw) {
                sql.add(((Order.Raw) order).sql());
            } else if (order instanceof Order.Column) {
                Order.Column column = (Order.Column) order;
                sql.add(wrap(column.column()) + " " + column.direction());
            } else if (order instanceof Order.Sub) {
                Order.Sub sub = (Order.Sub) order;
                sql.add("(" + sub.sql() + ") " + sub.direction());
            } else {
                throw new IllegalStateException("Unknown order " + order.getClass().getName());
            }
        }
        return "order by " + String.join(", ", sql);
    }

    /**
     * Returns the SQL that orders rows randomly.
     *
     * @implSpec The base ignores the seed and returns {@code RANDOM()}. The seed reaches this
     *     method unchecked; a subclass that writes it into the SQL must first check that it is
     *     numeric.
     *
     * @param seed the seed, never {@code null}, empty for none
     * @return the SQL, never {@code null}
     */
    public String compileRandom(String seed) {
        return "RANDOM()";
    }

    /**
     * Compiles the limit clause.
     *
     * @param query the query, never {@code null}
     * @param limit the limit, not negative
     * @return the SQL, never {@code null}
     */
    protected String compileLimit(Builder query, int limit) {
        return "limit " + limit;
    }

    /**
     * Compiles the offset clause.
     *
     * @param query the query, never {@code null}
     * @param offset the offset, not negative
     * @return the SQL, never {@code null}
     */
    protected String compileOffset(Builder query, int offset) {
        return "offset " + offset;
    }

    // ---- insert, update, delete, truncate ----

    /**
     * Compiles an insert of one or more rows.
     *
     * <p>With one row, columns follow the row's key order. With several, columns are sorted by
     * name, as Laravel's builder sorts each row's keys before an insert of many rows. Every row's
     * values are written in the column order; bind them the same way, as
     * {@link #insertColumns(List)} returns it. One empty row inserts the defaults. Laravel
     * compiles any mismatch of keys into SQL whose bindings no longer line up; here it is
     * refused.
     *
     * @param query the query, never {@code null}, with a table
     * @param rows the rows, never {@code null}; each a map of column to value, where an
     *     {@link Expression} is written as given
     * @return the SQL, or empty if there are no rows and nothing should run
     * @throws NullPointerException if an argument or row is {@code null}
     * @throws IllegalArgumentException if the rows do not all have the same keys, or more than one
     *     row is empty
     */
    public Optional<String> compileInsert(Builder query, List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String table = wrapTable(query.getFrom());
        Map<String, ?> first = Objects.requireNonNull(rows.get(0), "row");
        if (first.isEmpty()) {
            if (rows.size() > 1) {
                throw new IllegalArgumentException("Only a single empty row can be inserted");
            }
            return Optional.of("insert into " + table + " default values");
        }
        List<String> keys = insertColumns(rows);
        List<String> parameters = new ArrayList<>(rows.size());
        for (Map<String, ?> row : rows) {
            if (!Objects.requireNonNull(row, "row").keySet().equals(first.keySet())) {
                throw new IllegalArgumentException("Every inserted row must have the same columns");
            }
            List<Object> values = new ArrayList<>(keys.size());
            for (String key : keys) {
                values.add(row.get(key));
            }
            parameters.add("(" + parameterize(values) + ")");
        }
        return Optional.of("insert into " + table + " (" + columnize(keys) + ") values "
                + String.join(", ", parameters));
    }

    /**
     * Returns the column order {@link #compileInsert} writes: the single row's key order, or the
     * keys sorted by name when there are several rows.
     *
     * @param rows the rows, never {@code null} or empty, none {@code null}
     * @return the column names, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code rows} or its first row is {@code null}
     */
    public List<String> insertColumns(List<? extends Map<String, ?>> rows) {
        List<String> keys = new ArrayList<>(Objects.requireNonNull(rows.get(0), "row").keySet());
        if (rows.size() > 1) {
            Collections.sort(keys);
        }
        return Collections.unmodifiableList(keys);
    }

    /**
     * Compiles an insert of one row that returns the new key.
     *
     * @implSpec The base compiles a plain insert; a database that returns the key from the
     *     statement itself, for example with {@code returning}, overrides this.
     *
     * @param query the query, never {@code null}, with a table
     * @param row the row, never {@code null}
     * @param sequence the key column, never {@code null}
     * @return the SQL, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public String compileInsertGetId(Builder query, Map<String, ?> row, String sequence) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(sequence, "sequence");
        return compileInsert(query, Collections.singletonList(row))
                .orElseThrow(() -> new IllegalStateException("An insert of one row always compiles"));
    }

    /**
     * Compiles an update of the rows the query's conditions match.
     *
     * @param query the query, never {@code null}, with a table
     * @param values the columns and their new values, never {@code null} or empty; an
     *     {@link Expression} is written as given, a {@link Builder} as {@code (select ...)}
     * @return the SQL, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code values} is empty
     * @throws UnsupportedOperationException if the query has joins and the grammar cannot update
     *     with joins, as the base grammar cannot
     */
    public String compileUpdate(Builder query, Map<String, ?> values) {
        Objects.requireNonNull(query, "query");
        if (Objects.requireNonNull(values, "values").isEmpty()) {
            throw new IllegalArgumentException("An update needs at least one column");
        }
        String table = wrapTable(query.getFrom());
        String columns = compileUpdateColumns(query, values);
        String where = compileWheres(query);
        return (query.joins.isEmpty() ? compileUpdateWithoutJoins(query, table, columns, where)
                : compileUpdateWithJoins(query, table, columns, where)).trim();
    }

    /**
     * Compiles the {@code set} list of an update.
     *
     * @param query the query, never {@code null}
     * @param values the columns and values, never empty
     * @return the SQL, never {@code null}
     */
    protected String compileUpdateColumns(Builder query, Map<String, ?> values) {
        List<String> sql = new ArrayList<>(values.size());
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            sql.add(wrap(entry.getKey()) + " = "
                    + (value instanceof Builder ? "(" + compileSelect((Builder) value) + ")" : parameter(value)));
        }
        return String.join(", ", sql);
    }

    /**
     * Assembles an update statement.
     *
     * @param query the query, never {@code null}
     * @param table the quoted table
     * @param columns the compiled {@code set} list
     * @param where the compiled where clause, may be empty
     * @return the SQL, never {@code null}
     */
    protected String compileUpdateWithoutJoins(Builder query, String table, String columns, String where) {
        return "update " + table + " set " + columns + " " + where;
    }

    /**
     * Orders the bindings of an update: the new values first, in column order, then every bucket
     * except select (from, join, where and the rest). Laravel's base grammar puts the join
     * bindings first, because its joined update writes the joins before {@code set}; the SQLite and
     * PostgreSQL rewrites put the joins in a subquery after {@code set}, so they come after the
     * values here.
     *
     * @param bindings the query's bindings by bucket, never {@code null}
     * @param values the columns and new values, never {@code null}; {@link Expression} values are
     *     skipped, a {@link Builder} value contributes its own bindings in its place
     * @return the bindings, unmodifiable, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public List<Object> prepareBindingsForUpdate(Map<BindingType, List<Object>> bindings, Map<String, ?> values) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(values, "values");
        List<Object> result = new ArrayList<>();
        for (Object value : values.values()) {
            if (value instanceof Builder) {
                result.addAll(((Builder) value).getBindings());
            } else if (!(value instanceof Expression)) {
                result.add(Clauses.castBinding(value));
            }
        }
        for (BindingType type : BindingType.values()) {
            if (type != BindingType.SELECT) {
                result.addAll(bindings.getOrDefault(type, Collections.emptyList()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Orders the bindings of an update of this query, keeping only the buckets the compiled
     * statement writes: those of {@link #prepareBindingsForUpdate(Map, Map)} when
     * {@link #writesFullSelect} is {@code true}, otherwise from, join and where only, since a plain
     * {@code update ... set ... where} writes no group, having, order or union clause.
     *
     * @param query the query, never {@code null}
     * @param values the columns and new values, never {@code null}
     * @return the bindings, unmodifiable, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public List<Object> prepareBindingsForUpdate(Builder query, Map<String, ?> values) {
        return prepareBindingsForUpdate(writtenBindings(query), values);
    }

    /**
     * Orders the bindings of a delete of this query, keeping only the buckets the compiled
     * statement writes, as for {@link #prepareBindingsForUpdate(Builder, Map)}.
     *
     * @param query the query, never {@code null}
     * @return the bindings, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public List<Object> prepareBindingsForDelete(Builder query) {
        return prepareBindingsForDelete(writtenBindings(query));
    }

    /**
     * Tells whether this grammar writes an update or delete of the query through a whole
     * {@code select} of it, which carries every clause and so every binding bucket but select.
     *
     * @implSpec The base returns {@code false}: it writes only the table, joins and wheres.
     *
     * @param query the query, never {@code null}
     * @return {@code true} if the write embeds a full select of the query
     */
    protected boolean writesFullSelect(Builder query) {
        return false;
    }

    private Map<BindingType, List<Object>> writtenBindings(Builder query) {
        Map<BindingType, List<Object>> raw = Objects.requireNonNull(query, "query").getRawBindings();
        if (writesFullSelect(query)) {
            return raw;
        }
        Map<BindingType, List<Object>> written = new EnumMap<>(BindingType.class);
        for (BindingType type : new BindingType[] {BindingType.FROM, BindingType.JOIN, BindingType.WHERE}) {
            written.put(type, raw.getOrDefault(type, Collections.emptyList()));
        }
        return written;
    }

    /**
     * Compiles a delete of the rows the query's conditions match.
     *
     * @param query the query, never {@code null}, with a table
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException if the query has joins and the grammar cannot delete
     *     with joins, as the base grammar cannot
     */
    public String compileDelete(Builder query) {
        String table = wrapTable(query.getFrom());
        String where = compileWheres(query);
        return (query.joins.isEmpty() ? compileDeleteWithoutJoins(query, table, where)
                : compileDeleteWithJoins(query, table, where)).trim();
    }

    /**
     * Assembles a delete statement.
     *
     * @param query the query, never {@code null}
     * @param table the quoted table
     * @param where the compiled where clause, may be empty
     * @return the SQL, never {@code null}
     */
    protected String compileDeleteWithoutJoins(Builder query, String table, String where) {
        return "delete from " + table + " " + where;
    }

    /**
     * Orders the bindings of a delete: every bucket except select.
     *
     * @param bindings the query's bindings by bucket, never {@code null}
     * @return the bindings, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code bindings} is {@code null}
     */
    public List<Object> prepareBindingsForDelete(Map<BindingType, List<Object>> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<Object> result = new ArrayList<>();
        for (BindingType type : BindingType.values()) {
            if (type != BindingType.SELECT) {
                result.addAll(bindings.getOrDefault(type, Collections.emptyList()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Compiles the statements that empty a table.
     *
     * @implSpec The base returns {@code truncate table "t"} with no bindings. A database without
     *     {@code truncate} returns several statements, run in order in one transaction.
     *
     * @param query the query, never {@code null}, with a table
     * @return each statement mapped to its bindings, in run order, unmodifiable, never {@code null}
     */
    public Map<String, List<Object>> compileTruncate(Builder query) {
        Map<String, List<Object>> statements = new LinkedHashMap<>();
        statements.put("truncate table " + wrapTable(query.getFrom()), Collections.emptyList());
        return Collections.unmodifiableMap(statements);
    }

    // ---- bindings and raw SQL ----

    /**
     * Converts bindings to what this grammar's database stores, before they are run or written by
     * {@link #substituteBindingsIntoRawSql}.
     *
     * @implSpec The base returns an unmodifiable copy unchanged. A database that stores a type its
     *     own way, for example an {@code Instant} as microseconds, converts it here, so every path
     *     a value takes, including raw clauses and updates, gets the same conversion.
     *
     * @param bindings the bindings, never {@code null}, may contain {@code null}
     * @return the converted bindings, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code bindings} is {@code null}
     */
    public List<Object> prepareBindings(List<?> bindings) {
        return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(bindings, "bindings")));
    }

    /**
     * Writes bindings into SQL as escaped literals, for display and logging only.
     *
     * <p>The result must never be run. Literals assume standard SQL strings, where a backslash is
     * an ordinary character, as on SQLite and on PostgreSQL with
     * {@code standard_conforming_strings} on; escaping is in any case weaker than binding.
     *
     * <p>Each {@code ?} outside a string literal and outside a quoted identifier takes the next
     * binding. Inside a {@code '...'} literal or a {@code "..."} identifier, a {@code ?} stays and
     * the other quote character is plain text; a doubled quote ({@code ''} or {@code ""}) stays
     * inside its region. Laravel tracks only string literals, so a quoted name holding {@code ?}
     * or {@code '} is substituted into or ends the scan in the wrong state. A doubled {@code ??}
     * is kept as written. A {@code ?} without a binding left stays as {@code ?}.
     * Laravel also skips a backslash-escaped quote, which only MySQL reads that way; this grammar
     * does so only when {@link #isBackslashEscape()} says its database does, since otherwise a
     * literal ending in a backslash would hide every later placeholder.
     *
     * @param sql the SQL, never {@code null}
     * @param bindings the bindings, already prepared, never {@code null}
     * @return the SQL with literals, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a string binding holds a NUL character
     * @throws UnsupportedOperationException if a binding cannot be written as a literal
     */
    public String substituteBindingsIntoRawSql(String sql, List<?> bindings) {
        Objects.requireNonNull(sql, "sql");
        List<String> literals = new ArrayList<>(Objects.requireNonNull(bindings, "bindings").size());
        for (Object binding : bindings) {
            literals.add(escape(binding));
        }
        StringBuilder query = new StringBuilder(sql.length());
        int bindingIndex = 0;
        boolean isStringLiteral = false;
        boolean isIdentifier = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (isIdentifier || (current == '"' && !isStringLiteral)) {
                // A quoted identifier is copied as is; only an undoubled closing quote ends it.
                query.append(current);
                if (isIdentifier && current == '"' && next == '"') {
                    query.append(next);
                    i++;
                } else if (current == '"') {
                    isIdentifier = !isIdentifier;
                }
                continue;
            }
            boolean isEscapedPair = (current == '\'' && next == '\'') || (current == '?' && next == '?')
                    || (current == '\\' && next == '\'' && isBackslashEscape());
            if (isEscapedPair) {
                query.append(current).append(next);
                i++;
            } else if (current == '\'') {
                query.append(current);
                isStringLiteral = !isStringLiteral;
            } else if (current == '?' && !isStringLiteral) {
                query.append(bindingIndex < literals.size() ? literals.get(bindingIndex++) : "?");
            } else {
                query.append(current);
            }
        }
        return query.toString();
    }

    /**
     * Returns whether this grammar's database reads {@code \'} inside a string literal as an
     * escaped quote.
     *
     * @implSpec The base returns {@code false}: standard SQL, SQLite and PostgreSQL with standard
     *     strings treat a backslash as an ordinary character.
     *
     * @return {@code true} if a backslash escapes a quote
     */
    protected boolean isBackslashEscape() {
        return false;
    }

    /**
     * Writes one value as an SQL literal.
     *
     * @param value the value, may be {@code null}
     * @return the literal, never {@code null}
     * @throws IllegalArgumentException if a string holds a NUL character
     * @throws UnsupportedOperationException if the value is binary and the grammar cannot write it
     */
    protected String escape(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[]) {
            return escapeBinary((byte[]) value);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof Double || value instanceof Float) {
            return value.toString();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Boolean) {
            return escapeBool((Boolean) value);
        }
        String text = value.toString();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Strings with NUL characters cannot be escaped");
        }
        return escapeString(text);
    }

    /**
     * Writes text as a quoted string literal.
     *
     * @implSpec The base puts the text in single quotes and doubles any single quote inside it.
     *
     * @param value the text, never {@code null}, without NUL characters
     * @return the literal, never {@code null}
     */
    protected String escapeString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Writes a boolean as a literal.
     *
     * @implSpec The base writes {@code 1} or {@code 0}, as Laravel does.
     *
     * @param value the value
     * @return the literal, never {@code null}
     */
    protected String escapeBool(boolean value) {
        return value ? "1" : "0";
    }

    /**
     * Writes bytes as a literal.
     *
     * @implSpec The base refuses, as Laravel's does; binary literal syntax differs per database.
     *
     * @param value the bytes, never {@code null}
     * @return the literal, never {@code null}
     * @throws UnsupportedOperationException always, in the base
     */
    protected String escapeBinary(byte[] value) {
        throw unsupported("Escaping binary values");
    }
}
