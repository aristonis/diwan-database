package org.aristonis.diwan.database.query;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** The value and operator rules the builder applies before it records a clause. */
final class Clauses {

    /** Laravel's builder operators; a grammar may add its own through {@link QueryGrammar#getOperators()}. */
    static final Set<String> OPERATORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "=", "<", ">", "<=", ">=", "<>", "!=", "<=>",
            "like", "like binary", "not like", "ilike",
            "&", "|", "^", "<<", ">>", "&~", "is", "is not",
            "rlike", "not rlike", "regexp", "not regexp",
            "~", "~*", "!~", "!~*", "similar to",
            "not similar to", "not ilike", "~~*", "!~~*")));

    /** Operators that may be paired with a null value: the rest would compare with null and never match. */
    private static final Set<String> NULL_OPERATORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("=", "<=>", "<>", "!=")));

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private Clauses() {
    }

    /**
     * Returns the allowlist's spelling of an operator, or empty if neither the builder nor the
     * grammar knows it. The caller's text is never returned: lower-casing maps characters such as
     * the Kelvin sign to ASCII, so the text that passed the check is not the text to write.
     */
    static Optional<String> canonicalOperator(String operator, QueryGrammar grammar) {
        String lower = operator.toLowerCase(Locale.ROOT);
        for (String known : OPERATORS) {
            if (known.equals(lower)) {
                return Optional.of(known);
            }
        }
        for (String known : grammar.getOperators()) {
            if (known.equals(lower)) {
                return Optional.of(known);
            }
        }
        return Optional.empty();
    }

    /** Rejects a null value with an operator that cannot compare with null, as Laravel does. */
    static void checkNullOperator(String operator, Object value) {
        if (value == null && OPERATORS.contains(operator) && !NULL_OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("Illegal operator and value combination: " + operator + " null");
        }
    }

    /**
     * Rejects values that would silently mean something else. Laravel takes the first element of an
     * array value and runs a builder or closure value as a subquery; neither is supported here.
     */
    static Object checkValue(Object value) {
        if (value instanceof Collection || value instanceof Map || value instanceof Object[]) {
            throw new IllegalArgumentException("A list is not a single value; use whereIn for a list");
        }
        if (value instanceof Builder || value instanceof Consumer) {
            throw new IllegalArgumentException("Subqueries are not supported");
        }
        return castBinding(value);
    }

    /** Laravel binds an enum by its value; a Java enum is bound by its name. */
    static Object castBinding(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : value;
    }

    /** Formats a date value the way Laravel formats it for each date-based where. */
    static Object datePart(Where.DatePart part, Object value) {
        if (value == null || value instanceof Expression) {
            return value;
        }
        TemporalAccessor moment = value instanceof TemporalAccessor ? (TemporalAccessor) value : null;
        switch (part) {
            case DATE:
                return moment != null && moment.isSupported(ChronoField.EPOCH_DAY)
                        ? LocalDate.from(moment).toString() : value;
            case TIME:
                return moment != null && moment.isSupported(ChronoField.NANO_OF_DAY)
                        ? TIME.format(LocalTime.from(moment)) : value;
            case DAY:
                return twoDigits(moment, ChronoField.DAY_OF_MONTH, value);
            case MONTH:
                return twoDigits(moment, ChronoField.MONTH_OF_YEAR, value);
            default:
                return moment != null && moment.isSupported(ChronoField.YEAR)
                        ? String.valueOf(moment.get(ChronoField.YEAR)) : value;
        }
    }

    private static String twoDigits(TemporalAccessor moment, ChronoField field, Object value) {
        long number;
        if (moment != null && moment.isSupported(field)) {
            number = moment.get(field);
        } else if (value instanceof Number) {
            number = ((Number) value).longValue();
        } else {
            try {
                number = Long.parseLong(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("A day or month must be a whole number", e);
            }
        }
        return String.format(Locale.ROOT, "%02d", number);
    }

    /** Copies a list into an unmodifiable list that may hold {@code null}. */
    static <T> List<T> copy(List<? extends T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(Objects.requireNonNull(values, "values")));
    }

    static <T> List<T> requireTwo(List<T> values, String what) {
        if (values.size() != 2) {
            throw new IllegalArgumentException(what + " needs exactly two values, got " + values.size());
        }
        return values;
    }
}
