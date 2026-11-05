package org.aristonis.diwan.database.manager;

/** Builds configs and managers for the manager and seeding tests. */
public final class ManagerFixtures {

    /** The driver name every fixture connection uses. */
    public static final String DRIVER = "fake";

    private ManagerFixtures() {
    }

    /** Builds a config whose first name is the default, every connection on the fake driver. */
    public static DatabaseConfig config(String... names) {
        DatabaseConfig.Builder builder = DatabaseConfig.builder().defaultConnection(names[0]);
        for (String name : names) {
            builder.connection(ConnectionConfig.builder(name).driver(DRIVER).build());
        }
        return builder.build();
    }

    /** Builds a manager over the named connections and one connector. */
    public static DatabaseManager manager(FakeConnector connector, String... names) {
        return DatabaseManager.builder().config(config(names)).connector(connector).build();
    }
}
