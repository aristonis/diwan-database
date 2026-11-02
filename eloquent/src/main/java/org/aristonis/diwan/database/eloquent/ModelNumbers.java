package org.aristonis.diwan.database.eloquent;

import java.math.BigDecimal;
import org.aristonis.diwan.database.eloquent.casts.CastValues;

/**
 * Whole-number and decimal addition for increment, keeping whole numbers as {@code Long}.
 *
 * <p>Moved out of {@code Model} unchanged, to keep that file under its size limit.
 */
final class ModelNumbers {

    private ModelNumbers() {
    }

    static Object add(Object current, Number amount, boolean up) {
        Object base = current == null ? 0L : current;
        if (isWhole(base) && isWhole(amount)) {
            long delta = amount.longValue();
            long value = CastValues.toLong(base);
            return up ? Math.addExact(value, delta) : Math.subtractExact(value, delta);
        }
        BigDecimal value = CastValues.toBigDecimal(base);
        BigDecimal delta = CastValues.toBigDecimal(amount);
        BigDecimal result = up ? value.add(delta) : value.subtract(delta);
        boolean floating = base instanceof Double || base instanceof Float || amount instanceof Double
                || amount instanceof Float;
        return floating ? (Object) result.doubleValue() : result;
    }

    private static boolean isWhole(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || value instanceof String && ((String) value).matches("[+-]?\\d{1,18}");
    }
}
