package org.aristonis.diwan.database.eloquent.casts;

/**
 * Encrypts and decrypts the text of an {@code encrypted} cast attribute; the model supplies it.
 *
 * <p>Diwan does no cryptography: the algorithm, the keys and key rotation belong to the
 * implementation. {@link #encrypt} receives the text to store (the JSON text for the json,
 * array, collection and object forms) and returns the text the column holds. The column
 * must be a text type, and an encrypted column cannot be searched with {@code where}.
 *
 * @implSpec Implementations must be safe to share across threads, and {@link #decrypt} must
 * accept what {@link #encrypt} returned, including output made under an earlier key when
 * {@link #hasPreviousKeys()} is {@code true}. An exception thrown by {@code encrypt} or
 * {@code decrypt}, and a missing encrypter, reach the caller as an
 * {@code InvalidCastException} whose cause is the original exception (same instance). An
 * implementation should not throw {@code InvalidCastException}; the model rethrows that type as
 * it is.
 */
public interface AttributeEncrypter {

    /**
     * Encrypts attribute text into the text a column stores.
     *
     * @param plain the text to encrypt, never null
     * @return the ciphertext to store, never null
     * @throws RuntimeException if the text cannot be encrypted; the model wraps it in an
     *     {@code InvalidCastException} with this exception as cause
     */
    String encrypt(String plain);

    /**
     * Decrypts stored column text back into attribute text.
     *
     * @param cipher the stored text, never null
     * @return the decrypted text; null reads as a null attribute value
     * @throws RuntimeException if the text cannot be decrypted; the model wraps it in an
     *     {@code InvalidCastException} with this exception as cause
     */
    String decrypt(String cipher);

    /**
     * Reports whether stored values may be encrypted under keys other than the current one.
     *
     * <p>While this returns {@code true}, an encrypted attribute whose stored text changed is
     * dirty even when the decrypted value is the same, so the next save re-encrypts it under the
     * current key. An encrypted JSON value that was read is written back on the next dirty check,
     * so it also turns dirty.
     *
     * @implSpec The default returns {@code false}.
     *
     * @return {@code true} if earlier keys are still accepted by {@link #decrypt}
     */
    default boolean hasPreviousKeys() {
        return false;
    }
}
