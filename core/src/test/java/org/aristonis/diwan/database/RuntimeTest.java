package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuntimeTest {

    @Test
    void runsOnTheSupportedBaselineOrNewer() {
        int feature = Runtime.version().feature();

        assertTrue(feature >= 17, "running on Java " + feature);
    }
}
