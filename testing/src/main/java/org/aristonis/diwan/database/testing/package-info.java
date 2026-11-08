/**
 * Test support: a JUnit 5 extension that gives every test a clean database, and assertions on
 * database rows and models.
 *
 * <p>{@link org.aristonis.diwan.database.testing.RefreshDatabase} migrates once per run and then
 * either rolls each test back or empties the tables after it.
 * {@link org.aristonis.diwan.database.testing.DatabaseAssertions} has no JUnit dependency and works
 * from any test framework.
 *
 * <p>JUnit and the eloquent module are compile-time dependencies of this module only: an
 * application that does not use the extension needs no JUnit on its class path, and one that does
 * not assert on models needs no eloquent. The extension needs JUnit 5.10 or later. Production code belongs in the other modules, not here.
 *
 * <p>Rollback mode keeps a test's data in one transaction on the test thread only; work on other
 * threads, or a test under {@code @Timeout(threadMode = SEPARATE_THREAD)}, commits for real. Truncate
 * mode refuses parallel execution. {@code dropAllTables} on PostgreSQL leaves types, sequences and
 * functions, so migrations drop their own or use a dedicated schema.
 */
package org.aristonis.diwan.database.testing;
