package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.EncryptedCastTest.longs;
import static org.aristonis.diwan.database.eloquent.EncryptedCastTest.map;
import static org.aristonis.diwan.database.eloquent.EncryptedCastTest.stored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EncryptedCastTest.Vault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dirty checks of the encrypted casts, driven through the dirty ladder with the fake encrypter. */
class EncryptedCastDirtyTest {

    /** One encrypted form with a value, an equal value written another way, and a different value. */
    private record Form(String key, Object value, Object sameValue, Object otherValue) {
    }

    private static final List<Form> FORMS = Arrays.asList(
            new Form("secret", "alpha", "alpha", "beta"),
            new Form("list", longs(1, 2), longs(1, 2), longs(2, 1)),
            new Form("doc", map("a", 1L, "b", 2L), map("b", 2L, "a", 1L), map("a", 1L, "b", 3L)),
            new Form("tags", longs(3, 4), longs(3, 4), longs(4, 3)),
            new Form("opts", map("a", 1L, "b", 2L), map("b", 2L, "a", 1L), map("a", 1L, "b", 3L)));

    private FakeEncrypter fake;
    private Vault model;

    @BeforeEach
    void setUp() {
        fake = new FakeEncrypter();
        Vault.encrypter = fake;
        model = new Vault();
    }

    private Object rawOf(String key, Object value) {
        Vault vault = new Vault();
        vault.setAttribute(key, value);
        return vault.getRawAttribute(key);
    }

    private boolean clean(String key, Object original, Object current) {
        Map<String, Object> attributes = model.getAttributes();
        return DirtyLadder.equivalent(model, key, true, original, current, model.getCasts().get(key), attributes);
    }

    @Test
    void rewritingTheSameValueIsCleanForEveryForm() {
        for (Form form : FORMS) {
            Object original = rawOf(form.key(), form.value());
            Object current = rawOf(form.key(), form.sameValue());

            assertFalse(original.equals(current), form.key());
            assertTrue(clean(form.key(), original, current), form.key());
        }
    }

    @Test
    void aDifferentValueIsDirtyForEveryForm() {
        for (Form form : FORMS) {
            Object original = rawOf(form.key(), form.value());
            Object current = rawOf(form.key(), form.otherValue());

            assertFalse(clean(form.key(), original, current), form.key());
        }
    }

    @Test
    void numbersThatAreEqualAsJsonAreClean() {
        for (String key : Arrays.asList("doc", "opts")) {
            Object original = rawOf(key, map("n", 1L));
            Object current = rawOf(key, map("n", 1.0));

            assertTrue(clean(key, original, current), key);
        }
    }

    @Test
    void listOrderChangesTheAttribute() {
        Object original = rawOf("list", longs(1, 2, 3));
        Object current = rawOf("list", longs(3, 2, 1));

        assertFalse(clean("list", original, current));
    }

    @Test
    void withPreviousKeysRewritingTheSameValueIsDirtyForEveryForm() {
        fake.hasPreviousKeys = true;

        for (Form form : FORMS) {
            Object original = rawOf(form.key(), form.value());
            Object current = rawOf(form.key(), form.value());

            assertFalse(clean(form.key(), original, current), form.key());
        }
    }

    @Test
    void withPreviousKeysIdenticalCipherTextIsStillClean() {
        fake.hasPreviousKeys = true;

        for (Form form : FORMS) {
            Object raw = rawOf(form.key(), form.value());

            assertTrue(clean(form.key(), raw, raw), form.key());
        }
    }

    @Test
    void aDecryptFailureInTheCompareCountsAsDirtyWithoutThrowing() {
        Object original = rawOf("secret", "alpha");
        Object current = rawOf("secret", "alpha");
        fake.decryptFailure = new IllegalArgumentException("bad key");

        assertFalse(clean("secret", original, current));
    }

    @Test
    void mutatingAReadMapBackToItsContentLeavesTheModelClean() {
        Vault vault = stored("opts", fake.encrypt("{\"a\":1}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> opts = (Map<String, Object>) vault.getAttribute("opts");

        opts.put("b", 2L);
        opts.remove("b");

        assertFalse(vault.isDirty());
        assertEquals(map("a", 1L), vault.getAttribute("opts"));
    }

    @Test
    void withPreviousKeysAReadJsonValueTurnsDirty() {
        Vault vault = stored("opts", fake.encrypt("{\"a\":1}"), "secret", fake.encrypt("alpha"));
        fake.hasPreviousKeys = true;

        vault.getAttribute("opts");
        vault.getAttribute("secret");

        assertTrue(vault.isDirty("opts"));
        assertFalse(vault.isDirty("secret"));
    }
}
