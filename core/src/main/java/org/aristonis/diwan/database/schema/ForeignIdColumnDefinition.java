package org.aristonis.diwan.database.schema;

import java.util.Collections;
import java.util.Objects;
import org.aristonis.diwan.database.support.Inflector;

/**
 * The column {@link Blueprint#foreignId(String)}, {@link Blueprint#foreignUuid(String)} or
 * {@link Blueprint#foreignUlid(String)} adds: a key column that can point at another table's key in
 * one call, {@code table.foreignId("user_id").constrained()}.
 *
 * <p>Every modifier returns this type, so {@link #constrained()} can come after them:
 * {@code table.foreignId("user_id").nullable().constrained().nullOnDelete()}.
 */
public final class ForeignIdColumnDefinition extends ColumnDefinition {

    private static final String ID_SUFFIX = "_id";

    private final Blueprint blueprint;

    ForeignIdColumnDefinition(Blueprint blueprint, String name) {
        super(name, ColumnType.BIG_INTEGER, 0, 0, 0);
        this.blueprint = blueprint;
        unsigned();
    }

    ForeignIdColumnDefinition(Blueprint blueprint, String name, ColumnType type, int length) {
        super(name, type, length, 0, 0);
        this.blueprint = blueprint;
    }

    /**
     * Adds a foreign key to the {@code id} column of the table the column name points at, as
     * Laravel does: {@code user_id} references {@code users}, {@code blog_category_id}
     * {@code blog_categories}.
     *
     * @apiNote The plural is guessed with English rules and a short list of irregular nouns; name
     *     the table with {@link #constrained(String)} when the guess is wrong.
     * @return the foreign key, for its actions, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition constrained() {
        String column = name();
        String stem = column.endsWith(ID_SUFFIX) && column.length() > ID_SUFFIX.length()
                ? column.substring(0, column.length() - ID_SUFFIX.length())
                : column;
        return constrained(Inflector.plural(stem));
    }

    /**
     * Adds a foreign key to the {@code id} column of a named table.
     *
     * @param table the referenced table without prefix, never {@code null}
     * @return the foreign key, for its actions, never {@code null}
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition constrained(String table) {
        return constrained(table, "id");
    }

    /**
     * Adds a foreign key to a named column of a named table.
     *
     * @param table the referenced table without prefix, never {@code null}
     * @param column the referenced column, never {@code null}
     * @return the foreign key, for its actions, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition constrained(String table, String column) {
        return references(column).on(table);
    }

    /**
     * Adds a named foreign key to a named column of a named table.
     *
     * @param table the referenced table without prefix, never {@code null}
     * @param column the referenced column, never {@code null}
     * @param indexName the key name, never {@code null}
     * @return the foreign key, for its actions, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is empty, has a control character, or is too long
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition constrained(String table, String column, String indexName) {
        return references(column, indexName).on(table);
    }

    /**
     * Adds a foreign key from this column to a column of a table set next with
     * {@link ForeignKeyDefinition#on(String)}.
     *
     * @param column the referenced column, never {@code null}
     * @return the foreign key, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition references(String column) {
        return blueprint.foreign(name()).references(column);
    }

    /**
     * Adds a named foreign key from this column to a column of a table set next with
     * {@link ForeignKeyDefinition#on(String)}.
     *
     * @param column the referenced column, never {@code null}
     * @param indexName the key name, never {@code null}
     * @return the foreign key, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition references(String column, String indexName) {
        return blueprint.foreign(Collections.singletonList(name()), Objects.requireNonNull(indexName, "indexName")).references(column);
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition nullable() {
        super.nullable();
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition nullable(boolean isNullable) {
        super.nullable(isNullable);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition defaultValue(Object value) {
        super.defaultValue(value);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition unique() {
        super.unique();
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition unique(String indexName) {
        super.unique(indexName);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition index() {
        super.index();
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition index(String indexName) {
        super.index(indexName);
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition primary() {
        super.primary();
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition unsigned() {
        super.unsigned();
        return this;
    }

    /** {@inheritDoc} */
    @Override
    public ForeignIdColumnDefinition comment(String text) {
        super.comment(text);
        return this;
    }
}
