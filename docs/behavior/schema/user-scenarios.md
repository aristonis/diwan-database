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
