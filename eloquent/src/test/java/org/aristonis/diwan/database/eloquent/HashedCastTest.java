package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.AttributeHasher;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The hashed cast on a model: the hook, null handling, strings only, storage, serialization and failures. */
class HashedCastTest {

    private static final String PLAIN = "plain-secret-text";
    private static final String[] ALGORITHMS = {"bcrypt", "argon"};

    /** A model with both hashed forms and a hasher held in a static field. */
    static class Vault extends Model {
        static FakeHasher hasher;

        @Override
        protected AttributeHasher attributeHasher() {
            return hasher;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return allForms();
        }
    }

    /** The same casts with no hasher. */
    static final class Bare extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            return allForms();
        }
    }

    /** A model with its own hasher. */
    static final class VaultA extends Model {
        static FakeHasher hasher;

        @Override
        protected AttributeHasher attributeHasher() {
            return hasher;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("password", Casts.of("hashed"));
        }
    }

    /** A model with another hasher. */
    static final class VaultB extends Model {
        static FakeHasher hasher;

        @Override
        protected AttributeHasher attributeHasher() {
            return hasher;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("password", Casts.of("hashed"));
        }
    }

    /** A vault that hides its hashed attribute from the output. */
    static final class HiddenVault extends Vault {
        @Override
        protected List<String> hidden() {
            return Arrays.asList("password");
        }
    }

    static Map<String, Cast<?>> allForms() {
        Map<String, Cast<?>> casts = new LinkedHashMap<>();
        casts.put("password", Casts.of("hashed"));
        casts.put("note", Casts.hashed());
        return casts;
    }

    static Vault stored(Object... pairs) {
        Vault vault = new Vault();
        vault.setRawAttributes(row(pairs), true);
        return vault;
    }

    private FakeHasher fake;

    @BeforeEach
    void setUp() {
        fake = new FakeHasher();
        Vault.hasher = fake;
    }

    private static void assertHashedFailure(InvalidCastException e, String key) {
        assertEquals("hashed", e.getCastName());
        assertEquals(key, e.getKey());
    }

    private static void assertNoLeak(InvalidCastException e, String text) {
        assertFalse(e.getMessage().contains(text), e.getMessage());
        assertFalse(String.valueOf(e.getCause().getMessage()).contains(text), String.valueOf(e.getCause()));
    }

    @Test
    void eachModelUsesItsOwnHasher() {
        VaultA.hasher = new FakeHasher();
        VaultA.hasher.algorithm = "bcrypt";
        VaultB.hasher = new FakeHasher();
        VaultB.hasher.algorithm = "argon";
        VaultA a = new VaultA();
        VaultB b = new VaultB();

        a.setAttribute("password", PLAIN);
        b.setAttribute("password", PLAIN);

        assertEquals(1, VaultA.hasher.hashCalls);
        assertEquals(1, VaultB.hasher.hashCalls);
        assertTrue(((String) a.getRawAttribute("password")).startsWith("$fake$bcrypt$"));
        assertTrue(((String) b.getRawAttribute("password")).startsWith("$fake$argon$"));
    }

    @Test
    void aModelWithoutAHasherHasNone() {
        assertNull(new Bare().getAttributeHasher());
        assertSame(fake, new Vault().getAttributeHasher());
    }

    @Test
    void specStringAndFactoryBuildTheSameCast() {
        Cast<?> fromSpec = Casts.resolve(Casts.of("hashed"));
        Cast<?> fromFactory = Casts.resolve(Casts.hashed());

        assertSame(fromSpec.getClass(), fromFactory.getClass());
        assertEquals("hashed", Casts.typeName(fromSpec));
        assertEquals("hashed", Casts.typeName(fromFactory));
    }

    @Test
    void aPlainValueIsStoredAsItsHash() {
        for (String algorithm : ALGORITHMS) {
            FakeHasher own = new FakeHasher();
            own.algorithm = algorithm;
            Vault.hasher = own;
            Vault vault = new Vault();

            vault.setAttribute("password", PLAIN);

            String raw = (String) vault.getRawAttribute("password");
            assertNotEquals(PLAIN, raw, algorithm);
            assertTrue(raw.startsWith("$fake$" + algorithm + "$"), raw);
            assertTrue(own.verify(PLAIN, raw), algorithm);
            assertEquals(1, own.hashCalls, algorithm);
            assertEquals(PLAIN, own.lastHashInput, algorithm);
        }
    }

    @Test
    void bothFormsHashTheSameWay() {
        Vault vault = new Vault();

        vault.setAttribute("note", PLAIN);

        assertTrue(fake.verify(PLAIN, (String) vault.getRawAttribute("note")));
    }

    @Test
    void readingReturnsTheStoredHash() {
        String hash = fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 3, 2);
        Vault vault = stored("password", hash);

        assertEquals(hash, vault.getAttribute("password"));
        assertEquals(0, fake.hashCalls);
        assertEquals(0, fake.isHashedCalls);
    }

    @Test
    void aNonStringValueIsRefused() {
        Object[] values = {424242, true, 3.5, Arrays.asList(1, 2), new char[] {'a', 'b'}};
        for (Object value : values) {
            Vault vault = new Vault();

            InvalidCastException e = assertThrows(InvalidCastException.class,
                    () -> vault.setAttribute("password", value), value.getClass().getName());

            assertHashedFailure(e, "password");
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains(value.getClass().getName()), e.getCause().getMessage());
            assertNoLeak(e, "424242");
            assertEquals(0, fake.hashCalls);
            assertEquals(0, fake.isHashedCalls);
            assertFalse(vault.getAttributes().containsKey("password"));
        }
    }

    @Test
    void aNonStringOnAModelWithoutAHasherFailsOnTheMissingHasher() {
        Bare bare = new Bare();

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> bare.setAttribute("password", 424242));

        assertHashedFailure(e, "password");
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void anAlreadyHashedCurrentValueIsKeptAndNotHashedAgain() {
        for (String algorithm : ALGORITHMS) {
            FakeHasher own = new FakeHasher();
            own.algorithm = algorithm;
            Vault.hasher = own;
            String hash = own.fakeFrom(PLAIN, algorithm, 10, 64, 3, 2);
            Vault vault = new Vault();

            vault.setAttribute("password", hash);

            assertEquals(hash, vault.getRawAttribute("password"), algorithm);
            assertEquals(0, own.hashCalls, algorithm);
        }
    }

    @Test
    void nullStoresNullAndNeverCallsTheHasher() {
        Vault vault = new Vault();

        vault.setAttribute("password", null);
        vault.setAttribute("note", null);

        assertNull(vault.getRawAttribute("password"));
        assertNull(vault.getRawAttribute("note"));
        assertEquals(0, fake.hashCalls);
        assertEquals(0, fake.isHashedCalls);
        assertEquals(0, fake.isCurrentCalls);
    }

    @Test
    void nullOnAModelWithoutAHasherDoesNotThrow() {
        Bare bare = new Bare();

        bare.setAttribute("password", null);

        assertNull(bare.getRawAttribute("password"));
    }

    @Test
    void aNullColumnReadsNull() {
        Vault vault = stored("password", null);

        assertNull(vault.getAttribute("password"));
        assertEquals(0, fake.hashCalls);
    }

    private String[] hashesFromAStrongerConfiguration() {
        return new String[] {
            fake.fakeFrom(PLAIN, "bcrypt", 11, 64, 3, 2),
            fake.fakeFrom(PLAIN, "bcrypt", 10, 65, 3, 2),
            fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 4, 2),
            fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 3, 3)};
    }

    private void assertRefusedAsNotCurrent(String hash, String label) {
        String original = fake.fakeFrom("other", "bcrypt", 10, 64, 3, 2);
        Vault vault = stored("password", original);

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> vault.setAttribute("password", hash), label);

        assertHashedFailure(e, "password");
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("Could not verify the hashed value's configuration.", e.getCause().getMessage(), label);
        assertNoLeak(e, hash);
        assertEquals(original, vault.getRawAttribute("password"), label);
        assertEquals(0, fake.hashCalls, label);
    }

    @Test
    void aHashFromAStrongerConfigurationIsRefused() {
        for (String hash : hashesFromAStrongerConfiguration()) {
            assertRefusedAsNotCurrent(hash, hash);
        }
    }

    @Test
    void aHashFromAWeakerConfigurationIsAccepted() {
        String[] hashes = {
            fake.fakeFrom(PLAIN, "bcrypt", 9, 64, 3, 2),
            fake.fakeFrom(PLAIN, "bcrypt", 10, 63, 3, 2),
            fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 2, 2),
            fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 3, 1)};
        for (String hash : hashes) {
            Vault vault = new Vault();

            vault.setAttribute("password", hash);

            assertEquals(hash, vault.getRawAttribute("password"), hash);
            assertEquals(0, fake.hashCalls, hash);
        }
    }

    @Test
    void aHashFromAnotherAlgorithmIsRefused() {
        String[][] pairs = {{"bcrypt", "argon"}, {"argon", "bcrypt"}, {"argon2id", "argon"}};
        for (String[] pair : pairs) {
            fake.algorithm = pair[0];

            assertRefusedAsNotCurrent(fake.fakeFrom(PLAIN, pair[1], 10, 64, 3, 2), pair[0] + "/" + pair[1]);
        }
    }

    @Test
    void writingWithoutAHasherNamesTheModelAndKey() {
        Bare bare = new Bare();

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> bare.setAttribute("password", PLAIN));

        assertHashedFailure(e, "password");
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("Model [" + Bare.class.getName()
                + "] has no attribute hasher for hashed attribute [password]; override attributeHasher()",
                e.getCause().getMessage());
        assertNoLeak(e, PLAIN);
        assertFalse(bare.getAttributes().containsKey("password"));
    }

    @Test
    void aHashFailureIsWrappedWithTheOriginalCause() {
        IllegalArgumentException failure = new IllegalArgumentException("no entropy");
        fake.hashFailure = failure;
        Vault vault = new Vault();

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> vault.setAttribute("password", PLAIN));

        assertSame(failure, e.getCause());
        assertHashedFailure(e, "password");
        assertFalse(vault.getAttributes().containsKey("password"));
    }

    @Test
    void anIsHashedFailureIsWrappedWithTheOriginalCause() {
        IllegalStateException failure = new IllegalStateException("broken check");
        fake.isHashedFailure = failure;

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> new Vault().setAttribute("password", PLAIN));

        assertSame(failure, e.getCause());
        assertHashedFailure(e, "password");
    }

    @Test
    void anIsCurrentFailureIsWrappedWithTheOriginalCause() {
        IllegalStateException failure = new IllegalStateException("broken settings");
        fake.isCurrentFailure = failure;
        String hash = fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 3, 2);

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> new Vault().setAttribute("password", hash));

        assertSame(failure, e.getCause());
        assertHashedFailure(e, "password");
    }

    @Test
    void aNullFromHashFails() {
        fake.hashReturnsNull = true;
        Vault vault = new Vault();

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> vault.setAttribute("password", PLAIN));

        assertHashedFailure(e, "password");
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("Hasher for [" + Vault.class.getName() + "] returned null for hashed attribute [password]",
                e.getCause().getMessage());
        assertFalse(vault.getAttributes().containsKey("password"));
    }

    @Test
    void aHasherThatThrowsInvalidCastExceptionIsRethrownAsIs() {
        InvalidCastException failure = new InvalidCastException(Vault.class, "password", "custom");
        fake.hashFailure = failure;

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> new Vault().setAttribute("password", PLAIN));

        assertSame(failure, e);
    }

    @Test
    void toMapAndToJsonReturnTheStoredHash() {
        String hash = fake.fakeFrom("hunter2", "bcrypt", 10, 64, 3, 2);
        Vault vault = stored("password", hash);

        assertEquals(hash, vault.toMap().get("password"));
        assertTrue(vault.toJson().contains("\"password\":\"" + hash + "\""), vault.toJson());
        assertEquals(0, fake.hashCalls);
    }

    @Test
    void aHiddenHashedAttributeIsLeftOut() {
        HiddenVault vault = new HiddenVault();
        vault.setRawAttributes(row("password", fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 3, 2)), true);

        assertFalse(vault.toMap().containsKey("password"));
        assertFalse(vault.toJson().contains("password"), vault.toJson());
    }

    @Test
    void byteRawTextIsReadAsUtf8() {
        String hash = fake.fakeFrom(PLAIN, "bcrypt", 10, 64, 3, 2);
        Vault vault = stored("password", hash.getBytes(StandardCharsets.UTF_8));

        assertEquals(hash, vault.getAttribute("password"));
    }

    @Test
    void anUnsupportedRawTypeFailsWithTheHashedCastName() {
        Vault vault = stored("password", 42L);

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> vault.getAttribute("password"));

        assertHashedFailure(e, "password");
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        assertEquals("column holds unsupported type Long", e.getCause().getMessage());
    }
}
