package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;

/** Names of the template-mode databases: stable per migration list, safe as identifiers, short. */
class TemplateNameTest {

    private static final Pattern SAFE = Pattern.compile("[a-z0-9_]+");
    private static final String RUN = "a1b2c3";

    @Test
    void sameMigrationsGiveTheSameTemplate() {
        TemplateName first = TemplateName.of(Fixtures.base());
        TemplateName second = TemplateName.of(Fixtures.base());

        assertEquals(first.hash8(), second.hash8());
        assertEquals(first.template(), second.template());
    }

    @Test
    void anotherListGivesAnotherTemplate() {
        assertNotEquals(TemplateName.of(Fixtures.base()).template(), TemplateName.of(Fixtures.withTags()).template());
    }

    @Test
    void anotherOrderGivesAnotherTemplate() {
        List<Migration> reversed = new ArrayList<>(Fixtures.base());
        Collections.reverse(reversed);

        assertNotEquals(TemplateName.of(Fixtures.base()).hash8(), TemplateName.of(reversed).hash8());
    }

    @Test
    void aChangedChecksumGivesAnotherTemplate() {
        TemplateName one = TemplateName.of(Arrays.asList(checked("create_users", "one")));
        TemplateName two = TemplateName.of(Arrays.asList(checked("create_users", "two")));

        assertNotEquals(one.hash8(), two.hash8());
    }

    @Test
    void signatureCarriesClassNameAndChecksum() {
        String signature = TemplateName.signature(Arrays.asList(checked("create_users", "abc")));

        assertTrue(signature.contains("#create_users#abc;"), signature);
    }

    @Test
    void namesAreSafeIdentifiersUnderTheLimit() {
        TemplateName name = TemplateName.of(Fixtures.base());
        long largest = Long.MAX_VALUE;

        for (String value : Arrays.asList(name.template(), name.building(RUN), name.copy(RUN, 1),
                name.copy(RUN, largest))) {
            assertTrue(SAFE.matcher(value).matches(), value);
            assertTrue(value.length() < 63, value);
        }
    }

    @Test
    void hash8IsEightHexCharacters() {
        assertTrue(TemplateName.of(Fixtures.base()).hash8().matches("[0-9a-f]{8}"));
    }

    @Test
    void runIdIsSixBase36CharactersAndStaysTheSame() {
        assertTrue(TemplateName.runId().matches("[0-9a-z]{6}"), TemplateName.runId());
        assertEquals(TemplateName.runId(), TemplateName.runId());
    }

    @Test
    void runIdOfReadsACopyName() {
        TemplateName name = TemplateName.of(Fixtures.base());

        assertEquals(Optional.of(RUN), TemplateName.runIdOf(name.copy(RUN, 42)));
    }

    @Test
    void runIdOfReadsABuildingName() {
        TemplateName name = TemplateName.of(Fixtures.base());

        assertEquals(Optional.of(RUN), TemplateName.runIdOf(name.building(RUN)));
    }

    @Test
    void runIdOfIgnoresTheFinishedTemplateAndOtherNames() {
        TemplateName name = TemplateName.of(Fixtures.base());

        assertFalse(TemplateName.runIdOf(name.template()).isPresent());
        assertFalse(TemplateName.runIdOf("postgres").isPresent());
        assertFalse(TemplateName.runIdOf("diwan_t_zzzzzzzz_a1b2c3_1").isPresent());
    }

    @Test
    void runKeyReadsTheIdAsUnsignedBase36() {
        assertEquals(0, TemplateName.runKey("000000"));
        assertEquals(35, TemplateName.runKey("00000z"));
        // "zzzzzz" is 2,176,782,335, above Integer.MAX_VALUE, so the int is the unsigned bit pattern.
        assertEquals(2_176_782_335L, Integer.toUnsignedLong(TemplateName.runKey("zzzzzz")));
    }

    @Test
    void hash32ReadsTheHashAsUnsignedHex() {
        TemplateName name = TemplateName.of(Fixtures.base());

        assertEquals(Long.parseLong(name.hash8(), 16), Integer.toUnsignedLong(name.hash32()));
    }

    @Test
    void aMalformedRunIdIsRefused() {
        TemplateName name = TemplateName.of(Fixtures.base());

        assertThrows(IllegalArgumentException.class, () -> name.building("short"));
        assertThrows(IllegalArgumentException.class, () -> name.copy("UPPER1", 1));
        assertThrows(IllegalArgumentException.class, () -> name.copy("a\";--x", 1));
        assertThrows(IllegalArgumentException.class, () -> TemplateName.runKey("a1b2"));
    }

    @Test
    void aNegativeCopyNumberIsRefused() {
        TemplateName name = TemplateName.of(Fixtures.base());

        assertThrows(IllegalArgumentException.class, () -> name.copy(RUN, -1));
    }

    @Test
    void aNullElementIsRefused() {
        assertThrows(NullPointerException.class, () -> TemplateName.of(Arrays.asList((Migration) null)));
    }

    private static Migration checked(String name, String checksum) {
        return new Migration() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void up(Schema schema) {
            }

            @Override
            public Optional<String> checksum() {
                return Optional.of(checksum);
            }
        };
    }
}
