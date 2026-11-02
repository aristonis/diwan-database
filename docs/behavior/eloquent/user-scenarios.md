# Models: user scenarios

## Normal flow

1. The developer defines `User extends Model` with a `query` entry and a `fillable` list.
2. They create a user from request data and get the new id back.
3. They find the user, change the name and save; only `name` and `updated_at` are written.
4. They delete the user.

With casts:

1. The developer declares `price` as `decimal:2`, `options` as `json`, `paid_at` as `datetime`
   and `status` as an enum in `casts()`.
2. They read `price` as a `BigDecimal`, `options` as a map, `paid_at` as an `Instant` and `status`
   as the constant.
3. They change one key of the `options` map and save; only `options` and `updated_at` are written.
4. They hide `password`, append a computed `display_name`, and send `toJson()` to the client.

With relations:

1. The developer declares `posts()` on `User`, `author()` and `comments()` on `Post`, and lists
   them in `relations()`.
2. They read users with `with("posts.comments")`: one query per level, whatever the number of
   users.
3. They find users with at least one post titled `Java%` through `whereHas`.
4. They create a post through `user.posts().create(...)`; its `user_id` is set for them.
5. They send `toJson()` of a user with its posts loaded; the posts appear under `posts`.

## Edge cases

| # | Situation | What happens | Test |
|---|---|---|---|
| E1 | `fill` with a key that is not fillable | Dropped; refused with Laravel's message when the model prevents silent discards | `ModelGuardsTest#fillSetsFillableKeysAndSilentlyDropsOthers`, `ModelGuardsTest#preventingSilentDiscardsNamesEveryDroppedKey`, `SQLiteModelTest#massAssignmentAndForceFill` |
| E2 | A model with the default guards (`guarded` is `*`) filled | Refused with `MassAssignmentException` | `ModelGuardsTest#aTotallyGuardedModelRefusesWithLaravelsMessage`, `AndroidModelTest#massAssignmentRefusesGuardedKeysAndForceFillBypasses` |
| E3 | `save` on a clean model already in the database | No SQL; returns `true` | `ModelPersistenceTest#saveOnACleanExistingModelRunsNoSql` |
| E4 | Primary key changed, then saved | The update finds the row by the original key | `ModelPersistenceTest#saveUpdatesOnlyDirtyColumnsByTheOriginalKey` |
| E5 | `delete` on a model not in the database | Returns `false`; no SQL | `ModelPersistenceTest#deleteOnAModelNotInTheDatabaseIsFalseWithoutSql` |
| E6 | `increment` on a model not in the database | Refused; no SQL | `ModelPersistenceTest#incrementOnAModelNotInTheDatabaseIsRefused` |
| E7 | `findOrFail` with ids that do not exist | `ModelNotFoundException` naming the model and the ids | `EloquentBuilderTest#missingModelsThrowLaravelsMessage`, `SQLiteModelTest#finderFailuresUseLaravelsMessages` |
| E8 | `firstOrCreate` after someone else inserted the row, with a unique index | Returns the existing row as a model; no duplicate | `SQLiteModelTest#firstOrCreateReturnsTheRowSomeoneElseInsertedFirst`, `PostgresModelTest#firstOrCreateAndCreateOrFirstInsideATransactionUseASavepoint` |
| E9 | An integer id read back as `Long` on Android and `Integer` or `Long` on JDBC | `getKey` and `is` still match | `AndroidModelTest#readBackIdTypeStillMatchesTheInsertedKey` |
| E10 | `updatedAtColumn` returns `null` | `updated_at` is not written | `ModelPersistenceTest#instantTimestampsAndANullUpdatedAtColumn`, `SQLiteModelTest#aModelWithoutAnUpdatedAtColumnWritesOnlyCreatedAt` |
| E11 | Dirty check of `1` against `1L` or `"1"` | Same value; not dirty | `ModelAttributesTest#numbersCompareByCanonicalTextAsLaravelDoes`, `SQLiteModelTest#storedValuesCompareCleanAgainstJavaValues` |
| E12 | A UUID-key model saved with its id already set | The id is kept | `ModelPersistenceTest#uniqueIdsAreGeneratedOnlyForABlankKey`, `SQLiteModelTest#uuidAndUlidKeysAreGeneratedOrKept` |
| E13 | A class name with an irregular plural | Guessed from a short rule set; override `table()` for others | `ModelConfigTest#tableIsTheSnakePluralOfTheClassName` |
| E14 | A model with no connection | Clear `IllegalStateException` | `ModelPersistenceTest#aModelWithoutAConnectionFailsClearly` |
| E15 | `replicate` | A new model without key or timestamps, not in the database | `ModelPersistenceTest#replicateCopiesWithoutKeyTimestampsOrExceptedKeys` |
| E16 | JSON column re-read with keys in another order, or `1` against `1.0` | Clean | `DirtyLadderTest#jsonKeyOrderIsClean`, `DirtyLadderTest#jsonOneEqualsOnePointZero` |
| E17 | JSON list re-ordered | Dirty | `DirtyLadderTest#jsonListOrderIsDirty` |
| E18 | Float changed in the last bit, NaN against NaN, `0.0` against `-0.0` | Clean | `DirtyLadderTest#floatLastUlpIsClean`, `DirtyLadderTest#floatNanAndInfinityAreClean`, `DirtyLadderTest#floatNegativeZeroIsClean` |
| E19 | Float changed by a tiny but real amount, or finite against Infinity | Dirty | `DirtyLadderTest#floatTinyChangeIsDirty`, `DirtyLadderTest#infiniteVsFiniteIsDirty` |
| E20 | Datetime set to the same wall clock in another zone | Dirty: another instant | `DateCastsTest#sameWallClockOtherZoneIsDirty` |
| E21 | Datetime set to the same instant with another offset | Clean | `DateCastsTest#sameInstantOtherOffsetIsClean` |
| E22 | Time of day changed on a `date` cast | Clean | `DateCastsTest#timeOfDayOnDateIsClean` |
| E23 | `decimal:2` rewritten with the same rounded value | Clean | `DecimalCastTest#compareAfterRounding`, `ModelCastsTest#decimalRewriteWithSameValueIsClean` |
| E24 | Reading a JSON attribute, then saving | No change; nothing written | `ClassCastTest#readingJsonDoesNotDirty` |
| E25 | Changing the map a JSON cast returned | Dirty; written on save | `ClassCastTest#mutatingReadMapMarksDirty` |
| E26 | `datetime:F` or `timestamp` set | The storage form is stored; F is used only when serializing | `DateCastsTest#customFormatStoresStorageForm`, `DateCastsTest#timestampRoundTripsSeconds` |
| E27 | A number set on a `datetime` cast | Refused as ambiguous | `DateCastsTest#datetimeSetLongIsAmbiguous` |
| E28 | Broken JSON, non-numeric `int` text or an unknown enum value in the database | `InvalidCastException` naming model, key and cast, not the value | `JsonCastTest#brokenJsonThrows`, `PrimitiveCastsTest#intGarbageThrows`, `PrimitiveCastsTest#errorMessageNamesTypesNotValues`, `EnumCastTest#unknownValueThrows` |
| E29 | Cast written as `DateTime:yyyy-MM-dd` | Type is case-insensitive, format kept | `CastSpecParserTest#typeNameIsCaseInsensitiveFormatIsKept` |
| E30 | Unknown cast name or uncompilable format | `InvalidCastException` when casts resolve | `CastSpecParserTest#unknownCastFails`, `CastSpecParserTest#uncompilableFormatFails`, `ClassCastTest#invalidSpecIsWrappedWithModelAndKey` |
| E31 | Key with both an accessor and a cast | The accessor wins; the cast is not applied | `AccessorTest#accessorBeatsCast` |
| E32 | A computed accessor read after a column it uses changed, or after `increment` | The new value; the cache was cleared | `AccessorTest#computedAccessorSeesNewColumns`, `AccessorTest#realIncrementClearsAccessorCache` |
| E33 | Save after reading a cached accessor | The cached object is not written back | `AccessorTest#saveDoesNotWriteStaleObjectBack` |
| E34 | `visible` and `hidden` both set | Visible filters first, hidden then removes | `ModelSerializationTest#visibleThenHidden` |
| E35 | An appended key without an accessor | `IllegalStateException` at `toMap` | `ModelSerializationTest#appendWithoutAccessorFails` |
| E36 | NaN in a value given to `toJson` | `JsonException`, as Laravel | `ModelSerializationTest#nanBreaksToJson` |
| E37 | A JSON number larger than `Long.MAX_VALUE` or with a fraction | Kept exact through read and write | `JsonCastTest#bigIntegerSurvivesRoundTrip`, `JsonCodecTest#reencodeNeverRounds` |
| E38 | Non-strict JSON (comments, trailing commas, trailing text) | Refused; the message gives the position, not the input | `JsonCodecTest#rejectsNonStrictInput`, `JsonCodecTest#decodeMessagesNeverEchoInput` |
| E39 | A model that overrides `jsonCodec()` | Its codec is used | `JsonCastTest#customCodecIsUsed` |
| E40 | A `decimal:2` cast on a REAL column holding `0.1` (SQLite) | Reads `0.10`; setting `0.10` leaves the model clean | `SQLiteCastsTest#decimalFromRealColumn` |
| E41 | Only a JSON cast attribute changes, then save | Only that column and `updated_at` change | `SQLiteCastsTest#saveWritesOnlyDirtyCast` |
| E42 | PostgreSQL `jsonb` returns keys in a different order | The model stays clean after save and reload | `PostgresCastsTest#jsonbReorderStaysClean` |
| E43 | PostgreSQL `numeric`, `timestamptz`, `date` and `float8` Infinity columns | Each reads through its cast; Infinity breaks `toJson` | `PostgresCastsTest#columnTypesRead`, `PostgresCastsTest#infinityBreaksToJson` |
| E44 | Casts on Android SQLite with text, integer and real storage | Same results as on the JVM | `AndroidCastsTest#decimalFromRealColumn`, `AndroidCastsTest#decimalFromIntegerColumn` |
| E45 | A stored number like `1e-999999999` read through `int` or `decimal:N` | Reads as zero at once, no hang | `PrimitiveCastsTest#intTinyExponentReadsAsZeroQuickly`, `DecimalCastTest#tinyExponentRoundsToZeroQuickly` |
| E46 | A relation name that is not in `relations()` | `RelationNotFoundException` listing the known names | `RelationRegistryTest#unknownNameListsKnownNames`, `RelationRegistryTest#typoInMapSurfacesAsNotFound` |
| E47 | A relation map for another class, a null factory, or a related class without a `ModelQuery` entry | `InvalidRelationException` at first use, the same one on every later use | `RelationRegistryTest#mapClassMustMatch`, `RelationRegistryTest#nullFactoryRejected`, `RelationRegistryTest#unregisteredRelatedRejected`, `RelationRegistryTest#failedCheckRethrownSameMessage` |
| E48 | Two threads use a model class's relations for the first time together | One check; no constraint leaks between threads | `RelationConcurrencyTest#registryInitRace`, `RelationConcurrencyTest#noConstraintLeak` |
| E49 | `getAttribute("posts")` | Attributes only; no relation is loaded | `ModelRelationsTest#getAttributeDoesNotFallBackToRelations` |
| E50 | A lazy read when the parent key or foreign key is null | Null or empty, no query | `HasManyTest#nullParentKeyRunsNoQuery`, `HasOneTest#nullParentKeyRunsNoQuery`, `BelongsToTest#nullForeignKeyReturnsNullWithoutQuery` |
| E51 | Eager load where some parents have a null key | Null keys dropped, never read as `0`; all null runs no query | `EagerKeysTest#nullParentKeyNeverBecomesZero`, `EagerKeysTest#allNullKeysRunNoQuery` |
| E52 | Key `"1"` against `1`, or `"01"`, `"1.0"`, `"-0"` against `1` and `0` | `"1"` matches; the others do not | `EagerKeysTest#canonicalIntegerTextMatches`, `EagerKeysTest#nonCanonicalTextIsNotNormalised`, `EagerKeysTest#minusZeroTextNotNormalised` |
| E53 | More bound keys than one statement allows | Split per database; a constraint's own values shrink the chunk | `EagerLoadTest#probeShrinksBoundChunk`, `EagerLoadTest#nestedLoadedOnceAcrossChunks` |
| E54 | 45,000 integer keys on SQLite | Inlined in three queries | `EagerKeysTest#fortyFiveThousandKeysMakeThreeSQLiteInlineChunks`, `EagerKeysTest#hugeInlinedListChunked` |
| E55 | Eager constraint with `limit` or `offset` | `IllegalStateException` | `EagerLoadTest#constrainedLimitRefused`, `EagerLoadTest#constrainedOffsetRefused` |
| E56 | An eager constraint with an `or` | Not grouped, as Laravel | `EagerLoadTest#orInConstraintDocumentedParity` |
| E57 | A one-to-one relation with two matching rows | The first row wins | `HasOneTest#firstChildWinsOnDuplicates` |
| E58 | `with("posts:id,title")` without the foreign key | The key is added so matching works | `EagerLoadTest#selectConstraintKeepsKey` |
| E59 | `loadMissing` on a relation loaded as null | Not retried | `LoadMissingTest#loadedNullNotRetried` |
| E60 | `cursor()` with eager loads | `IllegalStateException`; `lazy()` loads per page | `EagerLoadTest#cursorWithEagerRefused`, `EagerLoadTest#lazyBuffersAndLoads` |
| E61 | `load` on a collection of mixed model classes | `IllegalArgumentException` | `EagerLoadTest#mixedClassesRefused`, `EagerLoadTest#mixedCollectionRejected` |
| E62 | `whereHas` on a relation to the same table, nested | A new alias at each level, the same SQL every time | `RelationExistenceTest#selfRelationAliased`, `RelationExistenceTest#nestedSelfAliasesIncrement`, `RelationExistenceTest#selfAliasIsDeterministic` |
| E63 | `doesntHave("posts.comments")` | Users with no post that has a comment | `RelationExistenceTest#nestedDoesntHave` |
| E64 | `whereHas` with a constraint that adds no `where` | No empty group is written | `RelationExistenceTest#emptyConstraintAddsNoGroup` |
| E65 | `save` or `create` through a relation of an unsaved parent | `IllegalStateException` before any SQL | `RelationWritesTest#nullParentKeyRefused` |
| E66 | `associate` a parent without a key | `IllegalStateException`; the child is unchanged | `RelationWritesTest#nullOwnerKeyInAssociateRefused` |
| E67 | `withDefault` on a missing one-to-one or inverse | A new default each time; a one-to-one default has its foreign key set | `WithDefaultTest#hasOneDefaultHasForeignKey`, `WithDefaultTest#belongsToDefaultIsBare`, `WithDefaultTest#defaultIsNewOnEveryUse` |
| E68 | `toMap()` with a cycle (a post and its author's posts) | Ends; the repeated model has attributes only | `RelationSerializationTest#cycleTerminates`, `RelationSerializationTest#thousandTwoWayTerminates` |
| E69 | A hidden relation, or a relation named like an attribute | Hidden is left out; the relation overwrites the attribute | `RelationSerializationTest#hiddenRelation`, `RelationSerializationTest#relationOverwritesSameNamedAttribute` |
| E70 | Lazy read with lazy loading prevented, on a model from a multi-row result | `LazyLoadingViolationException`, no query | `LazyLoadingPreventionTest#multiRowModelBlocked` |
| E71 | The same read on a single-row result, a new model or an eager-loaded relation | Allowed | `LazyLoadingPreventionTest#singleRowAndNewAllowed`, `LazyLoadingPreventionTest#eagerLoadedNotBlocked` |
| E72 | `refresh()` on a model with loaded relations | The relations are reloaded | `ModelRelationsTest#refreshReloadsRelations` |
