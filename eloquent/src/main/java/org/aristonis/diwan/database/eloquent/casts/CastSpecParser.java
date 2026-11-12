package org.aristonis.diwan.database.eloquent.casts;

import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Turns a Laravel-style cast string such as {@code decimal:2} into a built-in {@link Cast}.
 *
 * <p>The type part before the first {@code :} is trimmed and lower-cased; the argument after it is
 * kept verbatim, so {@code DateTime:yyyy-MM-dd} keeps its format, unlike Laravel. Formats are
 * {@link DateTimeFormatter} patterns, not PHP ones: {@code Y-m-d} compiles with a different
 * meaning. Each format is compiled once and probe-formatted, so a bad one fails here.
 *
 * <p>Parsed casts are cached by their exact spec text, up to {@link #CACHE_LIMIT} entries; past
 * the cap specs are parsed without caching. Failures are never cached. Safe for concurrent use.
 */
final class CastSpecParser {

    /** Most specs the cache holds. */
    static final int CACHE_LIMIT = 256;

    /** The date the {@code date:F} probe formats. */
    private static final LocalDate PROBE_DATE = LocalDate.of(1970, 1, 1);

    private static final ConcurrentMap<String, Cast<?>> CACHE = new ConcurrentHashMap<>();

    private CastSpecParser() {
    }

    /**
     * Parses a cast string, using the cache when possible.
     *
     * @param spec the cast string, such as {@code int} or {@code datetime:uuuu-MM-dd}
     * @return the built-in cast
     * @throws NullPointerException if {@code spec} is null
     * @throws IllegalArgumentException if the type is unknown or its argument is invalid
     */
    static Cast<?> parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        Cast<?> cached = CACHE.get(spec);
        if (cached != null) {
            return cached;
        }
        Cast<?> parsed = parseUncached(spec);
        if (CACHE.size() >= CACHE_LIMIT) {
            return parsed;
        }
        Cast<?> raced = CACHE.putIfAbsent(spec, parsed);
        return raced == null ? parsed : raced;
    }

    /**
     * Returns how many specs are cached; for tests.
     *
     * @return the cache size
     */
    static int cacheSize() {
        return CACHE.size();
    }

    /**
     * Builds a {@code date:F} cast after compiling and probing the pattern.
     *
     * @param pattern the {@link DateTimeFormatter} pattern
     * @return the cast
     * @throws NullPointerException if {@code pattern} is null
     * @throws IllegalArgumentException if the pattern is empty, does not compile, or cannot format
     *     a date
     */
    static DateCast date(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        DateTimeFormatter formatter = compile("date", pattern);
        try {
            formatter.format(PROBE_DATE);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Format [" + pattern + "] cannot format a date", e);
        }
        return new DateCast(pattern);
    }

    /**
     * Builds a {@code datetime:F} cast after compiling and probing the pattern.
     *
     * @param pattern the {@link DateTimeFormatter} pattern
     * @return the cast
     * @throws NullPointerException if {@code pattern} is null
     * @throws IllegalArgumentException if the pattern is empty, does not compile, or cannot format
     *     a moment
     */
    static MomentCast datetime(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        DateTimeFormatter formatter = compile("datetime", pattern).withZone(ZoneOffset.UTC);
        try {
            formatter.format(Instant.EPOCH);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Format [" + pattern + "] cannot format a moment", e);
        }
        return new MomentCast(pattern);
    }

    private static Cast<?> parseUncached(String spec) {
        int colon = spec.indexOf(':');
        String type = (colon < 0 ? spec : spec.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String argument = colon < 0 ? null : spec.substring(colon + 1);
        if (type.isEmpty()) {
            throw new IllegalArgumentException("Cast [" + spec + "] has no type");
        }
        if (argument == null) {
            return plain(type, spec);
        }
        return withArgument(type, argument, spec);
    }

    private static Cast<?> plain(String type, String spec) {
        switch (type) {
            case "int":
            case "integer":
                return new PrimitiveCasts.IntCast(type);
            case "real":
            case "float":
            case "double":
                return new PrimitiveCasts.FloatCast(type);
            case "string":
                return new PrimitiveCasts.StringCast(type);
            case "bool":
            case "boolean":
                return new PrimitiveCasts.BoolCast(type);
            case "date":
            case "immutable_date":
                return new DateCast();
            case "datetime":
            case "immutable_datetime":
                return new MomentCast(false);
            case "timestamp":
                return new MomentCast(true);
            case "instant":
                return new MomentCast();
            case "json":
            case "array":
                return new JsonCasts(type, false);
            case "collection":
                return Casts.collection();
            case "object":
                return Casts.object();
            case "decimal":
                throw new IllegalArgumentException("Cast [" + spec + "] needs a scale, as in decimal:2");
            case "custom_datetime":
            case "immutable_custom_datetime":
                throw new IllegalArgumentException("Cast [" + spec + "] needs a format");
            default:
                throw unknown(spec);
        }
    }

    private static Cast<?> withArgument(String type, String argument, String spec) {
        switch (type) {
            case "decimal":
                return decimal(argument, spec);
            case "date":
            case "immutable_date":
                return date(argument);
            case "datetime":
            case "immutable_datetime":
            case "custom_datetime":
            case "immutable_custom_datetime":
                return datetime(argument);
            case "json":
                if ("unicode".equalsIgnoreCase(argument)) {
                    return new JsonCasts("json:unicode", true);
                }
                throw new IllegalArgumentException("Cast [" + spec + "] has an unknown JSON option");
            default:
                throw unknown(spec);
        }
    }

    private static Cast<?> decimal(String argument, String spec) {
        if (argument.indexOf(',') < 0) {
            return new DecimalCast(scale(argument, spec));
        }
        String[] parts = argument.split(",", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Cast [" + spec + "] must be decimal:N, decimal:P,S or decimal:P,S,MODE");
        }
        try {
            int precision = Integer.parseInt(parts[0].trim());
            int scale = Integer.parseInt(parts[1].trim());
            RoundingMode mode = parts.length == 3
                    ? RoundingMode.valueOf(parts[2].trim().toUpperCase(Locale.ROOT))
                    : RoundingMode.UNNECESSARY;
            return new StrictDecimalCast(precision, scale, mode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cast [" + spec + "] must be decimal:P,S or decimal:P,S,MODE "
                    + "with a valid precision, scale and rounding mode");
        }
    }

    private static int scale(String argument, String spec) {
        boolean isDigits = !argument.isEmpty() && argument.length() <= 2;
        for (int i = 0; isDigits && i < argument.length(); i++) {
            isDigits = Character.isDigit(argument.charAt(i)) && argument.charAt(i) < 0x80;
        }
        if (!isDigits) {
            throw new IllegalArgumentException(
                    "Cast [" + spec + "] needs a scale of 0 to " + DecimalCast.MAX_SCALE);
        }
        return Integer.parseInt(argument);
    }

    private static DateTimeFormatter compile(String type, String pattern) {
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Cast [" + type + ":] has an empty format");
        }
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format [" + pattern + "] does not compile", e);
        }
    }

    private static IllegalArgumentException unknown(String spec) {
        return new IllegalArgumentException("Unknown cast [" + spec + "]");
    }
}
