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

### Typed values from casts

As an application developer, I want to declare a cast per column, so that a decimal reads as a
`BigDecimal`, a JSON column as a map and a datetime as an `Instant`.

Done when: every built-in cast reads and writes typed values on SQLite, PostgreSQL and Android, and
bad stored data throws an exception naming the model, the key and the cast.

### A dirty check that knows the cast

As an application developer, I want a column marked dirty only when its meaning changed, so that
re-ordered JSON keys, the last bit of a float or the same instant in another zone cause no update.

Done when: reading a cast value never dirties a model, and each difference from Laravel's dirty
check has a test.

### Accessors and mutators without reflection

As an application developer, I want computed and normalised attributes registered by lambdas, so
that they work after R8.

Done when: an accessor wins over the cast, a mutator can write several columns, and a cached
accessor value is cleared by any write.

### Control over what leaves the model

As an API developer, I want `hidden`, `visible` and `appends` with `toMap` and `toJson`, so that a
password never reaches a response.

Done when: visible filters first, hidden then removes, appended accessors are added, and a model
can plug in its own JSON library.

### Related models without reflection

As an Android developer, I want relations declared by typed methods and a name map, so that R8
cannot break them and the compiler checks the types.

Done when: a relation declared wrongly fails at first use naming the model and the relation, and
the same classes run on SQLite, PostgreSQL and Android.

### No N+1 queries

As an application developer, I want to eager load relations, so that a list of users with their
posts and comments costs one query per level.

Done when: `with`, `load` and `loadMissing` load nested and constrained relations, large key lists
stay under the database's limits, and lazy loading can be refused for multi-row results.

### Filtering by related rows

As an application developer, I want `has`, `whereHas` and `whereRelation`, so that I can find
users with matching posts without writing joins.

Done when: each form compiles to an `exists` subquery, a self relation gets a stable alias, and
the SQL is the same every time.

### Writing through a relation

As an application developer, I want `user.posts().create(...)` and `post.author().associate(user)`,
so that foreign keys are set for me.

Done when: the foreign key is set on every write, and a write from a parent without a key is
refused before any SQL.

### Counting related rows

As an application developer, I want `withCount("posts")` and the other aggregates, so that I can
list users with their post totals without loading the posts.

Done when: each aggregate is one subselect with Laravel's alias, an `as` alias and a constraint
closure work, `withExists` reads as a boolean on every database, and a mistyped relation or
function fails when the method is called.

### Aggregates on models I already hold

As an application developer, I want `loadCount` and the other `load*` methods, so that I can add a
total to models already in memory with one query.

Done when: the query selects only the key and the aggregates, the models stay clean, a deleted row
is skipped, and thousands of string keys are split so SQLite accepts every statement.

### Casts for one instance

As an application developer, I want `mergeCasts` and `withCasts`, so that one model or one query
can read a column as a type without changing the class for everyone.

Done when: the cast affects only the instance or the query's models, and the class casts and other
instances are unchanged.

### Touching parents

As an application developer, I want a comment's save to bump its post's `updated_at`, so that the
post shows when its thread last changed.

Done when: a changed save or a delete costs one `UPDATE` per touched relation and no `SELECT`,
loaded owners are touched in turn and each once, a cycle ends, and `withoutTouching` on one thread
never switches touching off on another.

### Saving a loaded graph

As an application developer, I want `post.push()`, so that the post and everything I loaded under
it are saved in one call.

Done when: the model is saved first, then the loaded relations in order, the first failure stops
the walk, and a cycle or a shared model is saved once.

### Children that know their parent

As an application developer, I want `chaperone()`, so that a child read through its parent points
back to it without a query.

Done when: the inverse is guessed or checked at the call, every lazy, eager and write path links
the same parent instance, and `toJson()` of the linked graph ends.

