# Schema builder: user scenarios

## Normal flow

1. The developer creates a users table with an id, a name, a unique email, an active flag and
   timestamps.
2. A later migration adds a column.
3. A rollback drops the table.
4. The migrator checks whether a table or column exists before acting.

## Edge cases

| # | Situation | What happens | Test |
|---|---|---|---|
| E1 | Two rows with the same value in a unique column | Duplicate-key error; the index has Laravel's name | `SQLiteSchemaTest#uniqueColumnRejectsDuplicatesAndHasLaravelsIndexName` |
| E2 | `dropIfExists` on a table that is not there | Nothing happens | `SQLiteSchemaTest#dropsRenamesAndChecksTables` |
| E3 | `drop` on a table that is not there | Database error | same test as E2 |
| E4 | Creating a table that already exists | Database error | same test as E2 |
| E5 | Asking for a column with different letter case | Found | same test as E2 |
| E6 | The app uses a table prefix | Every table and generated index gets it | `SQLiteSchemaTest#everyOperationUsesTheTablePrefix` |
| E7 | A name contains a quote, space or SQL word | A plain name; the SQL cannot break out | `SQLiteSchemaTest#hostileNamesAreOnlyNames`, `SchemaTest#quotesNamesAndEscapesDefaults` |
| E8 | A name is empty or has hidden characters | Refused before anything runs | `BlueprintTest#refusesInvalidColumns`, `SchemaTest#checksArgumentsBeforeAnySqlRuns` |
| E9 | A default text value contains a quote | Stored exactly as given | `SQLiteSchemaTest#hostileNamesAreOnlyNames` |
| E10 | No columns, the same column twice, a bad length or decimal size | Refused before anything runs | `BlueprintTest#refusesInvalidColumns`, `BlueprintTest#prepareRefusesEmptyTablesAndDuplicateColumns` |
| E11 | The same index name twice, or one too long for the database | Refused before anything runs | `BlueprintTest#repeatedOrTooLongIndexNamesAreRefused`, `BlueprintTest#longGeneratedIndexNamesAreRefusedButExplicitNamesPass` |
| E12 | Adding columns to a table with rows | Rows kept; new columns get their default or `NULL` | `SQLiteSchemaTest#tableAddsColumnsAndKeepsRows` |
| E13 | Adding a not-null column without a default to a table with rows | Database error | `SQLiteSchemaTest#addingANotNullColumnWithoutDefaultToATableIsADatabaseError` |
| E14 | The unique index fails after the table was created | Error; the table is rolled back too | `SchemaTest#severalStatementsRunInOneTransactionOnTransactionalDdl` |
| E15 | Running in pretend mode | SQL listed, nothing changed | `SQLiteSchemaTest#pretendRecordsTheStatementsAndChangesNothing` |
| E16 | `comment` or `unsigned` on SQLite | Accepted and ignored | `SQLiteSchemaTest#createsEveryColumnTypeWithItsModifiers` |
| E17 | Current date or time as a default | Filled in by the database on insert | `SQLiteSchemaTest#idCountsUpAndDefaultsAreApplied` |
| E18 | `defaultValue(null)` | The default is SQL `NULL` | `SchemaTest#defaultNullIsWrittenAsSqlNull` |
| E19 | A grammar lacks a command or column type | Error naming it and the grammar | `SchemaTest#baseGrammarDropsAndRefusesWhatItDoesNotKnow`, `SchemaTest#unknownCommandsAreRefused` |
| E20 | Composite, unique and primary indexes with default or chosen names | Created; listed by `indexes` | `SQLiteKeysTest#compositeIndexesAndKeysAreCreatedWithLaravelNames` |
| E21 | Deleting a user whose posts cascade | Posts and their comments go too | `MigratorKeysTest#deletingAUserCascadesThroughPostsToComments` |
| E22 | Restrict, set null, no action, cascade on update | Each behaves as declared | `SQLiteKeysTest#eachActionBehavesAsDeclared` |
| E23 | Adding a key to a filled table others point at | Every row kept in both; the key enforced after | `MigratorKeysTest#aLaterReleaseAddsAKeyToAReferencedTableWithoutLosingChildren` |
| E24 | A row already breaks the new key | Whole change undone; error names tables and count, no values | `SQLiteRebuildTest#aRowThatBreaksTheNewKeyUndoesTheWholeChange` |
| E25 | Dropping a primary key | Rows, indexes and inline unique constraints kept | `SQLiteRebuildTest#droppingThePrimaryKeyKeepsRowsIndexesAndUniqueConstraints` |
| E26 | Auto-increment table rebuilt after its top rows were deleted | Their ids are not handed out again | `SQLiteRebuildTest#anAutoIncrementTableKeepsItsCounter` |
| E27 | Add, rename and constrain in one call | All data kept | `SQLiteRebuildTest#aBlueprintThatAddsRenamesAndConstrainsKeepsAllData` |
| E28 | SQLite older than 3.25 or 3.35 | Rename and drop column rebuild the table | `SQLiteRebuildTest#olderSQLiteRenamesAndDropsColumnsByRebuilding` |
| E29 | Rebuild of a referenced table inside the caller's transaction | Refused, naming the way out | `SQLiteRebuildTest#insideACallersTransactionAReferencedTableIsNotRebuilt` |
| E30 | Table with CHECK, trigger or view | Rebuild refused | `SQLiteRebuildTest#tablesWithFeaturesARebuildWouldLoseAreRefused` |
| E31 | Dropping an indexed column | Refused naming the index | `SQLiteKeysTest#anIndexedColumnCannotBeDroppedButItsIndexFirstThenItCan` |
| E32 | `dropForeign(name)` on SQLite | Refused: use the columns form | `SQLiteKeysTest#droppingAForeignKeyByNameIsRefused` |
| E33 | Pretend a migration that rebuilds | Real statements listed; nothing changes | `MigratorKeysTest#pretendListsTheRebuildAndTouchesNothing` |
| E34 | Incomplete foreign key, empty or repeated columns | Refused before anything runs | `BlueprintKeysTest#incompleteForeignKeysAreRefused`, `BlueprintKeysTest#emptyOrRepeatedColumnListsAreRefused` |
| E35 | Every column type on SQLite | Laravel's SQLite type | `SQLiteTypesTest#everyTypeHasLaravelsSqliteType` |
| E36 | Enum value outside the list, or one with quotes and SQL | Refused; the hostile one stored literally | `SQLiteTypesTest#enumRefusesOtherValuesAndKeepsHostileOnesLiteral` |
| E37 | `year().useCurrent()`, `time().useCurrent()` | Current year and time filled in | `SQLiteTypesTest#currentDefaultsAreFilledIn` |
| E38 | `set` on SQLite | Refused | `SQLiteTypesTest#setIsRefusedOnSqlite` |
| E39 | Table with an enum gets a key later, or the enum is renamed on old SQLite | Check kept under the current name | `SQLiteEnumRebuildTest#anEnumSurvivesARebuildWithItsCheck`, `#aRenamedEnumKeepsItsCheckOnOldSqlite` |
| E40 | A check on another column, a table-level check, or an enum check written differently | Rebuild refused | `SQLiteEnumRebuildTest#aCheckOnAnotherColumnIsRefused`, `#aTableLevelCheckIsRefusedEvenInEnumShape`, `#anEnumCheckWrittenDifferentlyIsRefused` |
| E41 | A default text that contains `check`, `collate` or `as` | Not mistaken for a constraint | `SQLiteEnumRebuildTest#wordsInsideLiteralsDoNotTripTheRefusals` |
| E42 | Helpers and drop helpers in a real migration and rollback | Columns and index added and removed | `MigratorTypesTest#helpersAddAndDropTheirColumns` |
| E43 | Empty, null or repeated enum values; bad lengths and precisions | Refused before anything runs | `BlueprintTypesTest#invalidValuesAndRangesAreRefused` |
| E44 | A nullable column changed without `nullable()` | Becomes not null | `SQLiteChangeColumnTest#aNullableColumnChangedWithoutNullableBecomesNotNull` |
| E45 | Null rows, then a change to not null | Fails; table and rows as before | `SQLiteChangeColumnTest#rowsThatBreakTheNewDefinitionLeaveTheTableAsItWas` |
| E46 | An enum list shrinks while a row holds a removed value | Fails; table as before | `SQLiteChangeColumnTest#aRemovedEnumValueStillInUseFails` |
| E47 | `change()` on a missing column | Refused | `SQLiteChangeColumnTest#aMissingColumnIsRefused` |
| E48 | `change()` inside `Schema.create` | Refused before anything runs | `ChangeColumnBlueprintTest#changeInsideCreateIsRefused` |
| E49 | A change to or from auto-increment | Refused | `ChangeColumnBlueprintTest#changeToAutoIncrementIsRefused`, `SQLiteChangeColumnTest#theAutoIncrementColumnCannotBeChanged` |
| E50 | A changed column that another table's key points at, or that has its own key | Keys kept and enforced | `SQLiteChangeColumnTest#indexesKeysBothWaysAndTheSequenceSurvive` |
| E51 | `unique(false).change()` | The conventional unique index is dropped | `SQLiteChangeColumnTest#uniqueFalseDropsAndUniqueAddsTheIndex` |
| E52 | A column changed and renamed, or changed twice, in one call | Refused before anything runs | `ChangeColumnBlueprintTest#changeAndRenameOfOneColumnAreRefusedEitherWay`, `ChangeColumnBlueprintTest#theSameColumnChangedTwiceIsRefused` |
| E53 | `change()` on a table the rebuild cannot carry | Still refused | `SQLiteChangeColumnTest#aTableTheRebuildCannotCarryStaysRefused` |
| E54 | `change()` under pretend | SQL listed, table unchanged | `SQLiteChangeColumnTest#pretendListsTheRebuildAndChangesNothing` |
| E55 | Insert breaks a column check | Refused by the database | `SQLiteChecksTest#aColumnCheckRefusesABadRow` |
| E56 | A check added to a table whose rows already break it | Fails; table unchanged | `SQLiteChecksRebuildTest#addingACheckTestsTheRowsAlreadyThere` |
| E57 | `dropCheck` of a name that does not exist | Refused naming it | `SQLiteChecksTest#droppingAMissingCheckIsRefusedByName` |
| E58 | A stored column added to a filled table | Rebuilt; old rows get computed values | `SQLiteChecksRebuildTest#addingAStoredColumnComputesItForOldRows` |
| E59 | A virtual column added to a filled table | In place; values computed | `SQLiteChecksTest#aVirtualColumnIsAddedInPlace` |
| E60 | A generated column with a default, useCurrent, auto-increment, a primary key, or both kinds | Refused before anything runs | `ChecksBlueprintTest#impossibleGeneratedColumnsAreRefused` |
| E61 | Hand-written checks, named and unnamed, with `)` and quotes inside strings | Carried intact | `SQLiteChecksRebuildTest#handWrittenChecksWithTrickyTextAreCarried` |
| E62 | Dropping or renaming a column a check names, on the rebuild path | Refused | `SQLiteChecksRebuildTest#renamingOrDroppingAMentionedColumnOnTheRebuildPathIsRefused` |
| E63 | A column renamed in place, then a rebuild in the same call, while a check names it | Refused | `SQLiteChecksRebuildTest#aNativeRenameFollowedByARebuildIsRefusedWhenMentioned` |
| E64 | `change()` of a generated column's expression | Rebuilt with the new expression | `SQLiteChecksRebuildTest#changingAGeneratedColumnWritesTheNewExpression` |
| E65 | Enum check and a user check on the same column, then a key change | Both carried | `SQLiteChecksRebuildTest#aKeyChangeCarriesChecksGeneratedColumnsAndTheEnum` |
| E66 | Generated column on SQLite older than 3.31 | Refused naming the version | `SQLiteChecksTest#generatedColumnsOnAnOldSqliteAreRefused` |
| E67 | A migration branches on `driverName()` | The SQLite branch runs | `SQLiteChecksTest#driverNameLetsAMigrationBranch` |
| E68 | `Schema.statement` under pretend | Listed, not run | `SQLiteChecksTest#statementRunsAndPretendOnlyListsIt` |
| E69 | `rawColumn` | Created as written | `SQLiteChecksTest#aRawColumnIsCreatedAsWritten` |
| E70 | PostgreSQL: every type | Laravel's PostgreSQL types | `PostgresTypesTest#everyTypeIsCreatedWithLaravelsPostgresType` |
| E71 | PostgreSQL: `set` | Refused naming the grammar | `PostgresTypesTest#setIsRefused` |
| E72 | PostgreSQL: `virtualAs` before version 18 | Refused with the version | `PostgresTypesTest#virtualColumnsFollowTheServerVersion` |
| E73 | PostgreSQL: `change()` with data that cannot convert | Fails; table as before | `PostgresSchemaTest#aChangeThatCannotConvertLeavesTheTableAsItWas` |
| E74 | PostgreSQL: a statement in a blueprint fails | Nothing left behind | `PostgresSchemaTest#aFailingStatementLeavesNothingBehind` |
| E75 | PostgreSQL: a table with the same name in another schema | Not seen | `PostgresSchemaTest#aTableInAnotherSchemaIsNotSeen` |
| E76 | PostgreSQL: renaming a column a check names | The check follows | `PostgresSchemaTest#aRenamedColumnStaysInItsCheck` |
| E77 | PostgreSQL: two migrators at once | The second waits, then finds nothing to run | `PostgresMigratorTest#aSecondMigratorWaitsForTheLockThenFindsNothingToRun` |
| E78 | PostgreSQL: blog migrations up, down, up | All succeed | `PostgresMigratorTest#blogMigrationsRunRollBackAndRunAgain` |
