package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.query.SimplePage;

/**
 * One page of models without a total count, from {@link EloquentBuilder#simplePaginate(int, int)}.
 *
 * <p>Immutable apart from the models it holds.
 *
 * @param <T> the model class
 */
public final class SimpleModelPage<T extends Model> {

    private final SimplePage page;
    private final ModelCollection<T> items;

    SimpleModelPage(SimplePage page, ModelCollection<T> items) {
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
     * Returns this page's number.
     *
     * @return the page number, from 1
     */
    public int currentPage() {
        return page.currentPage();
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
        return "SimpleModelPage[" + page + "]";
    }
}
