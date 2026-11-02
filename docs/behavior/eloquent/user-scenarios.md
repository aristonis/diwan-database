# Models: user scenarios

## Normal flow

1. The developer defines `User extends Model` with a `query` entry and a `fillable` list.
2. They create a user from request data and get the new id back.
3. They find the user, change the name and save; only `name` and `updated_at` are written.
4. They delete the user.

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
