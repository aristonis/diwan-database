package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
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
     * Returns the table the model reads and writes.
     *
     * @return the table name, never {@code null}
     */
    public String getTable() {
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
}
