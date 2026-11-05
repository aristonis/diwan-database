package org.aristonis.diwan.database.eloquent.relations;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Restores the global morph map around every test, so no test sees an alias another test added.
 *
 * <p>It is registered by extension auto-detection, so test classes need no annotation. The map is
 * global, so tests must not run in parallel.
 *
 * <p>The map is reset before and after every test, so a test class must register its aliases in a
 * {@code @BeforeEach} method; a registration in {@code @BeforeAll} is wiped before the first test
 * runs.
 */
public final class MorphMapReset implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        MorphMap.resetForTests();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        MorphMap.resetForTests();
    }
}
