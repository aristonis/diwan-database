package org.aristonis.diwan.database.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Marks a position in a cursor-paginated result: the values of the order columns of one row, and
 * whether the page wanted lies after it or before it; Laravel's
 * {@code Illuminate\Pagination\Cursor}.
 *
 * <p>A cursor travels to a client and back as the text {@link #encode()} returns, which
 * {@link #decode(String)} reads. Supported values are {@code null}, {@link String},
 * {@link Long}, {@link BigDecimal}, {@link Double}, {@link Boolean}, {@link Instant},
 * {@link LocalDateTime}, {@link OffsetDateTime} and {@link UUID}. An {@link Integer},
 * {@link Short} or {@link Byte} is kept as a {@code Long}, a {@link Float} as a {@code Double}, and a
 * {@link java.sql.Timestamp} as an {@code Instant}.
 *
 * <p>The token is not signed or encrypted: a client can read it and forge another one. A forged
 * token can only move the page position, since every value is bound as a parameter and the column
 * names must match the query's own order columns; never put a value in a cursor that the client may
 * not see.
 *
 * <p>Immutable and safe to share between threads.
 */
public final class Cursor {

    private final Map<String, Object> parameters;
    private final boolean pointsToNextItems;

    /**
     * Creates a cursor.
     *
     * @param parameters the order column values by column name, never {@code null}, at least one;
     *     copied, values as listed in the class description
     * @param pointsToNextItems {@code true} for the page after the row, {@code false} for the page
     *     before it
     * @throws NullPointerException if {@code parameters} or a name is {@code null}
     * @throws IllegalArgumentException if {@code parameters} is empty or a value has an unsupported
     *     type
     */
    public Cursor(Map<String, ?> parameters, boolean pointsToNextItems) {
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("A cursor needs at least one parameter");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "parameter name"), CursorTokens.normalize(entry.getValue()));
        }
        this.parameters = Collections.unmodifiableMap(copy);
        this.pointsToNextItems = pointsToNextItems;
    }

    /**
     * Reads a cursor from the text {@link #encode()} returned.
     *
     * @param token the encoded cursor, never {@code null}
     * @return the cursor, never {@code null}
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws IllegalArgumentException if the token is not a well-formed cursor
     */
    public static Cursor decode(String token) {
        return CursorTokens.decode(Objects.requireNonNull(token, "token"));
    }

    /**
     * Writes this cursor as URL-safe text: base64url without padding.
     *
     * @return the token, never {@code null}
     */
    public String encode() {
        return CursorTokens.encode(parameters, pointsToNextItems);
    }

    /**
     * Returns the value of one order column.
     *
     * @param name the column name as in the query's order, never {@code null}
     * @return the value, may be {@code null}
     * @throws IllegalArgumentException if the cursor has no such parameter
     */
    public Object parameter(String name) {
        if (!parameters.containsKey(name)) {
            throw new IllegalArgumentException("Unable to find parameter [" + name + "] in the cursor");
        }
        return parameters.get(name);
    }

    /**
     * Returns every parameter.
     *
     * @return the values by column name in order, unmodifiable, never {@code null}
     */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * Tells whether the cursor leads to the rows after its position.
     *
     * @return {@code true} for the next page
     */
    public boolean pointsToNextItems() {
        return pointsToNextItems;
    }

    /**
     * Tells whether the cursor leads to the rows before its position.
     *
     * @return {@code true} for the previous page
     */
    public boolean pointsToPreviousItems() {
        return !pointsToNextItems;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Cursor)) {
            return false;
        }
        Cursor that = (Cursor) other;
        return pointsToNextItems == that.pointsToNextItems && parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        return 31 * parameters.hashCode() + (pointsToNextItems ? 1 : 0);
    }

    @Override
    public String toString() {
        return "Cursor[" + (pointsToNextItems ? "next " : "previous ") + parameters + "]";
    }
}
