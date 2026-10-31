/**
 * Android support: a driver over Android's own {@code SQLiteDatabase}, for Android 9 (API 28)
 * and later, where JDBC is not available.
 *
 * <p>Use {@link org.aristonis.diwan.database.android.AndroidSqliteDriver#of} with
 * {@code Connection.builder().driver(...)}, and the SQLite schema grammar for migrations.
 */
package org.aristonis.diwan.database.android;
