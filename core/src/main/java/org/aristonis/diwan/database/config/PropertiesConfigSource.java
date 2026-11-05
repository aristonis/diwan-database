package org.aristonis.diwan.database.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * A configuration source loaded once from {@code .properties} text, read as UTF-8.
 *
 * <p>The three factories cover a file, an open stream such as an Android asset, and a classpath
 * resource. The text is read completely when the factory runs; the source holds no file or stream
 * afterwards. Instances are immutable and safe to share between threads.
 */
public final class PropertiesConfigSource implements ConfigSource {

    /** The loaded entries. */
    private final MapConfigSource entries;

    private PropertiesConfigSource(MapConfigSource entries) {
        this.entries = entries;
    }

    /**
     * Loads a {@code .properties} file.
     *
     * @param path the file, never {@code null}
     * @return the loaded source
     * @throws NullPointerException if {@code path} is {@code null}
     * @throws UncheckedIOException if the file is missing or unreadable; the message names the path
     */
    public static PropertiesConfigSource of(Path path) {
        Objects.requireNonNull(path, "path");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config file [" + path + "]", e);
        }
    }

    /**
     * Loads {@code .properties} text from a stream.
     *
     * <p>The caller owns the stream: this method reads it to the end and does not close it.
     *
     * @param stream the stream positioned at the start of the text, never {@code null}
     * @return the loaded source
     * @throws NullPointerException if {@code stream} is {@code null}
     * @throws UncheckedIOException if reading fails
     */
    public static PropertiesConfigSource of(InputStream stream) {
        Objects.requireNonNull(stream, "stream");
        try {
            return load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config stream", e);
        }
    }

    /**
     * Loads a {@code .properties} resource from the classpath.
     *
     * <p>The context class loader is tried first, then the loader of this library. A leading slash
     * in the name is ignored.
     *
     * @param resource the resource name such as {@code diwan.properties}, never {@code null}
     * @return the loaded source
     * @throws NullPointerException if {@code resource} is {@code null}
     * @throws UncheckedIOException if the resource is not found or cannot be read; the message
     *     names the resource
     */
    public static PropertiesConfigSource fromClasspath(String resource) {
        Objects.requireNonNull(resource, "resource");
        String name = resource.startsWith("/") ? resource.substring(1) : resource;
        try (InputStream stream = open(name)) {
            if (stream == null) {
                throw new FileNotFoundException(name);
            }
            return load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config resource [" + name + "]", e);
        }
    }

    private static InputStream open(String name) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream stream = context == null ? null : context.getResourceAsStream(name);
        return stream != null ? stream : PropertiesConfigSource.class.getClassLoader().getResourceAsStream(name);
    }

    private static PropertiesConfigSource load(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        Map<String, String> copy = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            copy.put(name, properties.getProperty(name));
        }
        return new PropertiesConfigSource(MapConfigSource.of(copy));
    }

    /**
     * Returns the value stored under a key.
     *
     * @param key the full key, never {@code null}
     * @return the value, or empty when the key is not set
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Override
    public Optional<String> get(String key) {
        return entries.get(key);
    }

    /**
     * Returns the names one level below a prefix.
     *
     * @param prefix the dot-separated prefix, empty for the top level, never {@code null}
     * @return the direct child segments in natural string order, unmodifiable
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    @Override
    public Set<String> keys(String prefix) {
        return entries.keys(prefix);
    }

    /**
     * Describes the source without its values.
     *
     * @return the class name and the number of entries
     */
    @Override
    public String toString() {
        return "PropertiesConfigSource" + entries.toString().substring("MapConfigSource".length());
    }
}
