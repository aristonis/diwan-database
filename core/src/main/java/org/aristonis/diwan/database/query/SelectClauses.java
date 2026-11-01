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


/**
 * The select, from, group by and having layer of {@link Builder}.
 */
abstract class SelectClauses extends SubqueryWheres {

    SelectClauses(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- select and from ----

    /**
     * Replaces the selected columns; no columns selects {@code *}.
     *
     * @param columns the columns, each a {@code String} (may hold {@code "name as alias"} or
     *     {@code "table.*"}) or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     * @throws IllegalArgumentException if a column is neither a {@code String} nor an
     *     {@code Expression}
     */
    public final Builder select(Object... columns) {
        List<Object> checked = checkColumns(columns.length == 0 ? new Object[] {"*"} : columns);
        this.columns = new ArrayList<>(checked);
        bindings.get(BindingType.SELECT).clear();
        return self();
    }

    /**
     * Adds columns to the selection, skipping any name already selected; an {@link Expression} is
     * always added.
     *
     * @param columns the columns, as for {@link Builder#select(Object...)}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     * @throws IllegalArgumentException if a column is neither a {@code String} nor an
     *     {@code Expression}
     */
    public final Builder addSelect(Object... columns) {
        for (Object column : checkColumns(columns)) {
            // Only names are deduplicated. Laravel compares objects by identity, so two raw
            // expressions with the same text are both kept, each with its own bindings.
            if (column instanceof Expression || !this.columns.contains(column)) {
                this.columns.add(column);
            }
        }
        return self();
    }

    /**
     * Adds trusted SQL to the selection.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Builder selectRaw(String sql) {
        return selectRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings to the selection.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Builder selectRaw(String sql, List<?> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        addSelect(Expression.raw(sql));
        return addBindings(bindings, BindingType.SELECT);
    }

    /**
     * Makes the query return only distinct rows.
     *
     * @return this builder
     */
    public final Builder distinct() {
        this.distinct = true;
        return self();
    }

    /**
     * Sets the table. A subquery or raw SQL set before by {@link Builder#fromSub} or
     * {@link Builder#fromRaw} is dropped with its bindings; Laravel keeps those bindings, which then
     * no longer match the SQL.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @return this builder
     * @throws NullPointerException if {@code table} is {@code null}
     */
    public final Builder from(String table) {
        this.from = Objects.requireNonNull(table, "table");
        this.fromExpression = null;
        bindings.get(BindingType.FROM).clear();
        return self();
    }

    /**
     * Sets the table with an alias.
     *
     * @param table the table name, never {@code null}
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder from(String table, String as) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(as, "as");
        return from(table + " as " + as);
    }

    // ---- group by and having ----

    /**
     * Adds columns to the group by clause.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public final Builder groupBy(String... columns) {
        for (String column : columns) {
            groups.add(Objects.requireNonNull(column, "column"));
        }
        return self();
    }

    /**
     * Adds trusted SQL to the group by clause.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Builder groupByRaw(String sql) {
        return groupByRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings to the group by clause.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public final Builder groupByRaw(String sql, List<?> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        groups.add(Expression.raw(sql));
        return addBindings(bindings, BindingType.GROUP_BY);
    }

    /**
     * Adds a having condition comparing a column with a value using {@code =}.
     *
     * @param column the column name or alias, never {@code null}
     * @param value the value, may be {@code null} (bound as is), or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder having(String column, Object value) {
        return addHaving(column, "=", value, AND);
    }

    /**
     * Adds a having condition comparing a column with a value.
     *
     * @param column the column name or alias, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder having(String column, String operator, Object value) {
        return addHaving(column, operator, value, AND);
    }

    /**
     * Adds an {@code or} having condition comparing a column with a value using {@code =}.
     *
     * @param column the column name or alias, never {@code null}
     * @param value the value, as for {@link Builder#having(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orHaving(String column, Object value) {
        return addHaving(column, "=", value, OR);
    }

    /**
     * Adds an {@code or} having condition comparing a column with a value.
     *
     * @param column the column name or alias, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orHaving(String column, String operator, Object value) {
        return addHaving(column, operator, value, OR);
    }

    /**
     * Adds a group of having conditions in parentheses; an empty group adds nothing.
     *
     * @param group fills a fresh builder with the group's having conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public final Builder havingNested(Consumer<Builder> group) {
        return addHavingNested(group, AND);
    }

    /**
     * Adds an {@code or} group of having conditions in parentheses.
     *
     * @param group fills a fresh builder with the group's having conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public final Builder orHavingNested(Consumer<Builder> group) {
        return addHavingNested(group, OR);
    }

    /**
     * Adds a having condition that each column is null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public final Builder havingNull(String... columns) {
        for (String column : columns) {
            havings.add(new Having.Null(AND, Objects.requireNonNull(column, "column")));
        }
        return self();
    }

    /**
     * Adds an {@code or} having condition that a column is null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder orHavingNull(String column) {
        havings.add(new Having.Null(OR, Objects.requireNonNull(column, "column")));
        return self();
    }

    /**
     * Adds a having condition that each column is not null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public final Builder havingNotNull(String... columns) {
        for (String column : columns) {
            havings.add(new Having.NotNull(AND, Objects.requireNonNull(column, "column")));
        }
        return self();
    }

    /**
     * Adds an {@code or} having condition that a column is not null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder orHavingNotNull(String column) {
        havings.add(new Having.NotNull(OR, Objects.requireNonNull(column, "column")));
        return self();
    }

    /**
     * Adds a having condition that a column is between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder havingBetween(String column, List<?> values) {
        return addHavingBetween(column, values, AND, false);
    }

    /**
     * Adds a having condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link Builder#havingBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder havingNotBetween(String column, List<?> values) {
        return addHavingBetween(column, values, AND, true);
    }

    /**
     * Adds an {@code or} having condition that a column is between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link Builder#havingBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder orHavingBetween(String column, List<?> values) {
        return addHavingBetween(column, values, OR, false);
    }

    /**
     * Adds an {@code or} having condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link Builder#havingBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder orHavingNotBetween(String column, List<?> values) {
        return addHavingBetween(column, values, OR, true);
    }

    /**
     * Adds trusted SQL as a having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public final Builder havingRaw(String sql) {
        return havingRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings as a having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder havingRaw(String sql, List<?> bindings) {
        return addHavingRaw(sql, bindings, AND);
    }

    /**
     * Adds trusted SQL as an {@code or} having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public final Builder orHavingRaw(String sql) {
        return orHavingRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings as an {@code or} having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orHavingRaw(String sql, List<?> bindings) {
        return addHavingRaw(sql, bindings, OR);
    }

    Builder addHaving(String column, String operator, Object value, String conjunction) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        String checkedOperator = operator;
        Object checkedValue = value;
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        if (canonical.isPresent()) {
            checkedOperator = canonical.get();
        } else {
            checkedValue = operator;
            checkedOperator = "=";
        }
        checkedValue = Clauses.checkValue(checkedValue);
        havings.add(new Having.Basic(conjunction, column, checkedOperator, checkedValue));
        return bindUnlessExpression(checkedValue, BindingType.HAVING);
    }

    Builder addHavingNested(Consumer<Builder> group, String conjunction) {
        Objects.requireNonNull(group, "group");
        Builder query = forNestedWhere();
        group.accept(query);
        if (!query.havings.isEmpty()) {
            havings.add(new Having.Nested(conjunction, query));
            bindings.get(BindingType.HAVING).addAll(query.bindings.get(BindingType.HAVING));
        }
        return self();
    }

    Builder addHavingBetween(String column, List<?> values, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        List<Object> copy = checkedPair(values);
        havings.add(new Having.Between(conjunction, column, copy, not));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.HAVING);
        }
        return self();
    }

    Builder addHavingRaw(String sql, List<?> values, String conjunction) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(values, "bindings");
        havings.add(new Having.Raw(conjunction, sql));
        return addBindings(values, BindingType.HAVING);
    }
}
