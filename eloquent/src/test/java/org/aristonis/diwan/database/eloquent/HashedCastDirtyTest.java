package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.HashedCastTest.stored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.aristonis.diwan.database.eloquent.HashedCastTest.Vault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dirty checks of the hashed cast, driven through the dirty ladder with the fake hasher. */
class HashedCastDirtyTest {

    private static final String PLAIN = "plain-secret-text";

    private FakeHasher fake;
    private Vault model;

    @BeforeEach
    void setUp() {
        fake = new FakeHasher();
        Vault.hasher = fake;
        model = new Vault();
    }

    private Object rawOf(String value) {
        Vault vault = new Vault();
        vault.setAttribute("password", value);
        return vault.getRawAttribute("password");
    }

    private boolean clean(Object original, Object current) {
        Map<String, Object> attributes = model.getAttributes();
        return DirtyLadder.equivalent(model, "password", true, original, current,
                model.getCasts().get("password"), attributes);
    }

    @Test
    void settingTheSamePlainAgainMakesItDirty() {
        Object original = rawOf(PLAIN);
        Object current = rawOf(PLAIN);
        Vault vault = stored("password", original);

        vault.setAttribute("password", PLAIN);

        assertNotEquals(original, current);
        assertFalse(clean(original, current));
        assertTrue(vault.getDirty().containsKey("password"));
    }

    @Test
    void settingTheStoredHashStaysClean() {
        Object hash = rawOf(PLAIN);
        Vault vault = stored("password", hash);

        vault.setAttribute("password", hash);

        assertTrue(clean(hash, hash));
        assertTrue(vault.getDirty().isEmpty());
        assertEquals(hash, vault.getRawAttribute("password"));
    }

    @Test
    void readingNeverCallsTheHasher() {
        Vault vault = stored("password", rawOf(PLAIN));
        FakeHasher untouched = new FakeHasher();
        Vault.hasher = untouched;

        vault.getAttribute("password");
        vault.isDirty();

        assertEquals(0, untouched.hashCalls);
        assertEquals(0, untouched.isHashedCalls);
        assertEquals(0, untouched.isCurrentCalls);
    }

    @Test
    void aNullOriginalIsDirtyForANewHash() {
        assertFalse(clean(null, rawOf(PLAIN)));
    }
}
