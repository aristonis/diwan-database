package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.Internal;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks binding values against the types every driver must accept, and copies them. */
@Internal
public final class Bindings {

    private static final Set<Class<?>> SUPPORTED_TYPES = Collections.unmodifiableSet(new HashSet<Class<?>>(
            Arrays.<Class<?>>asList(Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class,
                    Double.class, BigDecimal.class, BigInteger.class, String.class, byte[].class, LocalDate.class,
                    LocalTime.class, LocalDateTime.class, OffsetDateTime.class, OffsetTime.class, Instant.class)));

    private Bindings() {
    }

    static List<Object> check(Object[] bindings) {
        if (bindings == null) {
            throw new IllegalArgumentException("bindings must not be a null array");
        }
        return check(Arrays.asList(bindings));
    }

    /** Returns an unmodifiable checked copy, so a caller changing its list or arrays later cannot affect a run. */
    static List<Object> check(List<?> bindings) {
        if (bindings == null) {
            throw new IllegalArgumentException("bindings must not be null");
        }
        List<Object> copy = copyArrays(bindings);
        for (int i = 0; i < copy.size(); i++) {
            Object value = copy.get(i);
            // Exact class match: BigDecimal and BigInteger are not final, and a subclass of them is
            // not something a driver can be asked to send.
            if (value != null && !SUPPORTED_TYPES.contains(value.getClass())) {
                throw new IllegalArgumentException("Unsupported binding type " + value.getClass().getName()
                        + " at position " + (i + 1));
            }
        }
        return Collections.unmodifiableList(copy);
    }

    /** Returns a modifiable copy of {@code values} in which every {@code byte[]} is cloned. */
    @Internal
    public static List<Object> copyArrays(List<?> values) {
        List<Object> copy = new ArrayList<Object>(values.size());
        for (Object value : values) {
            copy.add(value instanceof byte[] ? ((byte[]) value).clone() : value);
        }
        return copy;
    }
}
