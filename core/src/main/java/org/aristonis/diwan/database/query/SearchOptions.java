package org.aristonis.diwan.database.query;

/**
 * The settings of an escaped text search such as {@link Builder#whereContains(String, String,
 * SearchOptions)}: today only whether the match respects case.
 *
 * <p>Instances are immutable and safe to share between threads. There is no public constructor:
 * start from {@link #DEFAULT} or {@link #caseSensitive()} and derive a changed copy with a
 * {@code with} method, so a setting added in a later release never breaks existing callers.
 *
 * @apiNote A grammar that cannot honour a setting refuses the query with an
 *     {@link UnsupportedOperationException} that names the setting and the grammar; a setting is
 *     never ignored silently.
 */
public final class SearchOptions {

    /** The default settings: a case-insensitive match. */
    public static final SearchOptions DEFAULT = new SearchOptions(false);

    private static final SearchOptions CASE_SENSITIVE = new SearchOptions(true);

    private final boolean caseSensitive;

    private SearchOptions(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    /**
     * Returns options for a match that respects case.
     *
     * @return the options, never {@code null}
     */
    public static SearchOptions caseSensitive() {
        return CASE_SENSITIVE;
    }

    /**
     * Returns a copy with the case setting changed; this instance is not modified.
     *
     * @param caseSensitive whether the match respects case
     * @return the options, never {@code null}
     */
    public SearchOptions withCaseSensitive(boolean caseSensitive) {
        return caseSensitive ? CASE_SENSITIVE : DEFAULT;
    }

    /**
     * Returns whether the match respects case.
     *
     * @return whether the match respects case
     */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object other) {
        return other instanceof SearchOptions && ((SearchOptions) other).caseSensitive == caseSensitive;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Boolean.hashCode(caseSensitive);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "SearchOptions[caseSensitive=" + caseSensitive + "]";
    }
}
