package org.aristonis.diwan.database.eloquent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.aristonis.diwan.database.eloquent.casts.AttributeEncrypter;

/**
 * Test encrypter: reversible, different on every call, never real cryptography. The cipher text is a
 * running counter, a colon and the Base64 of the plain text. The fields are open so a test can read
 * the call counts and switch the failures on.
 */
final class FakeEncrypter implements AttributeEncrypter {

    int counter;
    int encrypts;
    int decrypts;
    boolean hasPreviousKeys;
    RuntimeException encryptFailure;
    RuntimeException decryptFailure;
    boolean returnNull;
    boolean decryptReturnsNull;
    String lastEncryptInput;

    @Override
    public String encrypt(String plain) {
        encrypts++;
        lastEncryptInput = plain;
        if (encryptFailure != null) {
            throw encryptFailure;
        }
        if (returnNull) {
            return null;
        }
        counter++;
        return counter + ":" + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String cipher) {
        decrypts++;
        if (decryptFailure != null) {
            throw decryptFailure;
        }
        if (decryptReturnsNull) {
            return null;
        }
        String encoded = cipher.substring(cipher.indexOf(':') + 1);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Override
    public boolean hasPreviousKeys() {
        return hasPreviousKeys;
    }
}
