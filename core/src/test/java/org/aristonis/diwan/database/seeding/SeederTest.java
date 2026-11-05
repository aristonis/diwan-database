package org.aristonis.diwan.database.seeding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.manager.FakeConnector;
import org.aristonis.diwan.database.manager.ManagerFixtures;
import org.junit.jupiter.api.Test;

class SeederTest {

    private final List<String> log = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<Connection> seen = new ArrayList<>();

    /** Records its class name and the connection it was given. */
    private abstract class Recording extends Seeder {
        @Override
        protected void run(SeedContext context) {
            log.add(getClass().getSimpleName());
            seen.add(context.connection());
            body(context);
        }

        void body(SeedContext context) {
        }
    }

    private final class Leaf extends Recording {
    }

    private final class OtherLeaf extends Recording {
    }

    private final class Parent extends Recording {
        @Override
        void body(SeedContext context) {
            context.call(Leaf::new, OtherLeaf::new);
        }
    }

    private final class OnceTwice extends Recording {
        @Override
        void body(SeedContext context) {
            context.callOnce(Leaf::new).callOnce(Leaf::new);
        }
    }

    private final class CallTwice extends Recording {
        @Override
        void body(SeedContext context) {
            context.call(Leaf::new).call(Leaf::new);
        }
    }

    private final class CycleA extends Recording {
        @Override
        void body(SeedContext context) {
            context.call(CycleB::new);
        }
    }

    private final class CycleB extends Recording {
        @Override
        void body(SeedContext context) {
            context.call(CycleA::new);
        }
    }

    private final class SelfCall extends Recording {
        @Override
        void body(SeedContext context) {
            context.call(SelfCall::new);
        }
    }

    private final class Boom extends Recording {
        @Override
        void body(SeedContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private final class WritesThenBooms extends Recording {
        @Override
        void body(SeedContext context) {
            context.call(Leaf::new, Boom::new);
        }
    }

    private final class Silent extends Recording {
        @Override
        void body(SeedContext context) {
            context.callSilent(Leaf::new);
        }
    }

    private final class RecordingListener implements SeederListener {
        @Override
        public void started(Class<?> seeder) {
            events.add("started " + seeder.getSimpleName());
        }

        @Override
        public void finished(Class<?> seeder, Duration elapsed) {
            assertTrue(!elapsed.isNegative());
            events.add("finished " + seeder.getSimpleName());
        }

        @Override
        public void failed(Class<?> seeder, Throwable failure) {
            events.add("failed " + seeder.getSimpleName() + " " + failure.getMessage());
        }
    }

    private DatabaseManager plainManager() {
        return ManagerFixtures.manager(new FakeConnector("fake"), "main", "other");
    }

    private DatabaseManager transactionalManager() {
        FakeConnector connector = new FakeConnector("fake")
                .connectionFactory(config -> recordingConnection(config.name(), log));
        return ManagerFixtures.manager(connector, "main", "other");
    }

    @Test
    void callRunsTheSeedersInOrderInsideTheParent() {
        plainManager().seed(Parent::new);

        assertEquals(Arrays.asList("Parent", "Leaf", "OtherLeaf"), log);
    }

    @Test
    void callOnceSkipsASeederThatAlreadyRanInTheSameRun() {
        plainManager().seed(OnceTwice::new);

        assertEquals(Arrays.asList("OnceTwice", "Leaf"), log);
    }

    @Test
    void callOnceRunsAgainInASecondRun() {
        DatabaseManager manager = plainManager();

        manager.seed(OnceTwice::new);
        manager.seed(OnceTwice::new);

        assertEquals(Arrays.asList("OnceTwice", "Leaf", "OnceTwice", "Leaf"), log);
    }

    @Test
    void plainCallRunsTheSameSeederEachTime() {
        plainManager().seed(CallTwice::new);

        assertEquals(Arrays.asList("CallTwice", "Leaf", "Leaf"), log);
    }

    @Test
    void aCycleIsDetectedAndNamesTheChain() {
        DatabaseManager manager = plainManager();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.seed(CycleA::new));

        assertEquals("Seeder cycle: CycleA -> CycleB -> CycleA", failure.getMessage());
    }

    @Test
    void aSeederCallingItselfIsACycle() {
        DatabaseManager manager = plainManager();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.seed(SelfCall::new));

        assertEquals("Seeder cycle: SelfCall -> SelfCall", failure.getMessage());
    }

    @Test
    void theListenerSeesStartAndFinishInOrder() {
        plainManager().seed(Parent::new, SeedOptions.defaults().listener(new RecordingListener()));

        assertEquals(Arrays.asList("started Parent", "started Leaf", "finished Leaf", "started OtherLeaf",
                "finished OtherLeaf", "finished Parent"), events);
    }

    @Test
    void callSilentHidesTheSeederFromTheListener() {
        plainManager().seed(Silent::new, SeedOptions.defaults().listener(new RecordingListener()));

        assertEquals(Arrays.asList("Silent", "Leaf"), log);
        assertEquals(Arrays.asList("started Silent", "finished Silent"), events);
    }

    @Test
    void aFailurePropagatesAndTheListenerSeesItInnermostFirst() {
        DatabaseManager manager = plainManager();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.seed(WritesThenBooms::new, SeedOptions.defaults().listener(new RecordingListener())));

        assertEquals("boom", failure.getMessage());
        assertEquals(Arrays.asList("started WritesThenBooms", "started Leaf", "finished Leaf", "started Boom",
                "failed Boom boom", "failed WritesThenBooms boom"), events);
        assertEquals(Arrays.asList("WritesThenBooms", "Leaf", "Boom"), log);
    }

    @Test
    void withoutATransactionEarlierWorkStaysAndNoTransactionIsOpened() {
        DatabaseManager manager = transactionalManager();

        assertThrows(IllegalStateException.class, () -> manager.seed(WritesThenBooms::new));

        assertEquals(Arrays.asList("WritesThenBooms", "Leaf", "Boom"), log);
        assertTrue(!log.contains("begin main"));
        assertSame(manager.connection(), seen.get(0));
    }

    @Test
    void inATransactionEverySeederGetsTheTransactionsConnection() {
        DatabaseManager manager = transactionalManager();

        manager.seed(Parent::new, SeedOptions.defaults().inTransaction(true));

        Connection root = manager.connection("main");
        assertEquals(3, seen.size());
        assertNotSame(root, seen.get(0));
        assertSame(seen.get(0), seen.get(1));
        assertSame(seen.get(0), seen.get(2));
        assertEquals(Arrays.asList("begin main", "Parent", "Leaf", "OtherLeaf", "commit main"), log);
    }

    @Test
    void inATransactionTheQueryAndSchemaAreBoundToTheTransactionsConnection() {
        DatabaseManager manager = transactionalManager();
        List<Connection> bound = new ArrayList<>();
        Seeder probe = new Seeder() {
            @Override
            protected void run(SeedContext context) {
                bound.add(context.connection());
                bound.add(context.query().connection());
                bound.add(context.schema().connection());
            }
        };

        manager.seed(() -> probe, SeedOptions.defaults().inTransaction(true));

        assertNotSame(manager.connection("main"), bound.get(0));
        assertSame(bound.get(0), bound.get(1));
        assertSame(bound.get(0), bound.get(2));
    }

    @Test
    void aFailureInATransactionRollsBackAndPropagates() {
        DatabaseManager manager = transactionalManager();

        assertThrows(IllegalStateException.class,
                () -> manager.seed(WritesThenBooms::new, SeedOptions.defaults().inTransaction(true)));

        assertEquals(Arrays.asList("begin main", "WritesThenBooms", "Leaf", "Boom", "rollback main"), log);
    }

    @Test
    void seedsTheConnectionNamedInTheOptions() {
        DatabaseManager manager = plainManager();

        manager.seed(Leaf::new, SeedOptions.defaults().connection("other"));

        assertSame(manager.connection("other"), seen.get(0));
    }

    @Test
    void seedsTheManagersCurrentDefaultWhenNoNameIsGiven() {
        DatabaseManager manager = plainManager();

        manager.usingConnection("other", () -> manager.seed(Leaf::new));

        assertSame(manager.connection("other"), seen.get(0));
    }

    @Test
    void theContextExposesTheManager() {
        DatabaseManager manager = plainManager();
        List<DatabaseManager> found = new ArrayList<>();
        Supplier<Seeder> root = () -> new Seeder() {
            @Override
            protected void run(SeedContext context) {
                found.add(context.manager());
            }
        };

        manager.seed(root);

        assertSame(manager, found.get(0));
    }

    @Test
    void aSupplierReturningNullFails() {
        DatabaseManager manager = plainManager();
        Supplier<Seeder> nothing = () -> null;

        assertThrows(NullPointerException.class, () -> manager.seed(nothing));
    }

    @Test
    void optionsAreImmutableAndDefaultToNoTransactionNoConnection() {
        SeedOptions defaults = SeedOptions.defaults();
        SeedOptions changed = defaults.connection("x").inTransaction(true);

        assertTrue(!defaults.isInTransaction());
        assertTrue(!defaults.connection().isPresent());
        assertTrue(changed.isInTransaction());
        assertEquals("x", changed.connection().get());
        assertSame(SeederListener.NONE, defaults.listener());
    }

    /** A stand-in connection that logs transactions and hands a distinct connection to the callback. */
    private static Connection recordingConnection(String name, List<String> log) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getName":
                            return name;
                        case "getTablePrefix":
                            return "";
                        case "transaction":
                            return transact(name, log, (TransactionCallback<?>) args[0]);
                        case "toString":
                            return "recording " + name;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static Object transact(String name, List<String> log, TransactionCallback<?> callback) {
        Connection inner = recordingConnection(name + "-tx", new ArrayList<>());
        log.add("begin " + name);
        try {
            Object result = callback.run(inner);
            log.add("commit " + name);
            return result;
        } catch (RuntimeException failure) {
            log.add("rollback " + name);
            throw failure;
        }
    }
}
