package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.support.Inflector;

/**
 * The configuration layer of {@link Model}: protected methods a model overrides, and the public
 * getters that read them.
 *
 * <p>Configuration is read through methods, not annotations, so it survives code shrinkers on
 * Android without keep rules. Each method is read on every use and should return a constant;
 * {@link #casts()} and {@link #accessors()} are read once per model class.
 */
abstract class ModelConfig {

    private static final String DEFAULT_KEY = "id";
    private static final String STRING_KEY = "string";
    private static final int DEFAULT_PER_PAGE = 15;

    // Set only on the related instance of a self-relation existence query (alias of its table).
    private String tableAlias;

    ModelConfig() {
    }

    /**
     * Returns the table name, or {@code null} for the default: the snake case plural of the
     * class's simple name, {@code BlogPost} giving {@code blog_posts}.
     *
     * @apiNote The plural comes from a small English rule set, not Laravel's full inflector.
     *     Override this method for a name it guesses wrong.
     * @return the table name, or {@code null} for the default
     */
    protected String table() {
        return null;
    }

    /**
     * Returns the primary key column; {@code id} by default.
     *
     * @return the key column, or {@code null} for a model without a primary key
     */
    protected String primaryKey() {
        return DEFAULT_KEY;
    }

    /**
     * Returns the key type, {@code int} or {@code string}; {@code int} by default.
     *
     * @return the key type
     */
    protected String keyType() {
        return "int";
    }

    /**
     * Returns whether the database assigns the key on insert; {@code true} by default.
     *
     * @return whether the key increments
     */
    protected boolean incrementing() {
        return true;
    }

    /**
     * Returns the name of the connection the model uses through a {@link ConnectionResolver};
     * {@code null} for the resolver's default.
     *
     * @return the connection name, or {@code null}
     */
    protected String connectionName() {
        return null;
    }

    /**
     * Returns whether the model writes {@code created_at} and {@code updated_at}; {@code true} by
     * default.
     *
     * @return whether the model keeps timestamps
     */
    protected boolean timestamps() {
        return true;
    }

    /**
     * Returns the creation timestamp column; {@code created_at} by default.
     *
     * @return the column, or {@code null} for none
     */
    protected String createdAtColumn() {
        return "created_at";
    }

    /**
     * Returns the update timestamp column; {@code updated_at} by default.
     *
     * @return the column, or {@code null} for none
     */
    protected String updatedAtColumn() {
        return "updated_at";
    }

    /**
     * Returns the keys mass assignment may set; empty by default.
     *
     * @return the fillable keys, never {@code null}
     */
    protected List<String> fillable() {
        return Collections.emptyList();
    }

    /**
     * Returns the keys mass assignment must not set; {@code ["*"]}, every key, by default.
     *
     * @return the guarded keys, never {@code null}
     */
    protected List<String> guarded() {
        return Collections.singletonList("*");
    }

    /**
     * Returns the default page size; 15 by default.
     *
     * @return the rows per page, at least 1
     */
    protected int perPage() {
        return DEFAULT_PER_PAGE;
    }

    /**
     * Returns the {@link java.time.format.DateTimeFormatter} pattern of text timestamps;
     * {@code yyyy-MM-dd HH:mm:ss} by default, Laravel's {@code Y-m-d H:i:s}.
     *
     * @return the pattern, never {@code null}
     */
    protected String dateFormat() {
        return "yyyy-MM-dd HH:mm:ss";
    }

    /**
     * Returns how timestamps are written; {@link TimestampFormat#TEXT} by default.
     *
     * @return the format, never {@code null}
     */
    protected TimestampFormat timestampFormat() {
        return TimestampFormat.TEXT;
    }

    /**
     * Returns the kind of unique id generated for the primary key on insert;
     * {@link UniqueIdKind#NONE} by default.
     *
     * @return the kind, never {@code null}
     */
    protected UniqueIdKind uniqueIdKind() {
        return UniqueIdKind.NONE;
    }

    /**
     * Returns the casts, by attribute name; none by default.
     *
     * <p>Read once per model class and kept, so the map must not depend on instance state. A cast
     * string from {@link org.aristonis.diwan.database.eloquent.casts.Casts#of(String)} is parsed
     * then; an invalid one throws on the first attribute access of the class.
     *
     * @return the casts, never {@code null}
     */
    protected Map<String, Cast<?>> casts() {
        return Collections.emptyMap();
    }

    /**
     * Returns the accessors and mutators, by attribute name; none by default.
     *
     * <p>Read once per model class and kept, so the map must not depend on instance state.
     *
     * @return the accessors, never {@code null}
     */
    protected Map<String, Attribute<?>> accessors() {
        return Collections.emptyMap();
    }

    /**
     * Returns the attributes left out of the model's map and JSON forms; none by default.
     *
     * @return the hidden attributes, never {@code null}
     */
    protected List<String> hidden() {
        return Collections.emptyList();
    }

    /**
     * Returns the only attributes kept in the model's map and JSON forms; empty, meaning every
     * attribute, by default.
     *
     * @return the visible attributes, never {@code null}
     */
    protected List<String> visible() {
        return Collections.emptyList();
    }

    /**
     * Returns the computed attributes added to the model's map and JSON forms; none by default.
     *
     * @return the appended attributes, never {@code null}
     */
    protected List<String> appends() {
        return Collections.emptyList();
    }

    /**
     * Returns the codec the JSON casts and the JSON form use; {@link JsonCodec#standard()} by
     * default.
     *
     * @return the codec, never {@code null}
     */
    protected JsonCodec jsonCodec() {
        return JsonCodec.standard();
    }

    /**
     * Returns the relations the model declares, by name; none by default.
     *
     * <p>Each entry builds one relation from a model, usually a reference to the model's typed
     * relation method:
     *
     * <pre>{@code
     * protected RelationMap<?> relations() {
     *     return RelationMap.of(Post.class).add("author", Post::author).add("comments", Post::comments);
     * }
     * }</pre>
     *
     * <p>The map names the relations that {@code relation(name)}, eager loading and existence
     * queries reach. It is read and checked once per model class, on first use, so it must not
     * depend on instance state; a subclass inherits its parent's map. A bad map throws
     * {@link org.aristonis.diwan.database.eloquent.relations.InvalidRelationException} on that
     * first use.
     *
     * @return the relation map, never {@code null}
     */
    protected RelationMap<?> relations() {
        return RelationMap.empty();
    }

    /**
     * Returns the names of the relations whose owners this model touches; none by default, a
     * port of Laravel's {@code $touches}.
     *
     * <p>Saving a dirty model, or deleting an existing one, sets the {@code updated_at} column
     * of each named relation's rows to the current time, in the set's iteration order, so return
     * a {@link java.util.LinkedHashSet} when the order matters:
     *
     * <pre>{@code
     * protected Set<String> touches() {
     *     return new LinkedHashSet<>(Arrays.asList("post"));
     * }
     * }</pre>
     *
     * <p>Each name must be a key of {@link #relations()} whose relation is a {@code HasOne},
     * {@code HasMany} or {@code BelongsTo}. The set is read and checked once per model class, on
     * first use, so it must not depend on instance state; a bad name throws
     * {@link org.aristonis.diwan.database.eloquent.relations.InvalidRelationException} naming the
     * class and the relation. A check that fails only because a related class has no
     * {@link ModelQuery} entry yet is not cached and runs again on the next save. The bad entry
     * is reported on first use, after the save that triggered it has written its row, so the
     * exception reaches the caller with that row stored and the model left dirty.
     *
     * <p>A touch is one raw {@code UPDATE}: it raises no model events, applies no casts and
     * loads no model. The owner models already in memory keep their old {@code updated_at}
     * value. The touch goes on into an owner only when that relation is already loaded on this
     * model, where the owner's {@code saved()} hook runs and its own touches follow; unlike
     * Laravel, an unloaded relation is never loaded for this. Like the relation map, this needs
     * no reflection.
     *
     * <p>{@code save()} runs no transaction: if a touch {@code UPDATE} fails, the child row is
     * already written, the model stays dirty and the exception reaches the caller. Use
     * {@code saveOrFail()} to make the save and its touches one transaction.
     *
     * @return the relation names, never {@code null}
     */
    protected Set<String> touches() {
        return Collections.emptySet();
    }

    /**
     * Returns whether loaded relations appear in the model's map and JSON forms under snake case
     * keys, {@code blogPosts} giving {@code blog_posts}; {@code true} by default, as Laravel.
     *
     * @return whether relation keys are snake case
     */
    protected boolean snakeRelationKeys() {
        return true;
    }

    /**
     * Returns the table the model reads and writes, or the alias it was given as the related
     * model of a self-relation existence query.
     *
     * @return the table name or alias, never {@code null}
     */
    public String getTable() {
        if (tableAlias != null) {
            return tableAlias;
        }
        String table = table();
        return table != null ? table : Inflector.plural(Inflector.snake(getClass().getSimpleName()));
    }

    /**
     * Returns the primary key column.
     *
     * @return the key column, or {@code null} for a model without a primary key
     */
    public String getKeyName() {
        return primaryKey();
    }

    /**
     * Returns the primary key qualified by the table, such as {@code users.id}.
     *
     * @return the qualified key column
     */
    public String getQualifiedKeyName() {
        return qualifyColumn(getKeyName());
    }

    /**
     * Returns the key type: {@code string} for a unique id model, else {@code keyType()}.
     *
     * @return the key type, {@code int} or {@code string}
     */
    public String getKeyType() {
        return getUniqueIdKind() != UniqueIdKind.NONE ? STRING_KEY : keyType();
    }

    /**
     * Returns whether the database assigns the key: never for a unique id model.
     *
     * @return whether the key increments
     */
    public boolean getIncrementing() {
        return getUniqueIdKind() == UniqueIdKind.NONE && incrementing();
    }

    /**
     * Returns the connection name.
     *
     * @return the name, or {@code null} for the resolver's default
     */
    public String getConnectionName() {
        return connectionName();
    }

    /**
     * Returns the creation timestamp column.
     *
     * @return the column, or {@code null} for none
     */
    public String getCreatedAtColumn() {
        return createdAtColumn();
    }

    /**
     * Returns the update timestamp column.
     *
     * @return the column, or {@code null} for none
     */
    public String getUpdatedAtColumn() {
        return updatedAtColumn();
    }

    /**
     * Returns the fillable keys.
     *
     * @return a copy of the keys, never {@code null}
     */
    public List<String> getFillable() {
        return Collections.unmodifiableList(new ArrayList<>(fillable()));
    }

    /**
     * Returns the guarded keys.
     *
     * @return a copy of the keys, never {@code null}
     */
    public List<String> getGuarded() {
        return Collections.unmodifiableList(new ArrayList<>(guarded()));
    }

    /**
     * Returns the default page size.
     *
     * @return the rows per page
     */
    public int getPerPage() {
        return perPage();
    }

    /**
     * Returns the pattern of text timestamps.
     *
     * @return the pattern, never {@code null}
     */
    public String getDateFormat() {
        return dateFormat();
    }

    /**
     * Returns how timestamps are written.
     *
     * @return the format, never {@code null}
     */
    public TimestampFormat getTimestampFormat() {
        return timestampFormat();
    }

    /**
     * Returns the kind of unique id generated on insert.
     *
     * @return the kind, never {@code null}
     */
    public UniqueIdKind getUniqueIdKind() {
        return uniqueIdKind();
    }

    /**
     * Returns the codec the JSON casts and the JSON form use.
     *
     * @return the codec, never {@code null}
     * @throws NullPointerException if {@link #jsonCodec()} returns {@code null}
     */
    public JsonCodec getJsonCodec() {
        return Objects.requireNonNull(jsonCodec(), "jsonCodec");
    }

    /**
     * Returns the name other tables use for a key pointing at this model, such as
     * {@code blog_post_id}: the snake case class name, an underscore and the key column.
     *
     * @return the foreign key column
     */
    public String getForeignKey() {
        return Inflector.snake(getClass().getSimpleName()) + "_" + getKeyName();
    }

    /**
     * Qualifies a column by the model's table, leaving a qualified one unchanged.
     *
     * @param column the column, never {@code null}
     * @return the qualified column, such as {@code users.name}
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public String qualifyColumn(String column) {
        return column.indexOf('.') >= 0 ? column : getTable() + "." + column;
    }

    final void useTableAlias(String alias) {
        tableAlias = Objects.requireNonNull(alias, "alias");
    }
}
