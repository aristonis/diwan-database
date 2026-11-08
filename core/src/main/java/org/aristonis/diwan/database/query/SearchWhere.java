package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Internal;

/**
 * An escaped text search: a column that contains, starts with or ends with a literal text. The
 * pattern itself is a binding, built by the grammar when the condition is added.
 *
 * @apiNote Internal: read by dialect grammars; may change without notice.
 */
@Internal
public final class SearchWhere implements Where {

    /** Where in the column the text must appear. */
    public enum Anchor {
        /** Anywhere in the value. */
        CONTAINS,
        /** At the start of the value. */
        STARTS_WITH,
        /** At the end of the value. */
        ENDS_WITH
    }

    private final String conjunction;
    private final String column;
    private final Anchor anchor;
    private final boolean caseSensitive;
    private final boolean not;

    /**
     * Creates the condition.
     *
     * @param conjunction the joining word
     * @param column the column name
     * @param anchor where the text must appear
     * @param caseSensitive whether the match must respect case
     * @param not whether the condition is negated
     */
    public SearchWhere(String conjunction, String column, Anchor anchor, boolean caseSensitive, boolean not) {
        this.conjunction = conjunction;
        this.column = column;
        this.anchor = anchor;
        this.caseSensitive = caseSensitive;
        this.not = not;
    }

    /** {@inheritDoc} */
    @Override
    public String conjunction() {
        return conjunction;
    }

    /**
     * Returns the column name.
     *
     * @return the column name
     */
    public String column() {
        return column;
    }

    /**
     * Returns where the text must appear.
     *
     * @return the anchor
     */
    public Anchor anchor() {
        return anchor;
    }

    /**
     * Returns whether the match must respect case.
     *
     * @return whether the match must respect case
     */
    public boolean caseSensitive() {
        return caseSensitive;
    }

    /**
     * Returns whether the condition is negated.
     *
     * @return whether the condition is negated
     */
    public boolean not() {
        return not;
    }
}
