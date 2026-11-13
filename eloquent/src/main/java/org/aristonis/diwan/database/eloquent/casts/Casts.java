package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.aristonis.diwan.database.eloquent.Model;

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
     * {@code string}, {@code bool}, {@code boolean}, {@code decimal:N} (N 0 to 30), {@code decimal:P,S},
     * {@code decimal:P,S,MODE} (a {@link java.math.RoundingMode} name, any case), {@code date},
     * {@code date:F}, {@code immutable_date[:F]}, {@code datetime}, {@code datetime:F},
     * {@code immutable_datetime[:F]}, {@code custom_datetime:F}, {@code timestamp}, {@code json},
     * {@code array}, {@code json:unicode}, {@code collection}, {@code object},
     * {@code encrypted}, {@code encrypted:array}, {@code encrypted:json},
     * {@code encrypted:collection}, {@code encrypted:object}, {@code hashed},
     * {@code binary:uuid}, {@code binary:ulid}, {@code instant}. The argument is
     * kept as written. A format {@code F} is a
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
     * Returns the strict {@code decimal:P,S} cast, which refuses a value that does not fit.
     *
     * <p>Precision is the total digits and scale the digits after the point, as in
     * {@code Blueprint.decimal}. A value with more decimals than the scale, or more than
     * precision minus scale integer digits, throws {@code InvalidCastException}; the message never
     * carries the value.
     *
     * @param precision the total number of digits, 1 to 1000
     * @param scale the digits after the point, 0 to the precision and at most 30
     * @return a cast reading {@code BigDecimal} at the given scale
     * @throws IllegalArgumentException if {@code precision} or {@code scale} is out of range
     */
    public static Cast<Object> decimal(int precision, int scale) {
        return new StrictDecimalCast(precision, scale, RoundingMode.UNNECESSARY);
    }

    /**
     * Returns the strict {@code decimal:P,S,MODE} cast, which rounds extra decimals with a mode.
     *
     * <p>Integer digits beyond precision minus scale are refused whatever the mode, also when
     * rounding carries into a new digit.
     *
     * @param precision the total number of digits, 1 to 1000
     * @param scale the digits after the point, 0 to the precision and at most 30
     * @param mode how extra decimals are rounded; {@code UNNECESSARY} refuses them
     * @return a cast reading {@code BigDecimal} at the given scale
     * @throws IllegalArgumentException if {@code precision} or {@code scale} is out of range, or
     *     {@code mode} is null
     */
    public static Cast<Object> decimal(int precision, int scale, RoundingMode mode) {
        return new StrictDecimalCast(precision, scale, mode);
    }

    /**
     * Returns the strict decimal cast for a text column, which keeps every digit on every database.
     *
     * <p>The precision and scale rules are those of {@link #decimal(int, int)}. The 15-digit limit that
     * {@code decimal(p, s)} applies on SQLite is skipped, because a text column stores the digits as
     * written; use it for long exact values, such as exchange rates, on a {@code string} column. SQL
     * sorts, compares and sums such a column as text, so do that math in Java.
     *
     * @param precision the total number of digits, 1 to 1000
     * @param scale the digits after the point, 0 to the precision and at most 30
     * @return a cast reading {@code BigDecimal} at the given scale
     * @throws IllegalArgumentException if {@code precision} or {@code scale} is out of range
     */
    public static Cast<Object> decimalText(int precision, int scale) {
        return new StrictDecimalCast(precision, scale, RoundingMode.UNNECESSARY, true);
    }

    /**
     * Returns the strict decimal cast for a text column, rounding extra decimals with a mode; as
     * {@link #decimalText(int, int)}.
     *
     * @param precision the total number of digits, 1 to 1000
     * @param scale the digits after the point, 0 to the precision and at most 30
     * @param mode how extra decimals are rounded; {@code UNNECESSARY} refuses them
     * @return a cast reading {@code BigDecimal} at the given scale
     * @throws IllegalArgumentException if {@code precision} or {@code scale} is out of range, or
     *     {@code mode} is null
     */
    public static Cast<Object> decimalText(int precision, int scale, RoundingMode mode) {
        return new StrictDecimalCast(precision, scale, mode, true);
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
     * Returns the {@code instant} cast, which stores an {@link java.time.Instant} whatever the
     * model's {@link org.aristonis.diwan.database.eloquent.TimestampFormat}.
     *
     * <p>Reads as an {@code Instant} from any stored form. Writes truncate to microseconds and
     * accept the same values as {@link #datetime()}. A bare number is refused. Use it for
     * {@code Blueprint.instant} columns other than {@code created_at}, {@code updated_at} and
     * {@code deleted_at}; those columns are also written by the framework in the model's format,
     * so they need {@code TimestampFormat.INSTANT} on the model and a cast of this kind fails
     * fast on a text-format model.
     *
     * @return a cast reading and writing {@code Instant}
     */
    public static Cast<Object> instant() {
        return new MomentCast();
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
     * Returns the {@code collection} cast: a JSON array column read as a {@code List}.
     *
     * <p>A stored JSON array reads as a mutable {@code List}; changing it in place marks the
     * attribute dirty, as with {@link #json()}. A null column, an empty string and the JSON
     * literal {@code null} read as null. A stored value of another shape, such as an object or a
     * scalar, throws {@link InvalidCastException} naming the key and {@code JSON array}, never
     * the value. Writes encode the list like {@link #json()}, a null value stores SQL NULL, and a
     * value that is not a {@code List} throws {@link InvalidCastException}. An element the codec
     * cannot encode fails the write with a cause of
     * {@link org.aristonis.diwan.database.eloquent.exception.JsonException}.
     *
     * @return a cast reading and writing {@code List} values
     */
    public static Cast<List<Object>> collection() {
        return new JsonShapeCast<>(JsonShapeCast.Shape.COLLECTION);
    }

    /**
     * Returns the {@code object} cast: a JSON object column read as a {@code Map}.
     *
     * <p>A stored JSON object reads as a mutable {@code Map} that keeps the stored key order;
     * changing it in place marks the attribute dirty, as with {@link #json()}. A null column, an
     * empty string and the JSON literal {@code null} read as null. A stored value of another
     * shape, such as an array or a scalar, throws {@link InvalidCastException} naming the key and
     * {@code JSON object}, never the value. Writes encode the map like {@link #json()}, a null
     * value stores SQL NULL, and a value that is not a {@code Map} throws
     * {@link InvalidCastException}.
     *
     * @return a cast reading and writing {@code Map} values keyed by {@code String}
     */
    public static Cast<Map<String, Object>> object() {
        return new JsonShapeCast<>(JsonShapeCast.Shape.OBJECT);
    }

    /**
     * Returns the {@code encrypted} cast: a text column holding encrypter output for a string.
     *
     * <p>A read decrypts the stored text with the model's {@link Model#getAttributeEncrypter()
     * encrypter} and returns the {@code String}; a write encrypts the text form of the value. A
     * null column reads null and a null value stores SQL NULL, without calling the encrypter.
     * An exception thrown by the encrypter, and a model without an encrypter, reach the caller as
     * an {@link InvalidCastException} whose cause is the original exception (same instance). The
     * column must be a text type and cannot be searched with {@code where}.
     *
     * @return a cast reading and writing encrypted strings
     */
    public static Cast<Object> encrypted() {
        return new EncryptedCast();
    }

    /**
     * Returns an {@code encrypted} cast that applies {@code inner} to the decrypted text.
     *
     * <p>A read decrypts, then reads the text through {@code inner}; a write encodes through
     * {@code inner}, then encrypts the result. The dirty check decrypts both stored values and
     * compares them through {@code inner}. Rules for null, the missing encrypter and failures are
     * those of {@link #encrypted()}: an exception thrown by the encrypter, and a model without an
     * encrypter, reach the caller as an {@link InvalidCastException} whose cause is the original
     * exception (same instance).
     *
     * @param inner the {@link #json()}, {@link #jsonUnicode()}, {@link #collection()} or
     *     {@link #object()} cast, or a cast string for one of them
     * @return a cast reading and writing encrypted values of the inner cast's type
     * @throws NullPointerException if {@code inner} is null
     * @throws IllegalArgumentException if {@code inner} is not one of the four casts, or is an
     *     invalid cast string
     */
    public static Cast<Object> encrypted(Cast<?> inner) {
        Objects.requireNonNull(inner, "inner");
        Cast<?> resolved = resolve(inner);
        String name = typeName(resolved);
        if (!name.equals("json") && !name.equals("collection") && !name.equals("object")) {
            throw new IllegalArgumentException(
                    "Cast [" + name + "] cannot be encrypted; use json, array, collection or object");
        }
        return new EncryptedCast(resolved);
    }

    /**
     * Returns the {@code hashed} cast: a text column holding the model's hash of a written string.
     *
     * <p>A write stores the string as given when the model's
     * {@link Model#getAttributeHasher() hasher} says it is already hashed and current, refuses it
     * when it is hashed but not current, and otherwise stores the hasher's hash of it. A value
     * that is not a {@code String} is refused with an {@link InvalidCastException}. A read
     * returns the stored text without calling the hasher. A null value stores SQL NULL and a null
     * column reads null, without calling the hasher. A model without a hasher fails on a non-null
     * write. An exception thrown by the hasher reaches the caller as an
     * {@link InvalidCastException} whose cause is the original exception (same instance). The
     * column must be a text type.
     *
     * @return a cast that hashes written strings
     */
    public static Cast<Object> hashed() {
        return new HashedCast();
    }

    /**
     * Returns the {@code binary:uuid} cast: a {@link UUID} stored as 16 bytes, most significant
     * bits first.
     *
     * <p>The column must be {@code binary()}, not {@code uuid()}. A read needs a {@code byte[]} of
     * exactly 16 bytes; a write accepts a {@code UUID} only. Any other value fails as an
     * {@link InvalidCastException} whose message never holds the value. Search the column with
     * {@link BinaryIds#uuidBytes(UUID)}. A null value stores SQL NULL.
     *
     * @return a cast reading and writing {@code UUID} values
     */
    public static Cast<UUID> binaryUuid() {
        return new BinaryCast<>(BinaryCast.Format.UUID);
    }

    /**
     * Returns the {@code binary:ulid} cast: a ULID text stored as 16 bytes, most significant byte
     * first.
     *
     * <p>The column must be {@code binary()}, not {@code uuid()}. A read needs a {@code byte[]} of
     * exactly 16 bytes and returns the 26-character lower-case text. A write accepts a
     * {@code String} of 26 characters in any letter case under the rules of
     * {@link BinaryIds#ulidBytes(String)}; any other value fails as an
     * {@link InvalidCastException} whose message never holds the value. Search the column with
     * {@link BinaryIds#ulidBytes(String)}. A null value stores SQL NULL.
     *
     * @return a cast reading and writing ULID {@code String} values
     */
    public static Cast<String> binaryUlid() {
        return new BinaryCast<>(BinaryCast.Format.ULID);
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
     * {@code timestamp}, {@code instant}, {@code json}, {@code collection}, {@code object},
     * {@code encrypted}, {@code hashed}, {@code binary},
     * {@code enum}. Any other cast returns its class name. A cast string is parsed first.
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
            String momentName = ((MomentCast) cast).name();
            return momentName.equals("timestamp") || momentName.equals("instant") ? momentName : "datetime";
        }
        if (cast instanceof EncryptedCast) {
            return "encrypted";
        }
        if (cast instanceof HashedCast) {
            return "hashed";
        }
        if (cast instanceof BinaryCast) {
            return "binary";
        }
        if (cast instanceof JsonShapeCast) {
            return ((JsonShapeCast<?>) cast).name();
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
