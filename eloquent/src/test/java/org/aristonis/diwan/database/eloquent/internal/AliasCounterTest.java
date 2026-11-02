package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

class AliasCounterTest {

    @Test
    void numbersAliasesFromZero() {
        AliasCounter counter = new AliasCounter();

        assertEquals("laravel_reserved_0", counter.next());
        assertEquals("laravel_reserved_1", counter.next());
    }

    @Test
    void copyKeepsTheCurrentValue() {
        AliasCounter counter = new AliasCounter();
        counter.next();

        AliasCounter copy = counter.copy();

        assertNotSame(counter, copy);
        assertEquals("laravel_reserved_1", copy.next());
    }

    @Test
    void copyIsIndependentAfterCopy() {
        AliasCounter counter = new AliasCounter();
        AliasCounter copy = counter.copy();

        copy.next();
        copy.next();

        assertEquals("laravel_reserved_0", counter.next());
        assertEquals("laravel_reserved_2", copy.next());
    }

    @Test
    void twoCountersGiveTheSameSequence() {
        AliasCounter first = new AliasCounter();
        AliasCounter second = new AliasCounter();

        first.next();

        assertEquals("laravel_reserved_1", first.next());
        assertEquals("laravel_reserved_0", second.next());
    }
}
