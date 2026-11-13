package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.SearchOptions;

/**
 * The escaped text-search forwarders of {@link EloquentBuilder}: contains, starts with and ends
 * with, each as the matching method of {@link Builder}, including the trailing {@link SearchOptions}
 * form.
 *
 * @param <T> the model class
 */
abstract class EloquentSearches<T extends Model> extends EloquentWheres<T> {

    EloquentSearches(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
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
    public final EloquentBuilder<T> whereContains(String column, String value) {
        query.whereContains(column, value);
        return self();
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
    public final EloquentBuilder<T> whereContains(String column, String value, SearchOptions options) {
        query.whereContains(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#whereContains(String, String, SearchOptions)}, for example
     *     {@code whereContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> whereContains(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> orWhereContains(String column, String value) {
        query.orWhereContains(column, value);
        return self();
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
    public final EloquentBuilder<T> orWhereContains(String column, String value, SearchOptions options) {
        query.orWhereContains(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#orWhereContains(String, String, SearchOptions)}, for example
     *     {@code orWhereContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> orWhereContains(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> whereNotContains(String column, String value) {
        query.whereNotContains(column, value);
        return self();
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
    public final EloquentBuilder<T> whereNotContains(String column, String value, SearchOptions options) {
        query.whereNotContains(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#whereNotContains(String, String, SearchOptions)}, for example
     *     {@code whereNotContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> whereNotContains(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> orWhereNotContains(String column, String value) {
        query.orWhereNotContains(column, value);
        return self();
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
    public final EloquentBuilder<T> orWhereNotContains(String column, String value, SearchOptions options) {
        query.orWhereNotContains(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#orWhereNotContains(String, String, SearchOptions)}, for example
     *     {@code orWhereNotContains(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> orWhereNotContains(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> whereStartsWith(String column, String value) {
        query.whereStartsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> whereStartsWith(String column, String value, SearchOptions options) {
        query.whereStartsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#whereStartsWith(String, String, SearchOptions)}, for example
     *     {@code whereStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> whereStartsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> orWhereStartsWith(String column, String value) {
        query.orWhereStartsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> orWhereStartsWith(String column, String value, SearchOptions options) {
        query.orWhereStartsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#orWhereStartsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> orWhereStartsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> whereNotStartsWith(String column, String value) {
        query.whereNotStartsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> whereNotStartsWith(String column, String value, SearchOptions options) {
        query.whereNotStartsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#whereNotStartsWith(String, String, SearchOptions)}, for example
     *     {@code whereNotStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> whereNotStartsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> orWhereNotStartsWith(String column, String value) {
        query.orWhereNotStartsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> orWhereNotStartsWith(String column, String value, SearchOptions options) {
        query.orWhereNotStartsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#orWhereNotStartsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereNotStartsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> orWhereNotStartsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> whereEndsWith(String column, String value) {
        query.whereEndsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> whereEndsWith(String column, String value, SearchOptions options) {
        query.whereEndsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#whereEndsWith(String, String, SearchOptions)}, for example
     *     {@code whereEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> whereEndsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> orWhereEndsWith(String column, String value) {
        query.orWhereEndsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> orWhereEndsWith(String column, String value, SearchOptions options) {
        query.orWhereEndsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#orWhereEndsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> orWhereEndsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> whereNotEndsWith(String column, String value) {
        query.whereNotEndsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> whereNotEndsWith(String column, String value, SearchOptions options) {
        query.whereNotEndsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#whereNotEndsWith(String, String, SearchOptions)}, for example
     *     {@code whereNotEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> whereNotEndsWith(String column, String value, boolean caseSensitive) {
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
    public final EloquentBuilder<T> orWhereNotEndsWith(String column, String value) {
        query.orWhereNotEndsWith(column, value);
        return self();
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
    public final EloquentBuilder<T> orWhereNotEndsWith(String column, String value, SearchOptions options) {
        query.orWhereNotEndsWith(column, value, options);
        return self();
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
     *     {@link EloquentBuilder#orWhereNotEndsWith(String, String, SearchOptions)}, for example
     *     {@code orWhereNotEndsWith(column, value, SearchOptions.caseSensitive())}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public final EloquentBuilder<T> orWhereNotEndsWith(String column, String value, boolean caseSensitive) {
        return orWhereNotEndsWith(column, value, SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));
    }
}
