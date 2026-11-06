package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.util.regex.Pattern;
import org.aristonis.diwan.database.Connection;


/**
 * The in, null and between where layer of {@link Builder}.
 */
abstract class RangeWheres extends BasicWheres {

    /** Canonical decimal integer text: no sign on zero, no leading zero, at most 19 digits. */
    private static final Pattern INTEGER_TEXT = Pattern.compile("0|-?[1-9][0-9]{0,18}");

    RangeWheres(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- in, null, between ----

    /**
     * Adds a condition that a column is in a list; an empty list matches no row.
     *
     * @param column the column name, never {@code null}
     * @param values the values, never {@code null}, copied; an {@link Expression} is written as
     *     given and not bound
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereIn(String column, Collection<?> values) {
        return addWhereIn(column, values, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is in a list.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link Builder#whereIn}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereIn(String column, Collection<?> values) {
        return addWhereIn(column, values, OR, false);
    }

    /**
     * Adds a condition that a column is not in a list; an empty list matches every row.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link Builder#whereIn}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder whereNotIn(String column, Collection<?> values) {
        return addWhereIn(column, values, AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not in a list.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link Builder#whereIn}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereNotIn(String column, Collection<?> values) {
        return addWhereIn(column, values, OR, true);
    }

    /**
     * Adds a condition that a column is in a list of integers written into the SQL, not bound; an
     * empty list matches no row.
     *
     * <p>A port of Laravel's {@code whereIntegerInRaw}, used by eager loading to keep a long list
     * of integer keys out of the binding limit. Laravel casts each value with {@code (int)}, which
     * turns any text into a number; here every value must be an exact integer, so nothing but
     * digits can reach the SQL. Accepted: {@code Byte}, {@code Short}, {@code Integer},
     * {@code Long}, a {@code BigInteger} or a whole {@code BigDecimal} in the {@code long} range,
     * and canonical decimal text such as {@code "42"} or {@code "-7"} (not {@code "01"},
     * {@code " 1"}, {@code "1.0"} or {@code "-0"}).
     *
     * @param column the column name, never {@code null}
     * @param values the integer values, never {@code null}, copied
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a value is not an exact integer; the message names the
     *     value's position and type, never the value
     */
    public final Builder whereIntegerInRaw(String column, Collection<?> values) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(values, "values");
        List<Object> inlined = new ArrayList<>(values.size());
        int index = 0;
        for (Object value : values) {
            inlined.add(Expression.raw(Long.toString(exactLong(value, index))));
            index++;
        }
        wheres.add(new Where.In(AND, column, inlined));
        return self();
    }

    private static long exactLong(Object value, int index) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        // bitLength, not BigInteger.longValueExact: the latter needs Android API 31.
        if (value instanceof BigInteger && ((BigInteger) value).bitLength() < Long.SIZE) {
            return ((BigInteger) value).longValue();
        }
        try {
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).longValueExact();
            }
            if (value instanceof String && INTEGER_TEXT.matcher((String) value).matches()) {
                return Long.parseLong((String) value);
            }
        } catch (ArithmeticException | NumberFormatException e) {
            // Out of the long range. The cause is dropped on purpose: its message can hold the value.
            return notAnInteger(value, index);
        }
        return notAnInteger(value, index);
    }

    private static long notAnInteger(Object value, int index) {
        String type = value == null ? "null" : value.getClass().getName();
        throw new IllegalArgumentException("value " + index + " of type " + type + " is not an integer");
    }

    /**
     * Adds a condition that each column is null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public Builder whereNull(String... columns) {
        for (String column : columns) {
            wheres.add(new Where.Null(AND, Objects.requireNonNull(column, "column")));
        }
        return self();
    }

    /**
     * Adds an {@code or} condition that a column is null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder orWhereNull(String column) {
        wheres.add(new Where.Null(OR, Objects.requireNonNull(column, "column")));
        return self();
    }

    /**
     * Adds a condition that each column is not null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public final Builder whereNotNull(String... columns) {
        for (String column : columns) {
            wheres.add(new Where.NotNull(AND, Objects.requireNonNull(column, "column")));
        }
        return self();
    }

    /**
     * Adds an {@code or} condition that a column is not null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder orWhereNotNull(String column) {
        wheres.add(new Where.NotNull(OR, Objects.requireNonNull(column, "column")));
        return self();
    }

    /**
     * Adds a condition that a column is between two values, both included.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder whereBetween(String column, List<?> values) {
        return addWhereBetween(column, values, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link Builder#whereBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder orWhereBetween(String column, List<?> values) {
        return addWhereBetween(column, values, OR, false);
    }

    /**
     * Adds a condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link Builder#whereBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder whereNotBetween(String column, List<?> values) {
        return addWhereBetween(column, values, AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link Builder#whereBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public final Builder orWhereNotBetween(String column, List<?> values) {
        return addWhereBetween(column, values, OR, true);
    }

    /**
     * Adds a condition that a column is between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public final Builder whereBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, as for {@link Builder#whereBetweenColumns}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public final Builder orWhereBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, OR, false);
    }

    /**
     * Adds a condition that a column is not between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, as for {@link Builder#whereBetweenColumns}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public final Builder whereNotBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, as for {@link Builder#whereBetweenColumns}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public final Builder orWhereNotBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, OR, true);
    }

    /**
     * Adds a condition that a value is between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns, or the value is a
     *     list, map, array or builder
     */
    public final Builder whereValueBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, AND, false);
    }

    /**
     * Adds an {@code or} condition that a value is between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, as for {@link Builder#whereValueBetween}
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereValueBetween}
     */
    public final Builder orWhereValueBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, OR, false);
    }

    /**
     * Adds a condition that a value is not between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, as for {@link Builder#whereValueBetween}
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereValueBetween}
     */
    public final Builder whereValueNotBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, AND, true);
    }

    /**
     * Adds an {@code or} condition that a value is not between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, as for {@link Builder#whereValueBetween}
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereValueBetween}
     */
    public final Builder orWhereValueNotBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, OR, true);
    }

    /**
     * Adds a condition that a moment column lies in {@code [from, to)}: at or after {@code from}
     * and before {@code to}, in parentheses.
     *
     * <p>This is the way to filter an {@code instant} column. Laravel's date wheres compare the
     * stored text of a date and do not work on a column that stores a number of microseconds.
     * The grammar converts each {@code Instant} to what its database stores, and a plain range
     * can use an index on the column.
     *
     * @param column the column name, never {@code null}
     * @param from the first moment included, never {@code null}
     * @param to the first moment excluded, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder whereInstantBetween(String column, Instant from, Instant to) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return whereNested(query -> query.where(column, ">=", from).where(column, "<", to), AND);
    }

    Builder addWhereIn(String column, Collection<?> values, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(values, "values");
        List<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) {
            copy.add(Clauses.checkValue(value));
        }
        wheres.add(not ? new Where.NotIn(conjunction, column, copy) : new Where.In(conjunction, column, copy));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.WHERE);
        }
        return self();
    }

    Builder addWhereBetween(String column, List<?> values, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        List<Object> copy = checkedPair(values);
        wheres.add(new Where.Between(conjunction, column, copy, not));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.WHERE);
        }
        return self();
    }

    Builder addBetweenColumns(String column, List<String> columns, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        Clauses.requireTwo(Objects.requireNonNull(columns, "columns"), "A between");
        wheres.add(new Where.BetweenColumns(conjunction, column, columns, not));
        return self();
    }

    Builder addValueBetween(Object value, List<String> columns, String conjunction, boolean not) {
        Clauses.requireTwo(Objects.requireNonNull(columns, "columns"), "A between");
        Object checked = Clauses.checkValue(value);
        wheres.add(new Where.ValueBetween(conjunction, checked, columns, not));
        return bindUnlessExpression(checked, BindingType.WHERE);
    }
}
