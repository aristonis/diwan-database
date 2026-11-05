package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * What {@link OneOfManyJoin} needs from the relation it joins for: the partition columns, the
 * subquery shape and the parent filter. Implemented by the relation bases through a private
 * adapter, so none of it becomes public API.
 *
 * @param <R> the related model class
 */
interface OneOfManyTarget<R extends Model> {

    /**
     * Returns the columns the rows are grouped by, qualified the way the given query reads them:
     * the foreign key, plus the type column of a morph relation, or the through table's first key.
     *
     * @param query the main query or a subquery, never {@code null}
     * @return the qualified columns, never {@code null} or empty
     */
    List<String> oneOfManyPartition(EloquentBuilder<R> query);

    /**
     * Gives a subquery what its rows need before grouping: the through join and the through
     * parent filter for a has-one-through relation, nothing for the others.
     *
     * @param sub the fresh subquery, never {@code null}
     */
    void prepareOneOfManySubQuery(EloquentBuilder<R> sub);

    /**
     * Limits the innermost subquery to the parents being read.
     *
     * @param sub the innermost subquery, never {@code null}
     * @param mode how the query is being built, never {@code null}; an existence query adds nothing
     * @param keys the eager chunk's keys, never {@code null}; empty outside eager loading
     */
    void filterOneOfManySubQuery(EloquentBuilder<R> sub, OneOfManyJoin.Mode mode, List<Object> keys);

    /**
     * Returns the name the main query uses for the related table, the alias of a self relation
     * when there is one.
     *
     * @param target the main query, never {@code null}
     * @return the table name or alias, never {@code null}
     */
    String relatedQualifier(EloquentBuilder<R> target);

    /**
     * Returns the alias of the joined subquery.
     *
     * @param relatedTable the related table without any alias, never {@code null}
     * @return the alias, never {@code null}
     */
    String oneOfManyAlias(String relatedTable);
}
