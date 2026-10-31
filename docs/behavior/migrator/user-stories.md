# Migrator: user stories

### Bring the database up to date

As an application developer, I want to run every migration that has not run yet, in order, so that
every install ends with the same schema.

Done when: a second run does nothing and a failure leaves no half-applied migration where the
database allows it.

### Undo the last change

As an application developer, I want to roll back the last batch, a number of steps or everything,
so that I can fix a bad migration.

Done when: rolled-back migrations can run again.

### Know where the database stands

As a release pipeline, I want to see which migrations ran and which are pending, so that I know what
a deploy will change.

Done when: status lists ran, pending and unknown records.

### Review before changing

As a release pipeline, I want the SQL a run or rollback would execute without executing it, so that
a person can review it.

Done when: pretend returns the SQL and nothing changes.

## Turned down

| Story | Reason |
|---|---|
| Run from a command line | Comes in a later release |
| Two servers migrating at once safely | Needs a database lock; the hook is there |
