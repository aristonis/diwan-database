# 0016. Attributes, casts and the JSON codec

## Status

Accepted

## Context

Models hold the values the database returns and compare them as stored. Laravel developers expect
casts (a `decimal:2` column read as a number, a `json` column read as a map, a `datetime` read as
a moment, an enum), accessors and mutators, `hidden`, `visible` and `appends`, and a dirty check
that knows the cast. Laravel's own dirty check has defects a port should not copy: JSON compared by
spelling, floats compared with an absolute epsilon, dates compared as text in the model's zone, a
`date` compared against a full datetime, and some casts leaving an object in the attributes instead
of the storage form. Reading JSON needs a parser, and the library adds no dependency. The jar must
run on Android, where R8 removes annotations and reflective lookups.

## Decision

1. Public types live in two packages beside the models: `eloquent.casts` (the cast contract, the
   built-in casts, `Casts`, `CastMap`, `Attribute`, `InvalidCastException`) and `eloquent.json`
   (`JsonCodec`, `JsonException`). The model gains three package-private layers between attributes
   and guards: casts, accessors, serialization.
2. A cast is a plain object: `Cast<T>` with `get` and `set`. Optional `CastSerializer` and
   `CastComparator` are found by `instanceof`. `set` returns one storage value or a `CastColumns`
   map for several columns. Built-in casts skip `null`; a user cast sees it only when it says so.
3. Casts are declared by overriding `casts()`, with Laravel's string forms (`Casts.of("decimal:2")`)
   or typed factories. No class names are looked up and no annotations are read. The type part of
   a string is trimmed and lower-cased and the argument after `:` is kept as written, so
   `DateTime:yyyy-MM-dd` works. A bad spec fails with `InvalidCastException` when the model class
   first resolves its casts. Casts and accessors resolve once per model class.
4. Java types: `int` reads `Long`, `float` reads `Double`, `decimal:N` reads `BigDecimal` rounded
   half up to N places and writes that text, `date` reads `LocalDate` and writes `yyyy-MM-dd`,
   `datetime` reads `Instant`, `timestamp` reads epoch seconds, `json` and `array` read maps and
   lists, an enum reads its constant and writes `name()` or `BackedValue.value()`. `immutable_*`
   are aliases, since `java.time` is immutable. A cast always writes the storage form.
5. Numbers given to date casts: a stored `Long` is read as microseconds, matching the instant
   storage form; `set` of a number on `timestamp` takes seconds, as Laravel; on `datetime` it is
   refused as ambiguous.
6. Bad stored data throws `InvalidCastException` naming the model, the key and the cast, never the
   value. Laravel reads broken JSON as `null`, `"abc"` as `0` and `"false"` as `true`.
7. The dirty check follows Laravel's order of steps with these fixes: JSON compares by structure
   (key order and `1` against `1.0` do not matter); floats are equal when both are NaN, when `==`
   holds, or within 4 ULP of the larger value; moments compare as instants, so the same wall clock
   in another zone is a change; `date` compares as a date, so a time of day change is not one.
   Kept from Laravel: decimals compare after rounding, `int` and `bool` compare by cast value. The
   check never throws; a value that cannot be converted is dirty.
8. The object a cast returns is cached per model and written back before the attributes are read,
   saved or synced, but only when it really changed: reading alone never makes a model dirty.
9. Accessors and mutators are registered in `accessors()` with `Attribute.of`, `get` and `set`;
   the lambdas receive the model as an argument. An accessor wins over the cast. Its cache is
   cleared on any write, so a computed value is never stale (Laravel keeps it and can write it back
   on save).
10. `toMap()` and `toJson()` apply `visible`, then `hidden`, then `appends`. Moments serialize as
    `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` UTC, a `date:F` or `datetime:F` cast uses F, decimals as plain
    text, enums as their stored value. NaN or Infinity makes `toJson` throw, as in Laravel.
11. JSON goes through the `JsonCodec` interface, chosen per model by `jsonCodec()`. The built-in
    codec is strict RFC 8259 with a nesting limit of 512, reads integers as `Long` or `BigInteger`
    and fractions as `BigDecimal` (never `Double`, so re-encoding never rounds), and writes compact
    text with non-ASCII escaped. Jackson or Gson can be plugged in by implementing the interface.
    PostgreSQL `json` and `jsonb` reach the cast as text.
12. Date formats are Java `DateTimeFormatter` patterns, not PHP letters. There is no translation.

## Consequences

- No new dependency, no reflection, no keep rules; casts work the same on SQLite, PostgreSQL and
  Android.
- Models without casts behave as before.
- A dirty check over many keys parses JSON only for keys with a `json` cast.
- A PHP format such as `Y-m-d` compiles in Java with another meaning (`Y` week-year, `m` minute).
- A value object returned by a user cast needs `equals`, or a `CastComparator`, to stay clean.
- Strict reads surface bad data that a Laravel app would have read silently.

## Alternatives rejected

- Jackson or Gson as a dependency: large, and the choice belongs to the application.
- Laravel's lenient conversions: silent data loss.
- Name-based accessor discovery (`getFooAttribute`): needs reflection, stripped by R8.
- A global, type-keyed converter registry (MyBatis, jdbi): casts are per model and per key.
- `Double` for JSON numbers: rounds ids and amounts on the way back.
- Translating PHP date letters: a second format language to keep correct.
