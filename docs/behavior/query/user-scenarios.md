# Query builder: user scenarios

## Normal flow

1. The developer gets a factory for the connection and calls `table("orders")`.
2. They add conditions, an order and a limit, and get rows back.
3. They insert an order and get its id, update it, and delete it.

## Edge cases

| # | Situation | What happens | Test |
|---|---|---|---|
| E1 | A column compared to `null` with `=` or no operator | Matches rows where the column is null (`is null`) | `BuilderStateTest#nullValueBecomesIsNullAndNotEqualsBecomesIsNotNull` |
| E2 | A column compared to `null` with `>` or another comparison | Refused before anything runs | `BuilderStateTest#nullWithAComparisonOperatorIsRejected` |
| E3 | A word that is not an operator passed as the operator | Taken as the value, compared with `=` | `BuilderStateTest#unknownOperatorBecomesTheValueWithEquals`, `SQLiteQueryGrammarTest#anUnknownOperatorIsTheValue` |
| E4 | `whereIn` with an empty list | Matches nothing; `whereNotIn` with one matches everything | `QueryGrammarTest#inWheresAndTheirEmptyForms` |
| E5 | A nested group that adds no condition | No condition and no empty brackets | `BuilderStateTest#emptyNestedWhereAddsNothing` |
| E6 | Counting a query that has an order and a limit | Counts every matching row; the builder still works after | `BuilderExecutionTest#countDropsColumnsOrdersLimitAndOffsetOnlyOnAClone`, `SQLiteQueryIntegrationTest#countIgnoresOrderLimitAndOffset` |
| E7 | Inserting an empty list of rows | Nothing runs; reported as success | `BuilderExecutionTest#anEmptyInsertRunsNothing` |
| E8 | Inserting many rows where one has different fields | Refused before anything is written | `QueryGrammarTest#insertRowsWithDifferentKeysAreRejected` |
| E9 | Getting the new id on an older Android SQLite without `returning` | The id comes back through `last_insert_rowid()` on the same session | `SQLiteQueryIntegrationTest#insertGetIdFallsBackToLastInsertRowidOnAnOldSQLite`, `AndroidQueryTest#insertGetIdReturnsTheNewKeyOnTheAndroidSqliteVersion` |
| E10 | A table whose key is not an integer rowid, on SQLite before 3.35 | Needs SQLite 3.35 or later (`returning`) | `SQLiteQueryIntegrationTest#insertGetIdReturnsANonIntegerKeyWithReturning` |
| E11 | Update or delete with a limit | Changes at most that many rows on both databases | `SQLiteQueryIntegrationTest#updateIncrementAndDeleteRespectALimit`, `PostgresQueryIntegrationTest#updateIncrementAndDeleteWithLimitTouchAtMostThatMany` |
| E12 | Emptying a table | Rows gone; the next id starts again from 1 | `SQLiteQueryIntegrationTest#truncateEmptiesTheTableAndResetsIds`, `PostgresQueryIntegrationTest#truncateRestartsTheIdentity` |
| E13 | `toRawSql` with a value holding a quote or a question mark | Quote doubled; question marks inside text left alone | `QueryGrammarTest#rawSqlLeavesQuestionMarksInsideStringLiteralsAlone` |
| E14 | A raw expression used as a value | Written into the SQL as given, never bound | `BuilderStateTest#rawValuesAreNeverBound` |
| E15 | A money amount or an `Instant` written and read back | Same value on every database, no rounding, no time-zone drift | `SQLiteQueryIntegrationTest#moneyAndInstantsRoundTrip`, `PostgresQueryIntegrationTest#moneyAndInstantsRoundTrip`, `AndroidQueryTest#moneyAndInstantRoundTrip` |
| E16 | A date where on an instant column | Not supported (SQLite reads microseconds as days); use `whereInstantBetween` | `SQLiteQueryIntegrationTest#whereInstantBetweenIncludesTheStartAndExcludesTheEnd` |
| E17 | A copy of a query is changed | The original is unchanged | `BuilderStateTest#cloneSharesNoMutableStateWithTheOriginal`, `SQLiteQueryIntegrationTest#aCloneIsIndependent` |
| E18 | A sort direction other than `asc` or `desc` | Refused | `BuilderStateTest#invalidSortDirectionIsRejected` |
| E19 | A negative limit or offset | Limit ignored, offset 0 | `BuilderStateTest#negativeLimitIsIgnoredAndNegativeOffsetBecomesZero` |
| E20 | A table or column name containing a quote | Quoted safely; it cannot break out of the SQL | `QueryGrammarTest#embeddedQuotesInNamesAreDoubled`, `SQLiteQueryGrammarTest#injectedNamesStayInsideQuotes` |
| E21 | A user value or operator containing SQL text | Sent as a parameter; never runs as SQL | `BuilderStateTest#operatorInjectionIsBoundNotWritten` |
| E22 | The same builder used from two threads | Not supported; use one builder per thread or `clone()` | Javadoc of `Builder` |
