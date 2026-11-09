package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnInfoTest {

    private static ColumnInfo plain() {
        return new ColumnInfo("id", "integer", "integer", null, false, null, true, null, null);
    }

    @Test
    void nullableFieldsAreEmptyOptionals() {
        ColumnInfo info = plain();

        assertFalse(info.collation().isPresent());
        assertFalse(info.defaultValue().isPresent());
        assertFalse(info.comment().isPresent());
        assertFalse(info.generation().isPresent());
    }

    @Test
    void presentFieldsAreReturned() {
        ColumnInfo info = new ColumnInfo("name", "varchar", "varchar(255)", "nocase", true, "'x'", false, "a note",
                new ColumnGeneration(true, "a || b"));

        assertEquals("name", info.name());
        assertEquals("varchar", info.typeName());
        assertEquals("varchar(255)", info.type());
        assertEquals("nocase", info.collation().get());
        assertTrue(info.isNullable());
        assertEquals("'x'", info.defaultValue().get());
        assertFalse(info.isAutoIncrement());
        assertEquals("a note", info.comment().get());
        assertTrue(info.generation().get().isStored());
        assertEquals("a || b", info.generation().get().expression());
    }

    @Test
    void equalsAndHashCodeCoverEveryField() {
        ColumnInfo base = plain();

        assertEquals(base, plain());
        assertEquals(base.hashCode(), plain().hashCode());
        assertNotEquals(base, new ColumnInfo("id", "integer", "integer", "c", false, null, true, null, null));
        assertNotEquals(base, new ColumnInfo("id", "integer", "integer", null, true, null, true, null, null));
        assertNotEquals(base, new ColumnInfo("id", "integer", "integer", null, false, "1", true, null, null));
        assertNotEquals(base, new ColumnInfo("id", "integer", "integer", null, false, null, false, null, null));
        assertNotEquals(base, new ColumnInfo("id", "integer", "integer", null, false, null, true, "c", null));
        assertNotEquals(base, new ColumnInfo("id", "integer", "integer", null, false, null, true, null,
                new ColumnGeneration(false, "1")));
    }

    @Test
    void generationEqualsComparesKindAndExpression() {
        assertEquals(new ColumnGeneration(true, "a"), new ColumnGeneration(true, "a"));
        assertEquals(new ColumnGeneration(true, "a").hashCode(), new ColumnGeneration(true, "a").hashCode());
        assertNotEquals(new ColumnGeneration(true, "a"), new ColumnGeneration(false, "a"));
        assertNotEquals(new ColumnGeneration(true, "a"), new ColumnGeneration(true, "b"));
    }

    @Test
    void requiredFieldsRefuseNull() {
        assertThrows(NullPointerException.class,
                () -> new ColumnInfo(null, "integer", "integer", null, false, null, false, null, null));
        assertThrows(NullPointerException.class, () -> new ColumnGeneration(true, null));
    }

    @Test
    void toStringNamesTheColumn() {
        assertTrue(plain().toString().contains("id"));
    }
}
