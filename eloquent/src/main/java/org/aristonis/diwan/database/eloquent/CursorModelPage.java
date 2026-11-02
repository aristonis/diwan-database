package org.aristonis.diwan.database.eloquent;

import java.util.Optional;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.CursorPage;

/**
 * One page of models read by key position, from
 * {@link EloquentBuilder#cursorPaginate(int, Cursor)}.
 *
 * <p>Immutable apart from the models it holds.
 *
 * @param <T> the model class
 */
public final class CursorModelPage<T extends Model> {

    private final CursorPage page;
    private final ModelCollection<T> items;

    CursorModelPage(CursorPage page, ModelCollection<T> items) {
        this.page = page;
        this.items = items;
    }

    /**
     * Returns the models of this page.
     *
     * @return the models, read-only, never {@code null}
     */
    public ModelCollection<T> items() {
        return items;
    }

    /**
     * Returns the page size.
     *
     * @return the models per page
     */
    public int perPage() {
        return page.perPage();
    }

    /**
     * Returns the cursor of the page after this one.
     *
     * @return the cursor, or empty on the last page
     */
    public Optional<Cursor> nextCursor() {
        return page.nextCursor();
    }

    /**
     * Returns the cursor of the page before this one.
     *
     * @return the cursor, or empty on the first page
     */
    public Optional<Cursor> previousCursor() {
        return page.previousCursor();
    }

    /**
     * Returns the page's cursors, without its models.
     *
     * @return the description, never {@code null}
     */
    @Override
    public String toString() {
        return "CursorModelPage[" + page + "]";
    }
}
