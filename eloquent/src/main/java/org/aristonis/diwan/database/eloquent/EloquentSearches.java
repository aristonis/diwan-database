package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.query.Builder;

/**
 * The escaped text-search forwarders of {@link EloquentBuilder}: contains, starts with and ends
 * with, each as the matching method of {@link Builder}.
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
     * Adds a condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> whereContains(String column, String value, boolean caseSensitive) {
        query.whereContains(column, value, caseSensitive);
        return self();
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
     * Adds an {@code or} condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> orWhereContains(String column, String value, boolean caseSensitive) {
        query.orWhereContains(column, value, caseSensitive);
        return self();
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
     * Adds a negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> whereNotContains(String column, String value, boolean caseSensitive) {
        query.whereNotContains(column, value, caseSensitive);
        return self();
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
     * Adds an {@code or} negated condition that a column contains the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> orWhereNotContains(String column, String value, boolean caseSensitive) {
        query.orWhereNotContains(column, value, caseSensitive);
        return self();
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
     * Adds a condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> whereStartsWith(String column, String value, boolean caseSensitive) {
        query.whereStartsWith(column, value, caseSensitive);
        return self();
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
     * Adds an {@code or} condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> orWhereStartsWith(String column, String value, boolean caseSensitive) {
        query.orWhereStartsWith(column, value, caseSensitive);
        return self();
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
     * Adds a negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> whereNotStartsWith(String column, String value, boolean caseSensitive) {
        query.whereNotStartsWith(column, value, caseSensitive);
        return self();
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
     * Adds an {@code or} negated condition that a column starts with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> orWhereNotStartsWith(String column, String value, boolean caseSensitive) {
        query.orWhereNotStartsWith(column, value, caseSensitive);
        return self();
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
     * Adds a condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> whereEndsWith(String column, String value, boolean caseSensitive) {
        query.whereEndsWith(column, value, caseSensitive);
        return self();
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
     * Adds an {@code or} condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> orWhereEndsWith(String column, String value, boolean caseSensitive) {
        query.orWhereEndsWith(column, value, caseSensitive);
        return self();
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
     * Adds a negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> whereNotEndsWith(String column, String value, boolean caseSensitive) {
        query.whereNotEndsWith(column, value, caseSensitive);
        return self();
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
     * Adds an {@code or} negated condition that a column ends with the text, with {@code %}, {@code _} and the escape character taken literally; case-sensitive.
     *
     * @param column the column name, never {@code null}
     * @param value the text to find, never {@code null}
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public final EloquentBuilder<T> orWhereNotEndsWith(String column, String value, boolean caseSensitive) {
        query.orWhereNotEndsWith(column, value, caseSensitive);
        return self();
    }
}
