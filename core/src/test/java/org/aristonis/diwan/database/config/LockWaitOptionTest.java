package org.aristonis.diwan.database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
import org.junit.jupiter.api.Test;

class LockWaitOptionTest {

    private static final String CONNECTION = "main";

    private static long parse(String value) {
        return LockWaitOption.parseMillis(CONNECTION, LockWaitOption.KEY, value);
    }

    private static void assertRejected(String value) {
        // Arrange
        // Act
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class, () -> parse(value));

        // Assert
        assertTrue(e.getMessage().contains("lock_wait"), e.getMessage());
        assertTrue(e.getMessage().contains(CONNECTION), e.getMessage());
        assertEquals(Optional.of(CONNECTION), e.connection());
        assertEquals(Optional.of(LockWaitOption.KEY), e.key());
    }

    @Test
    void parsesSecondsSuffixAsThousandsOfMilliseconds() {
        // Arrange
        String value = "5s";

        // Act
        long millis = parse(value);

        // Assert
        assertEquals(5000L, millis);
    }

    @Test
    void parsesMillisecondsSuffixAsMilliseconds() {
        // Arrange
        String value = "500ms";

        // Act
        long millis = parse(value);

        // Assert
        assertEquals(500L, millis);
    }

    @Test
    void parsesPlainDigitsAsMilliseconds() {
        // Arrange
        String value = "250";

        // Act
        long millis = parse(value);

        // Assert
        assertEquals(250L, millis);
    }

    @Test
    void ignoresSurroundingWhitespaceAndSuffixCase() {
        // Arrange
        String value = " 2S ";

        // Act
        long millis = parse(value);

        // Assert
        assertEquals(2000L, millis);
    }

    @Test
    void acceptsLargestValueThatFitsAnIntegerOfMilliseconds() {
        // Arrange
        String value = "2147483647";

        // Act
        long millis = parse(value);

        // Assert
        assertEquals(Integer.MAX_VALUE, millis);
    }

    @Test
    void rejectsNonNumericValueNamingConnectionAndKey() {
        assertRejected("abc");
    }

    @Test
    void rejectsZeroDurationNamingConnectionAndKey() {
        assertRejected("0s");
    }

    @Test
    void rejectsNegativeValueNamingConnectionAndKey() {
        assertRejected("-1");
    }

    @Test
    void rejectsBlankValueNamingConnectionAndKey() {
        assertRejected("");
    }

    @Test
    void rejectsValueBeyondIntegerMaxMillisecondsNamingConnectionAndKey() {
        assertRejected("99999999999");
    }

    @Test
    void rejectsSecondsValueWhoseMillisecondsOverflowAnInteger() {
        assertRejected("2147484s");
    }
}
