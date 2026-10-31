/**
 * Migrations: schema changes written as classes, and the table that records which ones ran.
 *
 * <p>An application lists its {@link org.aristonis.photisma.database.migrations.Migration}
 * instances explicitly; nothing is found by scanning the classpath, which keeps the code working on
 * Android and with code shrinkers. A
 * {@link org.aristonis.photisma.database.migrations.MigrationRepository} keeps the names of the
 * migrations that ran and the batch each ran in.
 */
package org.aristonis.photisma.database.migrations;
