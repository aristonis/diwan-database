package org.aristonis.diwan.database.eloquent.page;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.query.LengthAwarePage;

/**
 * One page of models with the total count, from {@link EloquentBuilder#paginate(int, int)}.
 *
 * <p>Immutable apart from the models it holds.
 *
 * @param <T> the model class
 */
public final class ModelPage<T extends Model> {

    private final LengthAwarePage page;
    private final ModelCollection<T> items;

    /**
     * Creates a page of models over a page of rows.
     *
     * @param page the page of rows, never {@code null}
     * @param items the models for the rows, never {@code null}
     */
    @Internal
    public ModelPage(LengthAwarePage page, ModelCollection<T> items) {
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
     * Returns the number of matching rows on every page.
     *
     * @return the total
     */
    public long total() {
        return page.total();
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
     * Returns this page's number.
     *
     * @return the page number, from 1
     */
    public int currentPage() {
        return page.currentPage();
    }

    /**
     * Returns the number of the last page.
     *
     * @return the last page number, at least 1
     */
    public int lastPage() {
        return page.lastPage();
    }

    /**
     * Returns whether a page follows this one.
     *
     * @return whether more pages follow
     */
    public boolean hasMorePages() {
        return page.hasMorePages();
    }

    /**
     * Returns the page's numbers, without its models.
     *
     * @return the description, never {@code null}
     */
    @Override
    public String toString() {
        return "ModelPage[" + page + "]";
    }
}
