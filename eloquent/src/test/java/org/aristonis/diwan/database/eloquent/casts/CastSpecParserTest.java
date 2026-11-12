package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.eloquent.Model;
import org.junit.jupiter.api.Test;

class CastSpecParserTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();
    private static final String KEY = "col";
    private static final int THREADS = 8;
    private static final int ROUNDS = 500;

    private enum Colour { RED, GREEN }

    /** A user cast, to check typeName falls back to the class name. */
    static final class UpperCast implements Cast<String> {
        @Override
        public String get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return String.valueOf(raw).toUpperCase(java.util.Locale.ROOT);
        }

        @Override
        public Object set(Model model, String key, String value, Map<String, Object> attributes) {
            return value;
        }
    }

    @Test
    void typeNameIsCaseInsensitiveFormatIsKept() {
        Cast<?> cast = CastSpecParser.parse("  DateTime:yyyy-MM-dd");
        MomentCast moment = assertInstanceOf(MomentCast.class, cast);
        assertEquals("datetime:yyyy-MM-dd", moment.name());
        DateCast date = assertInstanceOf(DateCast.class, CastSpecParser.parse("DATE:dd MMM uuuu"));
        assertEquals("date:dd MMM uuuu", date.name());
        assertInstanceOf(PrimitiveCasts.IntCast.class, CastSpecParser.parse(" Integer "));
    }

    @Test
    void badDecimalScaleFails() {
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:x"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:-1"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:31"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal: 2"));
        assertInstanceOf(DecimalCast.class, CastSpecParser.parse("decimal:0"));
        assertInstanceOf(DecimalCast.class, CastSpecParser.parse("Decimal:30"));
    }

    @Test
    void unknownCastFails() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("colour"));
        assertTrue(e.getMessage().contains("colour"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse(" :x"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("int:5"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("json:ascii"));
        assertThrows(NullPointerException.class, () -> CastSpecParser.parse(null));
    }

    @Test
    void uncompilableFormatFails() {
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("datetime:{bad"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("date:"));
        // PHP "Y-m-d": compiles for datetime (week year, minute, day) with a different meaning.
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("datetime:Y-m-d"));
        // A date has no minute field, so the probe format fails.
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("date:Y-m-d"));
    }

    @Test
    void everyAliasResolves() {
        assertInstanceOf(PrimitiveCasts.IntCast.class, CastSpecParser.parse("int"));
        assertInstanceOf(PrimitiveCasts.IntCast.class, CastSpecParser.parse("integer"));
        assertInstanceOf(PrimitiveCasts.FloatCast.class, CastSpecParser.parse("real"));
        assertInstanceOf(PrimitiveCasts.FloatCast.class, CastSpecParser.parse("float"));
        assertInstanceOf(PrimitiveCasts.FloatCast.class, CastSpecParser.parse("double"));
        assertInstanceOf(PrimitiveCasts.StringCast.class, CastSpecParser.parse("string"));
        assertInstanceOf(PrimitiveCasts.BoolCast.class, CastSpecParser.parse("bool"));
        assertInstanceOf(PrimitiveCasts.BoolCast.class, CastSpecParser.parse("boolean"));
        assertInstanceOf(DecimalCast.class, CastSpecParser.parse("decimal:2"));
        assertInstanceOf(DateCast.class, CastSpecParser.parse("date"));
        assertInstanceOf(DateCast.class, CastSpecParser.parse("immutable_date"));
        assertInstanceOf(DateCast.class, CastSpecParser.parse("immutable_date:dd/MM/uuuu"));
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("datetime"));
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("immutable_datetime"));
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("custom_datetime:uuuu-MM-dd HH:mm"));
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("immutable_custom_datetime:uuuu"));
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("timestamp"));
        assertInstanceOf(JsonCasts.class, CastSpecParser.parse("json"));
        assertInstanceOf(JsonCasts.class, CastSpecParser.parse("array"));
        assertInstanceOf(JsonCasts.class, CastSpecParser.parse("json:unicode"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("custom_datetime"));
    }

    @Test
    void parsedCastsWork() {
        Cast<?> decimal = CastSpecParser.parse("decimal:2");
        assertEquals(new BigDecimal("1.50"), decimal.get(null, KEY, "1.5", NO_ATTRIBUTES));
        assertEquals(Boolean.TRUE, CastSpecParser.parse("boolean").get(null, KEY, 1, NO_ATTRIBUTES));
    }

    @Test
    void cacheReturnsSameInstance() {
        assertSame(CastSpecParser.parse("decimal:7"), CastSpecParser.parse("decimal:7"));
    }

    @Test
    void cacheCappedAt256() {
        for (int i = 0; i < CastSpecParser.CACHE_LIMIT + 50; i++) {
            assertNotNull(CastSpecParser.parse("datetime:'c" + i + "' uuuu"));
        }
        assertTrue(CastSpecParser.cacheSize() <= CastSpecParser.CACHE_LIMIT);
        assertEquals(256, CastSpecParser.CACHE_LIMIT);
        // Past the cap parsing still works, and still validates.
        assertInstanceOf(MomentCast.class, CastSpecParser.parse("datetime:'past cap' uuuu"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:99"));
    }

    @Test
    void cacheIsThreadSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Cast<?>>> results = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int seed = t;
                Callable<Cast<?>> task = () -> {
                    Cast<?> last = null;
                    for (int i = 0; i < ROUNDS; i++) {
                        last = CastSpecParser.parse("decimal:" + ((seed + i) % 31));
                        CastSpecParser.parse("json:unicode");
                    }
                    return last;
                };
                results.add(pool.submit(task));
            }
            for (Future<Cast<?>> result : results) {
                assertInstanceOf(DecimalCast.class, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(CastSpecParser.cacheSize() <= CastSpecParser.CACHE_LIMIT);
    }

    @Test
    void collectionAndObjectSpecsResolve() {
        assertInstanceOf(JsonShapeCast.class, CastSpecParser.parse("collection"));
        assertInstanceOf(JsonShapeCast.class, CastSpecParser.parse("OBJECT"));
        assertInstanceOf(JsonShapeCast.class, CastSpecParser.parse(" Collection "));
        assertInstanceOf(JsonShapeCast.class, Casts.resolve(Casts.of("collection")));
    }

    @Test
    void collectionAndObjectTakeNoArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CastSpecParser.parse("collection:x"));

        assertTrue(e.getMessage().contains("Unknown cast [collection:x]"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("object:x"));
    }

    @Test
    void typeNameForCollectionAndObject() {
        assertEquals("collection", Casts.typeName(Casts.of("collection")));
        assertEquals("object", Casts.typeName(Casts.of("Object")));
        assertEquals("collection", Casts.typeName(Casts.collection()));
        assertEquals("object", Casts.typeName(Casts.object()));
    }

    @Test
    void typeNameForBuiltInsAndUserCasts() {
        assertEquals("int", Casts.typeName(Casts.integer()));
        assertEquals("int", Casts.typeName(Casts.of("Integer")));
        assertEquals("float", Casts.typeName(Casts.floating()));
        assertEquals("float", Casts.typeName(Casts.of("double")));
        assertEquals("string", Casts.typeName(Casts.string()));
        assertEquals("bool", Casts.typeName(Casts.of("boolean")));
        assertEquals("decimal", Casts.typeName(Casts.decimal(2)));
        assertEquals("date", Casts.typeName(Casts.date("dd/MM/uuuu")));
        assertEquals("datetime", Casts.typeName(Casts.datetime()));
        assertEquals("datetime", Casts.typeName(Casts.of("DateTime:uuuu")));
        assertEquals("timestamp", Casts.typeName(Casts.timestamp()));
        assertEquals("json", Casts.typeName(Casts.jsonUnicode()));
        assertEquals("json", Casts.typeName(Casts.of("array")));
        assertEquals("enum", Casts.typeName(Casts.enumOf(Colour.class)));
        assertEquals(UpperCast.class.getName(), Casts.typeName(new UpperCast()));
        assertThrows(IllegalArgumentException.class, () -> Casts.typeName(Casts.of("colour")));
    }

    @Test
    void ofIsLazy() {
        Cast<?> bad = Casts.of("decimal:x");
        assertInstanceOf(SpecCast.class, bad);
        assertEquals("decimal:x", ((SpecCast) bad).spec());
        assertThrows(IllegalArgumentException.class, () -> Casts.resolve(bad));
        assertThrows(IllegalStateException.class, () -> bad.get(null, KEY, "1", NO_ATTRIBUTES));
        assertInstanceOf(DecimalCast.class, Casts.resolve(Casts.of("decimal:2")));
        UpperCast user = new UpperCast();
        assertSame(user, Casts.resolve(user));
        assertThrows(NullPointerException.class, () -> Casts.of(null));
    }

    @Test
    void typedFactoriesValidateEagerly() {
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(-1));
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(31));
        assertThrows(IllegalArgumentException.class, () -> Casts.date("{bad"));
        assertThrows(IllegalArgumentException.class, () -> Casts.date("HH:mm"));
        assertThrows(IllegalArgumentException.class, () -> Casts.datetime("{bad"));
        assertThrows(NullPointerException.class, () -> Casts.enumOf(null));
        assertInstanceOf(DateCast.class, Casts.date());
        assertInstanceOf(JsonCasts.class, Casts.json());
        assertInstanceOf(PrimitiveCasts.BoolCast.class, Casts.bool());
    }

    @Test
    void encryptedSpecsResolve() {
        assertInstanceOf(EncryptedCast.class, CastSpecParser.parse("encrypted"));
        assertInstanceOf(EncryptedCast.class, CastSpecParser.parse("ENCRYPTED:JSON"));
        assertInstanceOf(EncryptedCast.class, CastSpecParser.parse("encrypted:array"));
        assertInstanceOf(EncryptedCast.class, CastSpecParser.parse("encrypted:collection"));
        assertInstanceOf(EncryptedCast.class, CastSpecParser.parse("encrypted:object"));
        assertInstanceOf(EncryptedCast.class, CastSpecParser.parse(" Encrypted "));
        assertInstanceOf(EncryptedCast.class, Casts.resolve(Casts.of("encrypted:json")));
    }

    @Test
    void encryptedRefusesOtherArguments() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CastSpecParser.parse("encrypted:x"));

        assertTrue(e.getMessage().contains("Unknown cast [encrypted:x]"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("encrypted:"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("encrypted: json"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("encrypted:string"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("encrypted:encrypted"));
    }

    @Test
    void typeNameForEncrypted() {
        assertEquals("encrypted", Casts.typeName(Casts.encrypted()));
        assertEquals("encrypted", Casts.typeName(Casts.encrypted(Casts.of("json"))));
        assertEquals("encrypted", Casts.typeName(Casts.of("encrypted")));
        assertEquals("encrypted", Casts.typeName(Casts.of("encrypted:json")));
        assertEquals("encrypted", Casts.typeName(Casts.of("encrypted:array")));
        assertEquals("encrypted", Casts.typeName(Casts.of("encrypted:collection")));
        assertEquals("encrypted", Casts.typeName(Casts.of("encrypted:object")));
    }

    @Test
    void encryptedFactoryRulesMatchTheSpec() {
        assertThrows(IllegalArgumentException.class, () -> Casts.encrypted(Casts.of("int")));
        assertThrows(IllegalArgumentException.class, () -> Casts.encrypted(Casts.string()));
        assertThrows(IllegalArgumentException.class, () -> Casts.encrypted(Casts.encrypted()));
        assertThrows(IllegalArgumentException.class, () -> Casts.encrypted(new UpperCast()));
        assertThrows(NullPointerException.class, () -> Casts.encrypted((Cast<?>) null));
        assertInstanceOf(EncryptedCast.class, Casts.encrypted(Casts.collection()));
        assertInstanceOf(EncryptedCast.class, Casts.encrypted(Casts.jsonUnicode()));
        assertInstanceOf(EncryptedCast.class, Casts.encrypted(Casts.of("object")));
    }

    @Test
    void hashedSpecsResolve() {
        assertInstanceOf(HashedCast.class, CastSpecParser.parse("hashed"));
        assertInstanceOf(HashedCast.class, CastSpecParser.parse(" HASHED "));
        assertInstanceOf(HashedCast.class, Casts.resolve(Casts.of("hashed")));
    }

    @Test
    void hashedRefusesAnArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CastSpecParser.parse("hashed:x"));

        assertTrue(e.getMessage().contains("Unknown cast [hashed:x]"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("hashed:"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("hashed: x"));
    }

    @Test
    void typeNameForHashed() {
        assertEquals("hashed", Casts.typeName(Casts.hashed()));
        assertEquals("hashed", Casts.typeName(Casts.of("hashed")));
        assertEquals("hashed", Casts.typeName(Casts.of("HASHED")));
    }

    @Test
    void castMapKeepsOrderAndIsImmutable() {
        UpperCast user = new UpperCast();
        Map<String, Cast<?>> map = CastMap.create()
                .put("total", "decimal:2")
                .put("name", user)
                .put("at", Casts.datetime())
                .build();
        Iterator<String> keys = map.keySet().iterator();
        assertEquals("total", keys.next());
        assertEquals("name", keys.next());
        assertEquals("at", keys.next());
        assertSame(user, map.get("name"));
        assertInstanceOf(SpecCast.class, map.get("total"));
        assertThrows(UnsupportedOperationException.class, () -> map.put("x", user));
        assertThrows(NullPointerException.class, () -> CastMap.create().put(null, user));
        assertThrows(NullPointerException.class, () -> CastMap.create().put("k", (Cast<?>) null));
    }

    @Test
    void castMapBuildIsASnapshot() {
        CastMap builder = CastMap.create().put("a", "int");
        Map<String, Cast<?>> first = builder.build();
        builder.put("b", "string");
        assertEquals(1, first.size());
        assertEquals(2, builder.build().size());
    }
}
