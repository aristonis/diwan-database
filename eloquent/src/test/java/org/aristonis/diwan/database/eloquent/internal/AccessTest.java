package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccessTest {

    @Test
    void setValueIsReturned() {
        Access.Holder<String> holder = new Access.Holder<>("sample");
        String value = "bridge";

        holder.set(value);

        assertSame(value, holder.get());
        assertTrue(holder.isSet());
    }

    @Test
    void secondSetThrows() {
        Access.Holder<String> holder = new Access.Holder<>("sample");
        holder.set("first");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> holder.set("second"));

        assertEquals("Internal access [sample] is already set", e.getMessage());
        assertEquals("first", holder.get());
    }

    @Test
    void unsetHolderThrows() {
        Access.Holder<String> holder = new Access.Holder<>("sample");

        IllegalStateException e = assertThrows(IllegalStateException.class, holder::get);

        assertFalse(holder.isSet());
        assertTrue(e.getMessage().startsWith("Internal access [sample] is not set"));
    }

    @Test
    void nullValueIsRefused() {
        Access.Holder<String> holder = new Access.Holder<>("sample");

        assertThrows(NullPointerException.class, () -> holder.set(null));
        assertFalse(holder.isSet());
    }
}
