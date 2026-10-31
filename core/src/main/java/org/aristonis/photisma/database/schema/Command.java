package org.aristonis.photisma.database.schema;

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
    /** Drops the table. */
    public static final String DROP = "drop";
    /** Drops the table if it exists. */
    public static final String DROP_IF_EXISTS = "dropIfExists";
    /** Renames the table; attribute {@link #TO}. */
    public static final String RENAME = "rename";
    /** Adds a unique index; attributes {@link #INDEX} and {@link #COLUMNS}. */
    public static final String UNIQUE = "unique";

    /** Attribute holding a {@link ColumnDefinition}. */
    public static final String COLUMN = "column";
    /** Attribute holding the column names, a {@code List<String>}. */
    public static final String COLUMNS = "columns";
    /** Attribute holding the index name, a {@code String}. */
    public static final String INDEX = "index";
    /** Attribute holding the new table name, prefix included, a {@code String}. */
    public static final String TO = "to";

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
