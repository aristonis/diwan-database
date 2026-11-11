package org.aristonis.diwan.database.schema.grammar;

import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.ColumnDefinition;
import org.aristonis.diwan.database.schema.ForeignKeyDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One step a {@link Blueprint} asks a grammar to compile, such as creating the table or adding a
 * unique index.
 *
 * <p>A command is a name and a set of attributes, so later versions can add commands and
 * attributes without changing this type. Instances are immutable.
 */
public final class Command {

    /** Creates the table with every column of the blueprint. */
    public static final String CREATE = "create";
    /** Adds one column to an existing table; attribute {@link #COLUMN}. */
    public static final String ADD = "add";
    /** Redefines an existing column; attribute {@link #COLUMN}. */
    public static final String CHANGE = "change";
    /** Drops the table. */
    public static final String DROP = "drop";
    /** Drops the table if it exists. */
    public static final String DROP_IF_EXISTS = "dropIfExists";
    /** Renames the table; attribute {@link #TO}. */
    public static final String RENAME = "rename";
    /** Adds a unique index; attributes {@link #INDEX} and {@link #COLUMNS}. */
    public static final String UNIQUE = "unique";
    /** Adds a plain index; attributes {@link #INDEX} and {@link #COLUMNS}. */
    public static final String PLAIN_INDEX = "index";
    /** Sets the primary key; attributes {@link #INDEX} and {@link #COLUMNS}. */
    public static final String PRIMARY = "primary";
    /** Adds a foreign key; attributes {@link #FOREIGN_KEY}, {@link #INDEX} and {@link #COLUMNS}. */
    public static final String FOREIGN = "foreign";
    /** Drops a plain index; attribute {@link #INDEX}, and {@link #COLUMNS} when dropped by columns. */
    public static final String DROP_INDEX = "dropIndex";
    /** Drops a unique index; attribute {@link #INDEX}, and {@link #COLUMNS} when dropped by columns. */
    public static final String DROP_UNIQUE = "dropUnique";
    /** Drops the primary key; attribute {@link #INDEX}. */
    public static final String DROP_PRIMARY = "dropPrimary";
    /** Drops a foreign key; attribute {@link #INDEX}, and {@link #COLUMNS} when dropped by columns. */
    public static final String DROP_FOREIGN = "dropForeign";
    /** Renames an index; attributes {@link #FROM} and {@link #TO}. */
    public static final String RENAME_INDEX = "renameIndex";
    /** Drops columns; attribute {@link #COLUMNS}. */
    public static final String DROP_COLUMN = "dropColumn";
    /** Renames a column; attributes {@link #FROM} and {@link #TO}. */
    public static final String RENAME_COLUMN = "renameColumn";
    /** Adds a named table check; attributes {@link #NAME} and {@link #SQL}. */
    public static final String CHECK = "check";
    /** Drops a named table check; attribute {@link #NAME}. */
    public static final String DROP_CHECK = "dropCheck";
    /** Sets the comment of the table; attribute {@link #COMMENT}. */
    public static final String TABLE_COMMENT = "tableComment";

    /** Attribute holding a {@link ColumnDefinition}. */
    public static final String COLUMN = "column";
    /** Attribute holding the column names, a {@code List<String>}. */
    public static final String COLUMNS = "columns";
    /** Attribute holding the index name, a {@code String}. */
    public static final String INDEX = "index";
    /** Attribute holding the new name: of the table, prefix included, or of an index or column. */
    public static final String TO = "to";
    /** Attribute holding the old name of an index or column, a {@code String}. */
    public static final String FROM = "from";
    /** Attribute holding the name of a constraint that is not an index, such as a check, a {@code String}. */
    public static final String NAME = "name";
    /** Attribute holding trusted SQL, a {@code String}. */
    public static final String SQL = "sql";
    /**
     * Attribute holding the {@code WHERE} conditions of a partial index, a {@code List<IndexCondition>};
     * absent when the index is not partial.
     */
    public static final String WHERE = "where";
    /**
     * Attribute holding the sort order of index columns, a {@code Map<String, String>} from column
     * name to {@code "asc"} or {@code "desc"}; absent when no order was set.
     */
    public static final String ORDERS = "orders";
    /**
     * Attribute holding the collation of index columns, a {@code Map<String, String>} from column
     * name to collation name; absent when no collation was set.
     */
    public static final String COLLATIONS = "collations";
    /** Attribute holding a {@link ForeignKeyDefinition}. */
    public static final String FOREIGN_KEY = "foreignKey";
    /** Attribute holding the comment text of {@link #TABLE_COMMENT}, a {@code String}. */
    public static final String COMMENT = "comment";
    /**
     * Attribute marking an index whose {@link #COLUMNS} entry is a raw SQL expression that grammars
     * write as it is; absent for an index on columns.
     */
    public static final String RAW = "raw";

    private final String name;
    private final Map<String, Object> attributes;

    /**
     * Creates a command.
     *
     * @param name the command name, never {@code null}
     * @param attributes the attributes, never {@code null}; copied, and each {@code List} value is
     *     copied into an unmodifiable list
     * @throws NullPointerException if an argument, a key or a value is {@code null}
     */
    public Command(String name, Map<String, ?> attributes) {
        this.name = Objects.requireNonNull(name, "name");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
            Object value = Objects.requireNonNull(entry.getValue(), "attribute value");
            if (value instanceof List) {
                value = Collections.unmodifiableList(new ArrayList<>((List<?>) value));
            }
            copy.put(Objects.requireNonNull(entry.getKey(), "attribute key"), value);
        }
        this.attributes = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the command name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns an attribute.
     *
     * @param key the attribute key, never {@code null}
     * @return the value, or empty when the command has no such attribute
     */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(attributes.get(Objects.requireNonNull(key, "key")));
    }

    /**
     * Returns a text attribute that the command must have.
     *
     * @param key the attribute key, never {@code null}
     * @return the value, never {@code null}
     * @throws IllegalStateException if the attribute is missing or is not text
     */
    public String string(String key) {
        Object value = require(key);
        if (!(value instanceof String)) {
            throw new IllegalStateException("Attribute " + key + " of command " + name + " is not text");
        }
        return (String) value;
    }

    /**
     * Returns a list-of-names attribute that the command must have.
     *
     * @param key the attribute key, never {@code null}
     * @return an unmodifiable list, never {@code null}
     * @throws IllegalStateException if the attribute is missing, is not a list or holds a value
     *     that is not text
     */
    public List<String> strings(String key) {
        Object value = require(key);
        if (!(value instanceof List)) {
            throw new IllegalStateException("Attribute " + key + " of command " + name + " is not a list");
        }
        for (Object element : (List<?>) value) {
            if (!(element instanceof String)) {
                throw new IllegalStateException("Attribute " + key + " of command " + name + " holds a non-text value");
            }
        }
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) value;
        return names;
    }

    /**
     * Returns the column definition attribute that the command must have.
     *
     * @return the definition, never {@code null}
     * @throws IllegalStateException if the command has no {@link #COLUMN} attribute
     */
    public ColumnDefinition column() {
        Object value = require(COLUMN);
        if (!(value instanceof ColumnDefinition)) {
            throw new IllegalStateException("Attribute column of command " + name + " is not a column definition");
        }
        return (ColumnDefinition) value;
    }

    /**
     * Describes the command for logs and test output.
     *
     * @return the name and attribute keys, never {@code null}
     */
    /**
     * Returns the {@link #FOREIGN_KEY} attribute.
     *
     * @return the foreign key, never {@code null}
     * @throws IllegalStateException if the command has no foreign key
     */
    public ForeignKeyDefinition foreignKey() {
        Object value = require(FOREIGN_KEY);
        if (!(value instanceof ForeignKeyDefinition)) {
            throw new IllegalStateException("Attribute foreignKey of command " + name + " is not a foreign key");
        }
        return (ForeignKeyDefinition) value;
    }

    /**
     * Tells whether the command has an attribute.
     *
     * @param key the attribute name, never {@code null}
     * @return {@code true} if the attribute is set
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public boolean has(String key) {
        return attributes.containsKey(Objects.requireNonNull(key, "key"));
    }

    @Override
    public String toString() {
        return "Command[" + name + ", " + attributes.keySet() + "]";
    }

    private Object require(String key) {
        Object value = attributes.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            throw new IllegalStateException("Command " + name + " has no attribute " + key);
        }
        return value;
    }
}
