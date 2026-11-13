package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;

/**
 * The escaped text-search where layer of {@link Builder}: contains, starts with and ends with.
 * The text is matched literally; {@code %}, {@code _} and the escape character in it are not
 * wildcards. {@link Builder#whereLike(String, Object)} stays the raw-pattern form. Each method also
 * takes a trailing {@link SearchOptions} for the search settings.
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
        return whereContains(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds a condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder whereContains(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, options, AND, false);
    }

    /**
     * Adds a condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#whereContains(String, String, SearchOptions)}, for example
     *     {@code whereContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder whereContains(String column, String value, boolean caseSensitive) {
        return whereContains(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return orWhereContains(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds an {@code or} condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder orWhereContains(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, options, OR, false);
    }

    /**
     * Adds an {@code or} condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#orWhereContains(String, String, SearchOptions)}, for example
     *     {@code orWhereContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder orWhereContains(String column, String value, boolean caseSensitive) {
        return orWhereContains(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return whereNotContains(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds a negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder whereNotContains(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, options, AND, true);
    }

    /**
     * Adds a negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#whereNotContains(String, String, SearchOptions)}, for example
     *     {@code whereNotContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder whereNotContains(String column, String value, boolean caseSensitive) {
        return whereNotContains(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return orWhereNotContains(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds an {@code or} negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder orWhereNotContains(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.CONTAINS, options, OR, true);
    }

    /**
     * Adds an {@code or} negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#orWhereNotContains(String, String, SearchOptions)}, for example
     *     {@code orWhereNotContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder orWhereNotContains(String column, String value, boolean caseSensitive) {
        return orWhereNotContains(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return whereStartsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds a condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder whereStartsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, options, AND, false);
    }

    /**
     * Adds a condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#whereStartsWith(String, String, SearchOptions)}, for example
     *     {@code whereStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder whereStartsWith(String column, String value, boolean caseSensitive) {
        return whereStartsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return orWhereStartsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds an {@code or} condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder orWhereStartsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, options, OR, false);
    }

    /**
     * Adds an {@code or} condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#orWhereStartsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder orWhereStartsWith(String column, String value, boolean caseSensitive) {
        return orWhereStartsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return whereNotStartsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds a negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder whereNotStartsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, options, AND, true);
    }

    /**
     * Adds a negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#whereNotStartsWith(String, String, SearchOptions)}, for example
     *     {@code whereNotStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder whereNotStartsWith(String column, String value, boolean caseSensitive) {
        return whereNotStartsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return orWhereNotStartsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds an {@code or} negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder orWhereNotStartsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.STARTS_WITH, options, OR, true);
    }

    /**
     * Adds an {@code or} negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#orWhereNotStartsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereNotStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder orWhereNotStartsWith(String column, String value, boolean caseSensitive) {
        return orWhereNotStartsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return whereEndsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds a condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder whereEndsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, options, AND, false);
    }

    /**
     * Adds a condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#whereEndsWith(String, String, SearchOptions)}, for example
     *     {@code whereEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder whereEndsWith(String column, String value, boolean caseSensitive) {
        return whereEndsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return orWhereEndsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds an {@code or} condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder orWhereEndsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, options, OR, false);
    }

    /**
     * Adds an {@code or} condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#orWhereEndsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder orWhereEndsWith(String column, String value, boolean caseSensitive) {
        return orWhereEndsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return whereNotEndsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds a negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder whereNotEndsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, options, AND, true);
    }

    /**
     * Adds a negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#whereNotEndsWith(String, String, SearchOptions)}, for example
     *     {@code whereNotEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder whereNotEndsWith(String column, String value, boolean caseSensitive) {
        return whereNotEndsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
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
        return orWhereNotEndsWith(column, value, SearchOptions.DEFAULT);
    }

    /**
     * Adds an {@code or} negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; the match follows the options.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param options the search settings, never {@code null}; the base grammar refuses case-sensitive ones
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code options} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot honour a setting in {@code options}
     */
    public final Builder orWhereNotEndsWith(String column, String value, SearchOptions options) {
        return addSearch(column, value, SearchWhere.Anchor.ENDS_WITH, options, OR, true);
    }

    /**
     * Adds an {@code or} negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive when the flag is set.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link Builder#orWhereNotEndsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereNotEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final Builder orWhereNotEndsWith(String column, String value, boolean caseSensitive) {
        return orWhereNotEndsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
    }

    private Builder addSearch(String column, String value, SearchWhere.Anchor anchor, SearchOptions options,
            String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(options, "options");
        if (value == null) {
            throw new IllegalArgumentException("search value must not be null");
        }
        String pattern = grammar.compileSearchPattern(value, anchor, options);
        wheres.add(new SearchWhere(conjunction, column, anchor, options, not));
        return addBindings(Collections.singletonList(pattern), BindingType.WHERE);
    }
}
