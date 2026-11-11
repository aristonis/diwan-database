package org.aristonis.diwan.database.testing;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * The state of template mode for one root data source and one migration list, kept in the root
 * store of the JUnit run so every class of the run shares it.
 *
 * <p>The first class to need it builds the template and sweeps leftovers; later classes find it.
 * Each test then gets its own copy and a manager on it. Everything sent over the run's single root
 * connection is serialised on that connection's monitor, whichever template sends it;
 * the tests themselves run concurrently.
 * One root data source with one migration list takes one set of options for the whole run: a
 * class that asks for other options is refused.
 */
// CloseableResource is deprecated in JUnit 6, but JUnit 5.10, the oldest supported, closes only it.
@SuppressWarnings("deprecation")
final class TemplateDatabases implements CloseableResource {

    private final TemplateSettings settings;
    private final TemplateCopies copies;
    private final RunConnection run;
    private final AtomicLong counter = new AtomicLong();

    private TemplateDatabases(TemplateSettings settings, TemplateCopies copies, RunConnection run) {
        this.settings = settings;
        this.copies = copies;
        this.run = run;
    }

    /**
     * Prepares the run: root connection, template and sweep.
     *
     * @param context the context of the class, never {@code null}
     * @param settings the extension's settings, never {@code null}
     * @throws IllegalStateException if the target is refused or the template cannot be built
     */
    static void beforeAll(ExtensionContext context, TemplateSettings settings) {
        runOf(context, settings);
    }

    /**
     * Gives the test its own copy and keeps it in the extension's own store.
     *
     * @param context the context of the test, never {@code null}
     * @param own the extension's per-instance store of the test, never {@code null}
     * @param settings the extension's settings, never {@code null}
     * @throws IllegalStateException if the target is refused, the template cannot be built or the
     *     copy cannot be made or opened
     */
    static void beforeEach(ExtensionContext context, Store own, TemplateSettings settings) {
        own.put(TestDatabase.KEY, runOf(context, settings).open());
    }

    /**
     * Closes the test's database and removes its copy. A failure is logged, never thrown.
     *
     * @param own the extension's per-instance store of the test, never {@code null}
     */
    static void afterEach(Store own) {
        TestDatabase database = own.remove(TestDatabase.KEY, TestDatabase.class);
        if (database != null) {
            database.close();
        }
    }

    /**
     * Returns the query factory of the test's own database.
     *
     * @param own the extension's per-instance store of the test, never {@code null}
     * @return the factory, never {@code null}
     * @throws ParameterResolutionException if the test has no database, as before its set-up ran
     */
    static QueryFactory queryOf(Store own) {
        TestDatabase database = own.get(TestDatabase.KEY, TestDatabase.class);
        if (database == null) {
            throw new ParameterResolutionException("Template mode has no database for this test yet: parameters"
                    + " resolve from a test method or a beforeEach method, not from a constructor or beforeAll");
        }
        return database.query();
    }

    private static TemplateDatabases runOf(ExtensionContext context, TemplateSettings settings) {
        Store store = context.getRoot().getStore(RefreshDatabase.NAMESPACE);
        RunKey key = new RunKey(settings.root(), "template:" + settings.name().template());
        synchronized (RefreshDatabase.MIGRATION_LOCK) {
            TemplateDatabases known = store.get(key, TemplateDatabases.class);
            if (known != null) {
                if (!known.settings.hasSameOptions(settings)) {
                    throw new IllegalStateException("Template '" + settings.name().template() + "' was already set up"
                            + " in this run with other options; use one set of options per database in a run");
                }
                return known;
            }
            RunConnection run = runConnection(store, settings);
            TemplateDatabases created = start(settings, run);
            store.put(key, created);
            return created;
        }
    }

    private static RunConnection runConnection(Store store, TemplateSettings settings) {
        RunKey key = new RunKey(settings.root(), "run-connection");
        RunConnection known = store.get(key, RunConnection.class);
        if (known != null) {
            return known;
        }
        RunConnection opened = RunConnection.open(settings.root());
        store.put(key, opened);
        return opened;
    }

    private static TemplateDatabases start(TemplateSettings settings, RunConnection run) {
        TemplateCopies copies = settings.copies().create(settings, run);
        try {
            synchronized (run.monitor()) {
                copies.prepare(settings.perDatabase(),
                        connection -> RefreshDatabase.migrate(settings.schema(), settings.migrations(), connection));
                copies.sweep();
            }
        } catch (RuntimeException e) {
            closeAfterFailure(copies, e);
            throw e;
        }
        return new TemplateDatabases(settings, copies, run);
    }

    private static void closeAfterFailure(TemplateCopies copies, RuntimeException failure) {
        try {
            copies.close();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private TestDatabase open() {
        long n = counter.incrementAndGet();
        String copy;
        synchronized (run.monitor()) {
            copy = copies.createCopy(n);
        }
        DatabaseManager manager = null;
        try {
            Function<String, DatabaseManager> perDatabase = settings.perDatabase();
            manager = perDatabase.apply(copy);
            if (manager == null) {
                throw new IllegalStateException("perDatabase returned null for the database '" + copy + "'");
            }
            copies.afterOpen(copy, manager);
            return new TestDatabase(copy, manager, this::dropCopy);
        } catch (RuntimeException e) {
            new TestDatabase(copy, manager, this::dropCopy).close();
            throw e;
        }
    }

    private void dropCopy(String copy) {
        synchronized (run.monitor()) {
            copies.dropCopy(copy);
        }
    }

    /**
     * Ends the run's claim on the engine.
     *
     * @throws IllegalStateException if the copies cannot close
     */
    @Override
    public void close() {
        synchronized (run.monitor()) {
            copies.close();
        }
    }
}
