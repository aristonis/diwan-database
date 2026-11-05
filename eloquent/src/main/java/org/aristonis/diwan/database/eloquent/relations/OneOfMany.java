package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The immutable description of a one-of-many relation: the ordered columns with their
 * {@code min} or {@code max} aggregate, the optional constraint every subquery receives, and the
 * optional explicit join alias. Laravel's {@code ofMany} arguments.
 *
 * <p>The primary key is always the last column, the tie-breaker. Column names are plain
 * identifiers and the aggregates a fixed pair, so nothing here can carry user SQL.
 */
final class OneOfMany {

    /** The aggregate that picks the largest value. */
    static final String MAX = "max";

    /** The aggregate that picks the smallest value. */
    static final String MIN = "min";

    private static final String SUFFIX = "_of_many";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final List<String> columns;

    private final List<String> aggregates;

    private final Consumer<? super EloquentBuilder<?>> constraint;

    private final String alias;

    private OneOfMany(List<String> columns, List<String> aggregates, Consumer<? super EloquentBuilder<?>> constraint,
            String alias) {
        this.columns = Collections.unmodifiableList(columns);
        this.aggregates = Collections.unmodifiableList(aggregates);
        this.constraint = constraint;
        this.alias = alias;
    }

    /**
     * Builds the description, validating every column and aggregate and appending the primary key
     * as the last column, with the first entry's aggregate, when it is not listed.
     *
     * @param columns the columns to their aggregate, in order, never {@code null} or empty
     * @param pkName the related model's primary key column, never {@code null}
     * @param constraint the constraint for every subquery, or {@code null} for none
     * @param alias the explicit join alias, or {@code null} to derive it
     * @return the description, never {@code null}
     * @throws NullPointerException if {@code columns} or {@code pkName} is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty, a column is not a plain
     *     identifier, an aggregate is not {@code min} or {@code max} in any case, or the alias is
     *     blank
     */
    static OneOfMany of(LinkedHashMap<String, String> columns, String pkName,
            Consumer<? super EloquentBuilder<?>> constraint, String alias) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(pkName, "pkName");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("ofMany needs at least one column");
        }
        if (alias != null && alias.trim().isEmpty()) {
            throw new IllegalArgumentException("The ofMany alias must not be blank");
        }
        List<String> names = new ArrayList<>(columns.size() + 1);
        List<String> aggs = new ArrayList<>(columns.size() + 1);
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            names.add(requireIdentifier(entry.getKey()));
            aggs.add(requireAggregate(entry.getValue()));
        }
        if (!names.contains(pkName)) {
            names.add(requireIdentifier(pkName));
            aggs.add(aggs.get(0));
        }
        return new OneOfMany(names, aggs, constraint, alias);
    }

    /**
     * Builds a column map where every column has the same aggregate.
     *
     * @param aggregate the aggregate for every column, never {@code null}
     * @param columns the columns, in order, never {@code null} or empty
     * @return the map, never {@code null}
     * @throws NullPointerException if an argument or a column is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty
     */
    static LinkedHashMap<String, String> uniform(String aggregate, String... columns) {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(columns, "columns");
        if (columns.length == 0) {
            throw new IllegalArgumentException("ofMany needs at least one column");
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String column : columns) {
            map.put(Objects.requireNonNull(column, "column"), aggregate);
        }
        return map;
    }

    /**
     * Turns a constraint over the related builder into one the subqueries can receive. A
     * subquery is always a builder of the related class.
     *
     * @param constraint the user constraint, or {@code null}
     * @param <R> the related model class
     * @return the adapted constraint, or {@code null} when none was given
     */
    static <R extends Model> Consumer<EloquentBuilder<?>> adapt(Consumer<EloquentBuilder<R>> constraint) {
        if (constraint == null) {
            return null;
        }
        return sub -> {
            // Safe: every subquery is built over the related class.
            @SuppressWarnings("unchecked")
            EloquentBuilder<R> typed = (EloquentBuilder<R>) sub;
            constraint.accept(typed);
        };
    }

    /**
     * Returns the join alias: the explicit one, else the relation's name, else the related table.
     * An alias equal to one of the tables the query reads, ignoring case, gets the {@code _of_many} suffix.
     *
     * @param explicit the explicit alias, or {@code null}
     * @param relationName the relation's name, or {@code null}
     * @param relatedTable the related table, never {@code null}
     * @param reserved the other tables of the query, never {@code null}
     * @return the alias, never {@code null}
     */
    static String resolveAlias(String explicit, String relationName, String relatedTable, List<String> reserved) {
        String name = explicit != null ? explicit : relationName != null ? relationName : relatedTable;
        if (name.equalsIgnoreCase(relatedTable) || containsIgnoreCase(reserved, name)) {
            return name + SUFFIX;
        }
        return name;
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        for (String candidate : names) {
            if (name.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the same description with another explicit alias.
     *
     * @param newAlias the explicit alias, never {@code null} or blank
     * @return a new description, never {@code null}
     * @throws NullPointerException if {@code newAlias} is {@code null}
     * @throws IllegalArgumentException if {@code newAlias} is blank
     */
    OneOfMany withAlias(String newAlias) {
        Objects.requireNonNull(newAlias, "alias");
        if (newAlias.trim().isEmpty()) {
            throw new IllegalArgumentException("The ofMany alias must not be blank");
        }
        return new OneOfMany(new ArrayList<>(columns), new ArrayList<>(aggregates), constraint, newAlias);
    }

    /**
     * Returns the columns in order, the primary key last.
     *
     * @return the columns, unmodifiable, never {@code null}
     */
    List<String> columns() {
        return columns;
    }

    /**
     * Returns the aggregate of each column, in the same order as {@link #columns()}.
     *
     * @return {@code min} or {@code max} per column, unmodifiable, never {@code null}
     */
    List<String> aggregates() {
        return aggregates;
    }

    /**
     * Returns the constraint every subquery receives.
     *
     * @return the constraint, or {@code null} for none
     */
    Consumer<? super EloquentBuilder<?>> constraint() {
        return constraint;
    }

    /**
     * Returns the explicit join alias.
     *
     * @return the alias, or {@code null} when it is derived
     */
    String alias() {
        return alias;
    }

    private static String requireIdentifier(String column) {
        Objects.requireNonNull(column, "column");
        if (!IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Invalid column [" + column
                    + "] used within ofMany relation. Use a plain column name without a table or an alias");
        }
        return column;
    }

    private static String requireAggregate(String aggregate) {
        String lower = aggregate == null ? null : aggregate.toLowerCase(Locale.ROOT);
        if (!MIN.equals(lower) && !MAX.equals(lower)) {
            throw new IllegalArgumentException("Invalid aggregate [" + aggregate
                    + "] used within ofMany relation. Available aggregates: MIN, MAX");
        }
        return lower;
    }
}
