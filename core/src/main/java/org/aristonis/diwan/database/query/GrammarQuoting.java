package org.aristonis.diwan.database.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The quoting layer of {@link QueryGrammar}: identifiers, table names, parameters and the table
 * prefix.
 *
 * <p>Package-private: its methods are documented as {@link QueryGrammar}'s own, and database
 * grammars override them there. The grammar is split into layers only to keep each file small.
 */
abstract class GrammarQuoting {

    private static final Pattern LEADING_BOOLEAN = Pattern.compile("and |or ", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS = Pattern.compile("\\s+as\\s+", Pattern.CASE_INSENSITIVE);
    private static final char QUOTE = '"';

    final String tablePrefix;

    /** Creates the quoting layer for a prefix already checked for {@code null}. */
    GrammarQuoting(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    // ---- quoting ----

    /**
     * Quotes a column reference: splits {@code "a as b"} into a quoted name and alias, quotes each
     * dotted segment (the first, a table, gets the prefix), and leaves {@code *} alone.
     *
     * @param value a {@code String}, or an {@link Expression} written as given; never {@code null}
     * @return the SQL, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the name is a JSON path ({@code ->})
     */
    public String wrap(Object value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof Expression) {
            return ((Expression) value).sql();
        }
        String name = value.toString();
        if (name.toLowerCase(Locale.ROOT).contains(" as ")) {
            return wrapAliasedValue(name);
        }
        if (isJsonSelector(name)) {
            return wrapJsonSelector(name);
        }
        return wrapSegments(name.split("\\.", -1));
    }

    /**
     * Quotes a table name, adding the prefix: {@code "users as u"} becomes
     * {@code "app_users" as "app_u"}, and in {@code "schema.users"} only the table is prefixed.
     *
     * @param table a {@code String}, or an {@link Expression} written as given; never {@code null}
     * @return the SQL, never {@code null}
     * @throws NullPointerException if {@code table} is {@code null}
     */
    public String wrapTable(Object table) {
        return wrapTable(table, tablePrefix);
    }

    /**
     * Quotes a table name with a given prefix.
     *
     * @param table a {@code String}, or an {@link Expression} written as given; never {@code null}
     * @param prefix the prefix, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String wrapTable(Object table, String prefix) {
        Objects.requireNonNull(table, "table");
        if (table instanceof Expression) {
            return ((Expression) table).sql();
        }
        String name = table.toString();
        if (name.toLowerCase(Locale.ROOT).contains(" as ")) {
            return wrapAliasedTable(name, prefix);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String prefixed = name.substring(0, dot) + "." + prefix + name.substring(dot + 1);
            List<String> segments = new ArrayList<>();
            for (String segment : prefixed.split("\\.", -1)) {
                segments.add(wrapValue(segment));
            }
            return String.join(".", segments);
        }
        return wrapValue(prefix + name);
    }

    /**
     * Quotes {@code "name as alias"}.
     *
     * @param value the aliased name, never {@code null}
     * @return the SQL, never {@code null}
     * @throws IllegalArgumentException if the name or alias is empty, or the alias holds another
     *     {@code as}
     */
    protected String wrapAliasedValue(String value) {
        String[] segments = splitAlias(value);
        return wrap(segments[0]) + " as " + wrapValue(segments[1]);
    }

    /**
     * Quotes {@code "table as alias"}, prefixing both.
     *
     * @param value the aliased table, never {@code null}
     * @param prefix the prefix, never {@code null}
     * @return the SQL, never {@code null}
     * @throws IllegalArgumentException if the table or alias is empty, or the alias holds another
     *     {@code as}
     */
    protected String wrapAliasedTable(String value, String prefix) {
        String[] segments = splitAlias(value);
        return wrapTable(segments[0], prefix) + " as " + wrapValue(prefix + segments[1]);
    }

    /**
     * Splits {@code "name as alias"} in two. Laravel splits on every {@code as} and keeps the first
     * two parts, silently dropping the rest, and fails on an empty alias; both are refused here.
     */
    private static String[] splitAlias(String value) {
        String[] segments = ALIAS.split(value, 2);
        if (segments.length < 2 || segments[0].trim().isEmpty() || segments[1].trim().isEmpty()
                || ALIAS.matcher(" " + segments[1] + " ").find()) {
            throw new IllegalArgumentException("An alias is written \"name as alias\" with one non-empty alias");
        }
        return segments;
    }

    /**
     * Quotes the dotted segments of a column reference; with more than one, the first is a table.
     *
     * @param segments the segments, never empty
     * @return the SQL, never {@code null}
     */
    protected String wrapSegments(String[] segments) {
        List<String> wrapped = new ArrayList<>(segments.length);
        for (int i = 0; i < segments.length; i++) {
            wrapped.add(i == 0 && segments.length > 1 ? wrapTable(segments[i]) : wrapValue(segments[i]));
        }
        return String.join(".", wrapped);
    }

    /**
     * Quotes one identifier segment; {@code *} is left as is.
     *
     * @param value the segment, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String wrapValue(String value) {
        return "*".equals(value) ? value : Identifiers.quote(value, QUOTE);
    }

    /**
     * Returns whether a name is a JSON path such as {@code data->name}.
     *
     * @param value the name, never {@code null}
     * @return {@code true} if it holds {@code ->}
     */
    protected boolean isJsonSelector(String value) {
        return value.contains("->");
    }

    /**
     * Quotes a JSON path.
     *
     * @implSpec The base refuses, as Laravel's does.
     *
     * @param value the path, never {@code null}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException always, in the base
     */
    protected String wrapJsonSelector(String value) {
        throw unsupported("JSON paths");
    }

    /**
     * Quotes and joins column references.
     *
     * @param columns the columns, each a {@code String} or {@link Expression}, never {@code null}
     * @return the columns separated by {@code ", "}, never {@code null}
     */
    public String columnize(Collection<?> columns) {
        List<String> wrapped = new ArrayList<>(columns.size());
        for (Object column : columns) {
            wrapped.add(wrap(column));
        }
        return String.join(", ", wrapped);
    }

    /**
     * Writes a placeholder for each value.
     *
     * @param values the values, never {@code null}
     * @return the placeholders separated by {@code ", "}, never {@code null}
     */
    public String parameterize(Collection<?> values) {
        List<String> parameters = new ArrayList<>(values.size());
        for (Object value : values) {
            parameters.add(parameter(value));
        }
        return String.join(", ", parameters);
    }

    /**
     * Writes the placeholder for one value: an {@link Expression} as given, anything else as
     * {@code ?}.
     *
     * @param value the value, may be {@code null}
     * @return the SQL, never {@code null}
     */
    public String parameter(Object value) {
        return value instanceof Expression ? ((Expression) value).sql() : "?";
    }

    /**
     * Joins the non-empty segments with single spaces.
     *
     * @param segments the segments, never {@code null}, none {@code null}
     * @return the SQL, never {@code null}
     */
    protected String concatenate(List<String> segments) {
        List<String> parts = new ArrayList<>(segments.size());
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                parts.add(segment);
            }
        }
        return String.join(" ", parts);
    }

    /**
     * Removes the conjunction in front of the first condition.
     *
     * @param value the joined conditions, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String removeLeadingBoolean(String value) {
        return LEADING_BOOLEAN.matcher(value).replaceFirst("");
    }

    /**
     * Builds the error for something this grammar does not support.
     *
     * @param what what is not supported, starting with a capital letter
     * @return the exception, never {@code null}
     */
    protected UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(what + " is not supported by " + getClass().getSimpleName());
    }
}
