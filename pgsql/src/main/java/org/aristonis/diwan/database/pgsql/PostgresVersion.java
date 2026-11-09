package org.aristonis.diwan.database.pgsql;

import org.aristonis.diwan.database.Connection;

/** Reads the PostgreSQL server version, for the checks that depend on it. */
final class PostgresVersion {

    private PostgresVersion() {
    }

    /**
     * Reads the server version with {@code show server_version}.
     *
     * @param connection the connection to read with, never {@code null}
     * @return the version text, such as {@code 16.3}, never {@code null}
     * @throws IllegalStateException if the server reports no version
     */
    static String of(Connection connection) {
        return connection.scalar("show server_version").map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("The server did not report server_version"));
    }
}
