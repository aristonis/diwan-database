/**
 * Reads configuration values through one small interface, so the same code runs on a server, on
 * Android and in a test.
 *
 * <p>{@link org.aristonis.diwan.database.config.ConfigSource} is the port. The adapters are
 * {@link org.aristonis.diwan.database.config.MapConfigSource} for values written in code,
 * {@link org.aristonis.diwan.database.config.PropertiesConfigSource} for {@code .properties} files,
 * streams and classpath resources, {@link org.aristonis.diwan.database.config.EnvConfigSource} for
 * environment variables, and {@link org.aristonis.diwan.database.config.LayeredConfigSource} to put
 * several of them in priority order. Keys are dot-separated, such as
 * {@code database.connections.main.host}.
 *
 * <p>This package does not hold the typed database configuration built from these values, nor any
 * code that opens a connection. No adapter prints a value from {@code toString}, because values can
 * be passwords.
 */
package org.aristonis.diwan.database.config;
