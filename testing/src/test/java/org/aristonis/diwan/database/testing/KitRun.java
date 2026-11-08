package org.aristonis.diwan.database.testing;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Runs fixture test classes through the Jupiter engine, so the root store of one run is shared by
 * them, as it is by the classes of a real run. A fixture is enabled only while {@link #run} runs.
 */
final class KitRun {

    private static volatile boolean active;

    private KitRun() {
    }

    /** Condition method of the fixture classes. */
    static boolean isActive() {
        return active;
    }

    static EngineExecutionResults run(Class<?>... fixtures) {
        return run(false, fixtures);
    }

    /** Runs the fixtures with every class and method in parallel by default. */
    static EngineExecutionResults runParallel(Class<?>... fixtures) {
        return run(true, fixtures);
    }

    private static EngineExecutionResults run(boolean isParallel, Class<?>... fixtures) {
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (Class<?> fixture : fixtures) {
            selectors.add(selectClass(fixture));
        }
        active = true;
        try {
            return EngineTestKit.engine("junit-jupiter")
                    .configurationParameter("junit.jupiter.execution.parallel.enabled", String.valueOf(isParallel))
                    .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                    .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                    .selectors(selectors.toArray(new DiscoverySelector[0])).execute();
        } finally {
            active = false;
        }
    }
}
