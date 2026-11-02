package org.aristonis.diwan.database.eloquent.casts;

import java.util.Objects;

/**
 * Factory for the built-in casts.
 *
 * <p>{@link #of(String)} takes a Laravel-style cast string and checks it only when the model first
 * resolves its casts; the typed factories check their arguments at once. Cast strings are listed
 * on {@link #of(String)}.
 */
public final class Casts {

    private Casts() {
    }

    /**
     * Returns a cast for a Laravel-style cast string, parsed later.
     *
     * <p>Accepted types, matched after trimming and lower-casing the part before the first
     * {@code :}: {@code int}, {@code integer}, {@code real}, {@code float}, {@code double},
     * {@code string}, {@code bool}, {@code boolean}, {@code decimal:N} (N 0 to 30), {@code date},
     * {@code date:F}, {@code immutable_date[:F]}, {@code datetime}, {@code datetime:F},
     * {@code immutable_datetime[:F]}, {@code custom_datetime:F}, {@code timestamp}, {@code json},
     * {@code array}, {@code json:unicode}. The argument is kept as written. A format {@code F} is a
     * {@link java.time.format.DateTimeFormatter} pattern; a PHP format such as {@code Y-m-d}
     * compiles with a different meaning.
     *
     * @param spec the cast string
     * @return a placeholder the model resolves when it first reads its casts
     * @throws NullPointerException if {@code spec} is null
     */
    public static Cast<?> of(String spec) {
        return new SpecCast(spec);
    }

    /**
     * Returns the {@code int} cast.
     *
     * @return a cast reading {@code Long}
     */
    public static Cast<Object> integer() {
        return new PrimitiveCasts.IntCast("int");
    }

    /**
     * Returns the {@code float} cast.
     *
     * @return a cast reading {@code Double}
     */
    public static Cast<Object> floating() {
        return new PrimitiveCasts.FloatCast("float");
    }

    /**
     * Returns the {@code string} cast.
     *
     * @return a cast reading {@code String}
     */
    public static Cast<Object> string() {
        return new PrimitiveCasts.StringCast("string");
    }

    /**
     * Returns the {@code bool} cast.
     *
     * @return a cast reading {@code Boolean}
     */
    public static Cast<Object> bool() {
        return new PrimitiveCasts.BoolCast("bool");
    }

    /**
     * Returns the {@code decimal:N} cast.
     *
     * @param scale the digits after the point, 0 to 30
     * @return a cast reading {@code BigDecimal}
     * @throws IllegalArgumentException if {@code scale} is out of range
     */
    public static Cast<Object> decimal(int scale) {
        return new DecimalCast(scale);
    }

    /**
     * Returns the {@code date} cast.
     *
     * @return a cast reading {@code LocalDate}
     */
    public static Cast<Object> date() {
        return new DateCast();
    }

    /**
     * Returns the {@code date:F} cast.
     *
     * @param pattern the {@link java.time.format.DateTimeFormatter} pattern for serialized output
     * @return a cast reading {@code LocalDate}
     * @throws NullPointerException if {@code pattern} is null
     * @throws IllegalArgumentException if the pattern is empty, does not compile, or cannot format
     *     a date
     */
    public static Cast<Object> date(String pattern) {
        return CastSpecParser.date(pattern);
    }

    /**
     * Returns the {@code datetime} cast.
     *
     * @return a cast reading {@code Instant}
     */
    public static Cast<Object> datetime() {
        return new MomentCast(false);
    }

    /**
     * Returns the {@code datetime:F} cast.
     *
     * @param pattern the {@link java.time.format.DateTimeFormatter} pattern for serialized output,
     *     applied in UTC
     * @return a cast reading {@code Instant}
     * @throws NullPointerException if {@code pattern} is null
     * @throws IllegalArgumentException if the pattern is empty, does not compile, or cannot format
     *     a moment
     */
    public static Cast<Object> datetime(String pattern) {
        return CastSpecParser.datetime(pattern);
    }

    /**
     * Returns the {@code timestamp} cast.
     *
     * @return a cast reading {@code Long} epoch seconds
     */
    public static Cast<Object> timestamp() {
        return new MomentCast(true);
    }

    /**
     * Returns the {@code json} cast.
     *
     * @return a cast reading JSON values, writing non-ASCII escaped
     */
    public static Cast<Object> json() {
        return new JsonCasts("json", false);
    }

    /**
     * Returns the {@code json:unicode} cast.
     *
     * @return a cast reading JSON values, writing non-ASCII unescaped
     */
    public static Cast<Object> jsonUnicode() {
        return new JsonCasts("json:unicode", true);
    }

    /**
     * Returns an enum cast.
     *
     * @param <E> the enum type
     * @param enumType the enum class
     * @return a cast reading constants by {@code name()} or {@link BackedValue#value()}
     * @throws NullPointerException if {@code enumType} is null
     * @throws IllegalArgumentException if a backed value is null or two constants share one
     */
    public static <E extends Enum<E>> Cast<Object> enumOf(Class<E> enumType) {
        return new EnumCast(enumType);
    }

    /**
     * Returns the canonical type name of a cast, used by {@code hasCast}.
     *
     * <p>Built-ins return one name per family whatever alias or argument was used: {@code int},
     * {@code float}, {@code string}, {@code bool}, {@code decimal}, {@code date}, {@code datetime},
     * {@code timestamp}, {@code json}, {@code enum}. Any other cast returns its class name. A cast
     * string is parsed first.
     *
     * @param cast the cast
     * @return the type name
     * @throws NullPointerException if {@code cast} is null
     * @throws IllegalArgumentException if {@code cast} is an invalid cast string
     */
    public static String typeName(Cast<?> cast) {
        Cast<?> resolved = resolve(cast);
        if (resolved instanceof PrimitiveCasts.IntCast) {
            return "int";
        }
        if (resolved instanceof PrimitiveCasts.FloatCast) {
            return "float";
        }
        if (resolved instanceof PrimitiveCasts.StringCast) {
            return "string";
        }
        if (resolved instanceof PrimitiveCasts.BoolCast) {
            return "bool";
        }
        return otherTypeName(resolved);
    }

    /**
     * Turns a cast string from {@link #of(String)} into its built-in cast; returns any other cast
     * unchanged.
     *
     * <p>Internal to the model layer, which calls it once per model class and wraps a failure in
     * an {@link InvalidCastException} naming the model and key. Application code does not need it.
     *
     * @param cast the cast
     * @return the resolved cast
     * @throws NullPointerException if {@code cast} is null
     * @throws IllegalArgumentException if {@code cast} is an invalid cast string
     */
    public static Cast<?> resolve(Cast<?> cast) {
        Objects.requireNonNull(cast, "cast");
        if (cast instanceof SpecCast) {
            return CastSpecParser.parse(((SpecCast) cast).spec());
        }
        return cast;
    }

    private static String otherTypeName(Cast<?> cast) {
        if (cast instanceof DecimalCast) {
            return "decimal";
        }
        if (cast instanceof DateCast) {
            return "date";
        }
        if (cast instanceof MomentCast) {
            return ((MomentCast) cast).name().equals("timestamp") ? "timestamp" : "datetime";
        }
        if (cast instanceof JsonCasts) {
            return "json";
        }
        if (cast instanceof EnumCast) {
            return "enum";
        }
        return cast.getClass().getName();
    }
}
