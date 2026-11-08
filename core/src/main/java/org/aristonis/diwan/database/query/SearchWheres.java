package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;

/**
 * The escaped text-search where layer of {@link Builder}: contains, starts with and ends with.
 * The text is matched literally; {@code %}, {@code _} and the escape character in it are not
 * wildcards. {@link Builder#whereLike(String, Object)} stays the raw-pattern form.
 */
abstract class SearchWheres extends BasicWheres {

    SearchWheres(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    /**
     * Adds a condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereContains(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, false, AND, false);
    }

    /**
     * Adds a condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereContains(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, caseSensitive, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereContains(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, false, OR, false);
    }

    /**
     * Adds an {@code or} condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereContains(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, caseSensitive, OR, false);
    }

    /**
     * Adds a negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereNotContains(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, false, AND, true);
    }

    /**
     * Adds a negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereNotContains(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, caseSensitive, AND, true);
    }

    /**
     * Adds an {@code or} negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereNotContains(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, false, OR, true);
    }

    /**
     * Adds an {@code or} negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereNotContains(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, caseSensitive, OR, true);
    }

    /**
     * Adds a condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereStartsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, false, AND, false);
    }

    /**
     * Adds a condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereStartsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, caseSensitive, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereStartsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, false, OR, false);
    }

    /**
     * Adds an {@code or} condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereStartsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, caseSensitive, OR, false);
    }

    /**
     * Adds a negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereNotStartsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, false, AND, true);
    }

    /**
     * Adds a negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereNotStartsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, caseSensitive, AND, true);
    }

    /**
     * Adds an {@code or} negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereNotStartsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, false, OR, true);
    }

    /**
     * Adds an {@code or} negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereNotStartsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, caseSensitive, OR, true);
    }

    /**
     * Adds a condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereEndsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, false, AND, false);
    }

    /**
     * Adds a condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereEndsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, caseSensitive, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereEndsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, false, OR, false);
    }

    /**
     * Adds an {@code or} condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereEndsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, caseSensitive, OR, false);
    }

    /**
     * Adds a negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereNotEndsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, false, AND, true);
    }

    /**
     * Adds a negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder whereNotEndsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, caseSensitive, AND, true);
    }

    /**
     * Adds an {@code or} negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-insensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereNotEndsWith(String column, String value) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, false, OR, true);
    }

    /**
     * Adds an {@code or} negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final Builder orWhereNotEndsWith(String column, String value, boolean caseSensitive) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, caseSensitive, OR, true);
    }

    private Builder addSearch(String column, String value, SearchWhere.Anchor anchor, boolean caseSensitive,
            String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        if (value == null) {
            throw new IllegalArgumentException("search value must not be null");
        }
        String pattern = grammar.searchPattern(value, anchor, caseSensitive);
        wheres.add(new SearchWhere(conjunction, column, anchor, caseSensitive, not));
        return addBindings(Collections.singletonList(pattern), BindingType.WHERE);
    }
}
