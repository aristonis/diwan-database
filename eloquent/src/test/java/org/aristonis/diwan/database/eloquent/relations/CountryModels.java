package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/** Countries keyed by a text code, each with cities: the string-key models of the chunking tests. */
final class CountryModels {

    private CountryModels() {
    }

    /** Initialises both classes so their {@link ModelQuery} entries are registered. */
    static void register() {
        if (Country.QUERY == null || City.QUERY == null) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /** A country: the key is the text {@code code}; has many cities. */
    static final class Country extends Model {
        static final ModelQuery<Country> QUERY = ModelQuery.of(Country.class, Country::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected String primaryKey() {
            return "code";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Country.class).add("cities", Country::cities);
        }

        public HasMany<City> cities() {
            return hasMany(City.class, "country_code");
        }
    }

    /** A city of a country. */
    static final class City extends Model {
        static final ModelQuery<City> QUERY = ModelQuery.of(City.class, City::new);

        @Override
        protected String table() {
            return "cities";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }
}
