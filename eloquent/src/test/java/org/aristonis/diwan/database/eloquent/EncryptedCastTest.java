package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.AttributeEncrypter;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.eloquent.exception.JsonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The encrypted casts on a model: the hook, null handling, storage, serialization and failures. */
class EncryptedCastTest {

    /** A model with every encrypted form and an encrypter held in a static field. */
    static class Vault extends Model {
        static FakeEncrypter encrypter;

        @Override
        protected AttributeEncrypter attributeEncrypter() {
            return encrypter;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return allForms();
        }
    }

    /** The same casts with no encrypter. */
    static final class Bare extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            return allForms();
        }
    }

    /** A model with its own encrypter. */
    static final class VaultA extends Model {
        static FakeEncrypter encrypter;

        @Override
        protected AttributeEncrypter attributeEncrypter() {
            return encrypter;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("secret", Casts.of("encrypted"));
        }
    }

    /** A model with another encrypter. */
    static final class VaultB extends Model {
        static FakeEncrypter encrypter;

        @Override
        protected AttributeEncrypter attributeEncrypter() {
            return encrypter;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("secret", Casts.of("encrypted"));
        }
    }

    /** A model with a user cast that fails on every read. */
    static final class Odd extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("x", new Cast<Object>() {
                @Override
                public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
                    throw new IllegalStateException("odd");
                }

                @Override
                public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
                    return value;
                }
            });
        }
    }

    static Map<String, Cast<?>> allForms() {
        Map<String, Cast<?>> casts = new LinkedHashMap<>();
        casts.put("secret", Casts.of("encrypted"));
        casts.put("list", Casts.of("encrypted:array"));
        casts.put("doc", Casts.of("encrypted:json"));
        casts.put("tags", Casts.of("encrypted:collection"));
        casts.put("opts", Casts.of("encrypted:object"));
        return casts;
    }

    static Vault stored(Object... pairs) {
        Vault vault = new Vault();
        vault.setRawAttributes(row(pairs), true);
        return vault;
    }

    static List<Object> longs(long... values) {
        List<Object> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    static Map<String, Object> map(Object... pairs) {
        return new LinkedHashMap<>(row(pairs));
    }

    private FakeEncrypter fake;

    @BeforeEach
    void setUp() {
        fake = new FakeEncrypter();
        Vault.encrypter = fake;
    }

    @Test
    void eachModelUsesItsOwnEncrypter() {
        FakeEncrypter first = new FakeEncrypter();
        FakeEncrypter second = new FakeEncrypter();
        VaultA.encrypter = first;
        VaultB.encrypter = second;
        VaultA a = new VaultA();
        VaultB b = new VaultB();

        a.setAttribute("secret", "one");
        a.setAttribute("secret", "two");
        b.setAttribute("secret", "three");

        assertEquals(2, first.encrypts);
        assertEquals(1, second.encrypts);
        assertEquals("three", b.getAttribute("secret"));
        assertSame(first, a.getAttributeEncrypter());
        assertSame(second, b.getAttributeEncrypter());
    }

    @Test
    void aModelWithoutAnEncrypterHasNone() {
        assertNull(new Bare().getAttributeEncrypter());
    }

    @Test
    void previousKeysAreOffUnlessOverridden() {
        AttributeEncrypter plain = new AttributeEncrypter() {
            @Override
            public String encrypt(String text) {
                return text;
            }

            @Override
            public String decrypt(String text) {
                return text;
            }
        };

        assertFalse(plain.hasPreviousKeys());
    }

    @Test
    void readingWithoutAnEncrypterNamesTheModelAndKey() {
        Bare bare = new Bare();
        bare.setRawAttributes(row("secret", "1:abc"), true);

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> bare.getAttribute("secret"));

        assertEquals("encrypted", e.getCastName());
        assertEquals("secret", e.getKey());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains(Bare.class.getName()), e.getCause().getMessage());
        assertTrue(e.getCause().getMessage().contains("secret"), e.getCause().getMessage());
    }

    @Test
    void writingWithoutAnEncrypterNamesTheModelAndKey() {
        Bare bare = new Bare();

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> bare.setAttribute("secret", "x"));

        assertEquals("encrypted", e.getCastName());
        assertEquals("secret", e.getKey());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains(Bare.class.getName()), e.getCause().getMessage());
        assertTrue(e.getCause().getMessage().contains("secret"), e.getCause().getMessage());
        assertFalse(e.getMessage().contains("x]"), e.getMessage());
    }

    @Test
    void nullValuesNeedNoEncrypter() {
        Bare bare = new Bare();
        bare.setRawAttributes(row("secret", null), true);

        bare.setAttribute("doc", null);

        assertNull(bare.getAttribute("secret"));
        assertNull(bare.getAttribute("doc"));
        assertNull(bare.getRawAttribute("doc"));
    }

    @Test
    void everyFormStoresCipherTextAndReadsThePlainValue() {
        Vault vault = new Vault();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("secret", "alpha");
        values.put("list", longs(1, 2));
        values.put("doc", map("a", 1L));
        values.put("tags", longs(3, 4));
        values.put("opts", map("b", 2L));

        values.forEach(vault::setAttribute);

        values.forEach((key, value) -> {
            Object raw = vault.getRawAttribute(key);
            assertInstanceOf(String.class, raw, key);
            assertTrue(((String) raw).matches("\\d+:.+"), key + " " + raw);
            assertFalse(((String) raw).contains("alpha"), key);
            assertEquals(value, vault.getAttribute(key), key);
        });
        assertEquals(5, fake.encrypts);
    }

    @Test
    void textFormEncryptsThePlainText() {
        new Vault().setAttribute("secret", "alpha");

        assertEquals("alpha", fake.lastEncryptInput);
    }

    @Test
    void jsonFormsEncryptTheCompactJsonText() {
        Vault vault = new Vault();

        vault.setAttribute("list", longs(1, 2));
        assertEquals("[1,2]", fake.lastEncryptInput);
        vault.setAttribute("doc", map("a", 1L, "b", "x"));
        assertEquals("{\"a\":1,\"b\":\"x\"}", fake.lastEncryptInput);
        vault.setAttribute("tags", longs(3));
        assertEquals("[3]", fake.lastEncryptInput);
        vault.setAttribute("opts", map("k", true));
        assertEquals("{\"k\":true}", fake.lastEncryptInput);
    }

    @Test
    void storedCipherTextReadsAsThePlainValue() {
        Vault vault = stored(
                "secret", fake.encrypt("hello"),
                "list", fake.encrypt("[1,2]"),
                "doc", fake.encrypt("{\"a\":1}"),
                "tags", fake.encrypt("[3]"),
                "opts", fake.encrypt("{\"b\":2}"));

        assertEquals("hello", vault.getAttribute("secret"));
        assertEquals(longs(1, 2), vault.getAttribute("list"));
        assertEquals(map("a", 1L), vault.getAttribute("doc"));
        assertEquals(longs(3), vault.getAttribute("tags"));
        assertEquals(map("b", 2L), vault.getAttribute("opts"));
    }

    @Test
    void byteCipherTextIsReadAsUtf8() {
        Vault vault = stored("secret", fake.encrypt("hello").getBytes(StandardCharsets.UTF_8));

        assertEquals("hello", vault.getAttribute("secret"));
    }

    @Test
    void settingNullStoresNullAndNeverEncrypts() {
        Vault vault = new Vault();

        for (String key : Arrays.asList("secret", "list", "doc", "tags", "opts")) {
            vault.setAttribute(key, null);
            assertNull(vault.getRawAttribute(key), key);
        }

        assertEquals(0, fake.encrypts);
    }

    @Test
    void nullRawReadsNullAndNeverDecrypts() {
        Vault vault = stored("secret", null, "doc", null);

        assertNull(vault.getAttribute("secret"));
        assertNull(vault.getAttribute("doc"));
        assertEquals(0, fake.decrypts);
    }

    @Test
    void aNullFromDecryptReadsNull() {
        Vault vault = stored("secret", "1:abc");
        fake.decryptReturnsNull = true;

        assertNull(vault.getAttribute("secret"));
        assertEquals(1, fake.decrypts);
    }

    @Test
    void emptyCipherTextReachesDecrypt() {
        Vault vault = stored("secret", "");

        assertEquals("", vault.getAttribute("secret"));
        assertEquals(1, fake.decrypts);
    }

    @Test
    void toMapAndToJsonHoldThePlainValues() {
        Vault vault = new Vault();
        vault.setAttribute("secret", "alpha");
        vault.setAttribute("list", longs(1, 2));
        vault.setAttribute("doc", map("a", 1L));
        vault.setAttribute("tags", longs(3));
        vault.setAttribute("opts", map("b", 2L));
        String cipher = (String) vault.getRawAttribute("secret");

        Map<String, Object> map = vault.toMap();
        String json = vault.toJson();

        assertEquals("alpha", map.get("secret"));
        assertEquals(longs(1, 2), map.get("list"));
        assertEquals(map("a", 1L), map.get("doc"));
        assertEquals(longs(3), map.get("tags"));
        assertEquals(map("b", 2L), map.get("opts"));
        assertTrue(json.contains("\"secret\":\"alpha\""), json);
        assertTrue(json.contains("\"list\":[1,2]"), json);
        assertTrue(json.contains("\"doc\":{\"a\":1}"), json);
        assertTrue(json.contains("\"tags\":[3]"), json);
        assertTrue(json.contains("\"opts\":{\"b\":2}"), json);
        assertFalse(json.contains(cipher), json);
    }

    @Test
    void aNullEncryptedAttributeSerializesAsNull() {
        Vault vault = stored("secret", null);

        assertTrue(vault.toMap().containsKey("secret"));
        assertNull(vault.toMap().get("secret"));
        assertTrue(vault.toJson().contains("\"secret\":null"), vault.toJson());
    }

    @Test
    void anEncryptFailureOnSetIsWrappedWithTheOriginalCause() {
        IllegalArgumentException failure = new IllegalArgumentException("no key");
        fake.encryptFailure = failure;

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> new Vault().setAttribute("secret", "x"));

        assertSame(failure, e.getCause());
        assertEquals("encrypted", e.getCastName());
        assertEquals("secret", e.getKey());
    }

    @Test
    void aDecryptFailureOnGetIsWrappedWithTheOriginalCause() {
        IllegalArgumentException failure = new IllegalArgumentException("bad key");
        Vault vault = stored("secret", "1:abc");
        fake.decryptFailure = failure;

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> vault.getAttribute("secret"));

        assertSame(failure, e.getCause());
        assertEquals("encrypted", e.getCastName());
        assertEquals("secret", e.getKey());
    }

    @Test
    void decryptedTextThatIsNotJsonFails() {
        Vault vault = stored("doc", fake.encrypt("not json"));

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> vault.getAttribute("doc"));

        assertEquals("doc", e.getKey());
        assertInstanceOf(JsonException.class, e.getCause());
    }

    @Test
    void aCollectionHoldingAnObjectFailsWithTheShapeError() {
        Vault vault = stored("tags", fake.encrypt("{}"));

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> vault.getAttribute("tags"));

        assertEquals("tags", e.getKey());
        assertTrue(e.getMessage().contains("JSON array"), e.getMessage());
    }

    @Test
    void anObjectHoldingAnArrayFailsWithTheShapeError() {
        Vault vault = stored("opts", fake.encrypt("[]"));

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> vault.getAttribute("opts"));

        assertEquals("opts", e.getKey());
        assertTrue(e.getMessage().contains("JSON object"), e.getMessage());
    }

    @Test
    void aNullCipherTextFromEncryptFails() {
        fake.returnNull = true;

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> new Vault().setAttribute("secret", "x"));

        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("secret", e.getKey());
    }

    @Test
    void aFailingUserCastIsStillWrapped() {
        Odd odd = new Odd();
        odd.setRawAttributes(row("x", "v"), true);

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> odd.getAttribute("x"));

        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void anEncryptFailureOnACachedMapSurfacesFromIsDirty() {
        Vault vault = stored("doc", fake.encrypt("{\"a\":1}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) vault.getAttribute("doc");
        doc.put("b", 2L);
        fake.encryptFailure = new IllegalArgumentException("gone");

        assertThrows(InvalidCastException.class, vault::isDirty);
    }

    @Test
    void theMissingEncrypterMessageIsExact() {
        Bare bare = new Bare();

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> bare.setAttribute("secret", "x"));

        assertEquals("Model [" + Bare.class.getName()
                + "] has no attribute encrypter for encrypted attribute [secret]; override attributeEncrypter()",
                e.getCause().getMessage());
    }

    @Test
    void theNullCipherMessageIsExact() {
        fake.returnNull = true;

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> new Vault().setAttribute("secret", "x"));

        assertEquals("Encrypter for [" + Vault.class.getName() + "] returned null for encrypted attribute [secret]",
                e.getCause().getMessage());
    }

    @Test
    void anUnsupportedRawTypeFailsWithTheEncryptedCastName() {
        Vault vault = stored("secret", 42L);

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> vault.getAttribute("secret"));

        assertEquals("encrypted", e.getCastName());
        assertEquals("secret", e.getKey());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        assertEquals("column holds unsupported type Long", e.getCause().getMessage());
    }
}
