package org.aristonis.diwan.database.eloquent.casts;

/**
 * Hashes the text of a {@code hashed} cast attribute and recognizes already hashed text; the
 * model supplies it.
 *
 * <p>Diwan does no hashing: the algorithm, its cost and the salt belong to the implementation,
 * and so does checking a password against a stored hash. Use a password hash such as bcrypt or
 * argon2 from your own library. {@link #hash} turns the text to store into the text the column
 * holds; {@link #isHashed} tells the cast whether a value being set is already a hash, so it is
 * not hashed twice. The column must be a text type wide enough for your hash format.
 *
 * @implSpec Implementations must be safe to share across threads. A wrong {@link #isHashed} can
 * hash a hash a second time or store a plain value unhashed. An exception thrown from any
 * method reaches the caller of the model method as the cause of an
 * {@code InvalidCastException} (same instance). An implementation should not throw
 * {@code InvalidCastException}; the model rethrows that type as it is.
 */
public interface AttributeHasher {

    /**
     * Hashes attribute text into the text a column stores.
     *
     * @param plain the text to hash, never null
     * @return the hash to store, never null
     * @throws RuntimeException if the text cannot be hashed; the model wraps it in an
     *     {@code InvalidCastException} with this exception as cause
     */
    String hash(String plain);

    /**
     * Reports whether a value is already a hash made in this implementation's format.
     *
     * @param value the text being set, never null
     * @return {@code true} if the value is a hash, so it is not hashed again
     * @throws RuntimeException if the value cannot be inspected; the model wraps it in an
     *     {@code InvalidCastException} with this exception as cause
     */
    boolean isHashed(String value);

    /**
     * Reports whether an already hashed value uses the current algorithm and cost settings.
     *
     * <p>Called only for a value that {@link #isHashed} accepted. When it returns {@code false}
     * the write fails and nothing is stored. Override it to refuse hashes below your minimum cost:
     * the default keeps any hash a caller supplies, weak ones included.
     *
     * @implSpec The default returns {@code true}.
     *
     * @param hashed the hash being set, never null
     * @return {@code true} if the hash may be stored as given
     * @throws RuntimeException if the hash cannot be inspected; the model wraps it in an
     *     {@code InvalidCastException} with this exception as cause
     */
    default boolean isCurrent(String hashed) {
        return true;
    }
}
