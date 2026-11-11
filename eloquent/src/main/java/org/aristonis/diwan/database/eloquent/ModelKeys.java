package org.aristonis.diwan.database.eloquent;

import java.util.Objects;
import org.aristonis.diwan.database.schema.ForeignKeySource;

/**
 * Reads the key of a model so a migration can add a foreign column for it with
 * {@code blueprint.foreignIdFor(ModelKeys.of(User.class))}.
 *
 * <p>The values come from the model's own configuration: the foreign key name, the table without
 * the connection's prefix, the primary key column and the kind of key. An integer key gives
 * {@link ForeignKeySource.KeyKind#INTEGER}; a unique id model gives {@code ULID} for a ULID key and
 * {@code UUID} for a UUID key. Any other key type is refused.
 */
public final class ModelKeys implements ForeignKeySource {

    private static final String INTEGER_KEY_TYPE = "int";

    private final String foreignKey;
    private final String table;
    private final String keyName;
    private final KeyKind keyKind;

    private ModelKeys(Model model) {
        Objects.requireNonNull(model, "model");
        String name = model.getKeyName();
        if (name == null) {
            throw new IllegalArgumentException(
                    "Model " + model.getClass().getName() + " has no primary key");
        }
        this.foreignKey = model.getForeignKey();
        this.table = model.getTable();
        this.keyName = name;
        this.keyKind = kindOf(model);
    }

    private static KeyKind kindOf(Model model) {
        if (INTEGER_KEY_TYPE.equals(model.getKeyType())) {
            return KeyKind.INTEGER;
        }
        UniqueIdKind unique = model.getUniqueIdKind();
        if (unique == UniqueIdKind.UUID) {
            return KeyKind.UUID;
        }
        if (unique == UniqueIdKind.ULID) {
            return KeyKind.ULID;
        }
        throw new IllegalArgumentException("Model " + model.getClass().getName() + " has a "
                + model.getKeyType() + " key; foreignIdFor supports integer, UUID and ULID keys");
    }

    /**
     * Reads the key of a model class, through the instance its registered constructor builds.
     *
     * @param type the model class, never {@code null}
     * @return the key description, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.UnregisteredModelException if the class has no registered entry
     * @throws IllegalArgumentException if the model has no primary key, or a key that is not an
     *     integer, UUID or ULID
     */
    public static ModelKeys of(Class<? extends Model> type) {
        Objects.requireNonNull(type, "type");
        return new ModelKeys(ModelQuery.registeredOrLoad(type).newBareModel());
    }

    /**
     * Reads the key of a model instance.
     *
     * @param model the model, never {@code null}
     * @return the key description, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalArgumentException if the model has no primary key, or a key that is not an
     *     integer, UUID or ULID
     */
    public static ModelKeys of(Model model) {
        return new ModelKeys(model);
    }

    /**
     * Returns the column other tables use to point at the model, such as {@code user_id}.
     *
     * @return the column name, never {@code null}
     */
    @Override
    public String foreignKey() {
        return foreignKey;
    }

    /**
     * Returns the model's table, without the connection's prefix.
     *
     * @return the table name, never {@code null}
     */
    @Override
    public String table() {
        return table;
    }

    /**
     * Returns the model's primary key column.
     *
     * @return the column name, never {@code null}
     */
    @Override
    public String keyName() {
        return keyName;
    }

    /**
     * Returns the kind of the model's key.
     *
     * @return the kind, never {@code null}
     */
    @Override
    public KeyKind keyKind() {
        return keyKind;
    }
}
