# Models: user stories

### A class per table

As an application developer, I want a class per table with `find`, `save` and `delete`, so that I
stop passing maps around.

Done when: a model with only its `query` entry does create, read, update and delete on SQLite,
PostgreSQL and Android.

### Only changed columns written

As an application developer, I want only changed columns written and timestamps kept for me, so
that updates are small and the audit columns are right.

Done when: `save` on a clean model runs nothing, and an update writes the changed columns plus
`updated_at`.

### Safe filling from input

As an application developer, I want request data filled safely, so that a column I did not allow
is never written.

Done when: guarded keys are dropped or refused with Laravel's messages, and `forceFill` is the
only way past the guards.

### Laravel's finders and creators

As an application developer, I want `firstOrCreate`, `updateOrCreate` and `findOrFail`, so that
common lookups are one call.

Done when: they return models, and a missing model throws `ModelNotFoundException` with
Laravel's message.

### Models on Android

As an Android developer, I want models that work without reflection, annotations or build
plugins, so that R8 cannot break them.

Done when: the Android tests pass with the same model classes as the JDBC tests.

### Same names as Eloquent

As a Laravel developer, I want Eloquent's names and behavior, so that I do not relearn the API.

Done when: the SQL of each model call equals the query builder's SQL for the same Laravel call.
