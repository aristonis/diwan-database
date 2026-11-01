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
 * The date-based and all/any/none where layer of {@link Builder}.
 */
abstract class DateWheres extends RangeWheres {

    DateWheres(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- date-based ----

    /**
     * Adds a condition on the date part of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the date; a {@code LocalDate} or other date is written as {@code yyyy-MM-dd}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder whereDate(String column, Object value) {
        return addDateBased(Where.DatePart.DATE, column, "=", value, AND);
    }

    /**
     * Adds a condition on the date part of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the date, as for {@link Builder#whereDate(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereDate(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DATE, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the date part of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the date, as for {@link Builder#whereDate(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereDate(String column, Object value) {
        return addDateBased(Where.DatePart.DATE, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the date part of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the date, as for {@link Builder#whereDate(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereDate(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DATE, column, operator, value, OR);
    }

    /**
     * Adds a condition on the time part of a text time column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the time; a {@code LocalTime} or other time is written as {@code HH:mm:ss}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder whereTime(String column, Object value) {
        return addDateBased(Where.DatePart.TIME, column, "=", value, AND);
    }

    /**
     * Adds a condition on the time part of a text time column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the time, as for {@link Builder#whereTime(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereTime(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.TIME, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the time part of a text time column.
     *
     * @param column the column name, never {@code null}
     * @param value the time, as for {@link Builder#whereTime(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereTime(String column, Object value) {
        return addDateBased(Where.DatePart.TIME, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the time part of a text time column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the time, as for {@link Builder#whereTime(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereTime(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.TIME, column, operator, value, OR);
    }

    /**
     * Adds a condition on the day of the month of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the day; a number or date is written as two digits
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is not a whole number or date
     */
    public final Builder whereDay(String column, Object value) {
        return addDateBased(Where.DatePart.DAY, column, "=", value, AND);
    }

    /**
     * Adds a condition on the day of the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the day, as for {@link Builder#whereDay(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereDay(String, Object)}
     */
    public final Builder whereDay(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DAY, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the day of the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the day, as for {@link Builder#whereDay(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereDay(String, Object)}
     */
    public final Builder orWhereDay(String column, Object value) {
        return addDateBased(Where.DatePart.DAY, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the day of the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the day, as for {@link Builder#whereDay(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereDay(String, Object)}
     */
    public final Builder orWhereDay(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DAY, column, operator, value, OR);
    }

    /**
     * Adds a condition on the month of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the month; a number or date is written as two digits
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is not a whole number or date
     */
    public final Builder whereMonth(String column, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, "=", value, AND);
    }

    /**
     * Adds a condition on the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the month, as for {@link Builder#whereMonth(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereMonth(String, Object)}
     */
    public final Builder whereMonth(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the month, as for {@link Builder#whereMonth(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereMonth(String, Object)}
     */
    public final Builder orWhereMonth(String column, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the month, as for {@link Builder#whereMonth(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#whereMonth(String, Object)}
     */
    public final Builder orWhereMonth(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, operator, value, OR);
    }

    /**
     * Adds a condition on the year of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the year; a date is written as its year
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder whereYear(String column, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, "=", value, AND);
    }

    /**
     * Adds a condition on the year of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the year, as for {@link Builder#whereYear(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereYear(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the year of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the year, as for {@link Builder#whereYear(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereYear(String column, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the year of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the year, as for {@link Builder#whereYear(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereYear(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, operator, value, OR);
    }

    // ---- all, any, none ----

    /**
     * Adds a group requiring every column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder whereAll(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, AND, AND);
    }

    /**
     * Adds a group requiring every column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereAll(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, AND, AND);
    }

    /**
     * Adds an {@code or} group requiring every column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereAll(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, OR, AND);
    }

    /**
     * Adds an {@code or} group requiring every column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereAll(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, OR, AND);
    }

    /**
     * Adds a group requiring at least one column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder whereAny(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, AND, OR);
    }

    /**
     * Adds a group requiring at least one column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereAny(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, AND, OR);
    }

    /**
     * Adds an {@code or} group requiring at least one column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereAny(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, OR, OR);
    }

    /**
     * Adds an {@code or} group requiring at least one column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereAny(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, OR, OR);
    }

    /**
     * Adds a group requiring no column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder whereNone(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, AND + NOT, OR);
    }

    /**
     * Adds a group requiring no column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereNone(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, AND + NOT, OR);
    }

    /**
     * Adds an {@code or} group requiring no column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereNone(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, OR + NOT, OR);
    }

    /**
     * Adds an {@code or} group requiring no column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereNone(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, OR + NOT, OR);
    }

    Builder addDateBased(Where.DatePart part, String column, String operator, Object value,
            String conjunction) {
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
        Object formatted = Clauses.datePart(part, Clauses.checkValue(checkedValue));
        wheres.add(new Where.DateBased(conjunction, part, column, checkedOperator, formatted));
        return bindUnlessExpression(formatted, BindingType.WHERE);
    }

    Builder addMultiColumn(List<String> columns, String operator, Object value, String conjunction,
            String inner) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        return whereNested(query -> {
            for (String column : columns) {
                query.addWhere(column, operator, value, inner);
            }
        }, conjunction);
    }
}
