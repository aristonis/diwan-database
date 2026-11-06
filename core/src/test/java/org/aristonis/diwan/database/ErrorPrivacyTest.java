package org.aristonis.diwan.database;

import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorPrivacyTest {

    private static final String SECRET = "s3cret-token-value";

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    @Test
    void leavesHiddenBindingsOutOfTheSerializedForm() throws Exception {
        QueryException error = new QueryException(
                "main", "select * from t where token = ?", Arrays.<Object>asList(SECRET), failure("42P01"), false);

        byte[] bytes = serialize(error);
        QueryException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            copy = (QueryException) in.readObject();
        }

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SECRET));
        assertTrue(copy.getBindings().isEmpty());
        assertEquals("select * from t where token = ?", copy.getSql());
    }

    @Test
    void keepsShownBindingsInTheSerializedForm() throws Exception {
        QueryException error = new QueryException(
                "main", "select ?", Arrays.<Object>asList("visible"), failure("42P01"), true);

        QueryException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialize(error)))) {
            copy = (QueryException) in.readObject();
        }

        assertEquals(Arrays.<Object>asList("visible"), copy.getBindings());
    }

    @Test
    void loggedQueryTextCountsBindingsInsteadOfPrintingThem() {
        LoggedQuery query = new LoggedQuery("select * from t where token = ?", Arrays.<Object>asList(SECRET));

        assertEquals("select * from t where token = ? [1 binding]", query.toString());
    }

    @Test
    void escapesLineBreaksAndShortensLongValuesInMessages() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append('x');
        }
        QueryException error = new QueryException(
                "main", "select ?\nfrom t", Arrays.<Object>asList("a\r\nb", huge.toString()), failure("42P01"), true);

        String message = error.getMessage();

        assertFalse(message.contains("\n") || message.contains("\r"));
        assertTrue(message.contains("select ?\\nfrom t"));
        assertTrue(message.length() < 800);
    }

    @Test
    void keepsTheResultWhenTheCleanupHandlerItselfFails() {
        FakeDriver driver = new FakeDriver().failsToClose(new IllegalStateException("network gone"));
        Connection connection = Connection.builder().name("main").driver(driver)
                .onCleanupFailure(problem -> {
                    throw new IllegalArgumentException("handler broke");
                }).build();

        assertTrue(connection.statement("select 1"));
    }

    @Test
    void refusesATablePrefixThatIsNotAPlainIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> Connection.builder().tablePrefix("app; drop table x"));
    }
}
