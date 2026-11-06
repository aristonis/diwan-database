/**
 * Diwan Database Eloquent: models, casts and relations over the query builder.
 *
 * <p>The {@code internal} package is not exported; it is not part of the API.
 */
module org.aristonis.diwan.database.eloquent {
    requires transitive org.aristonis.diwan.database;

    exports org.aristonis.diwan.database.eloquent;
    exports org.aristonis.diwan.database.eloquent.casts;
    exports org.aristonis.diwan.database.eloquent.exception;
    exports org.aristonis.diwan.database.eloquent.json;
    exports org.aristonis.diwan.database.eloquent.page;
    exports org.aristonis.diwan.database.eloquent.relations;
    exports org.aristonis.diwan.database.eloquent.scope;
}
