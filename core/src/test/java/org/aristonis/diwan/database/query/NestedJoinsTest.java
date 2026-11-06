package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.TestQueries.db;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A join whose table is itself a parenthesised join, from Laravel's DatabaseQueryBuilderTest. */
class NestedJoinsTest {

    @Test
    void aJoinInsideAJoinClauseIsWrittenInParentheses() {
        Builder query = db().table("users").select("users.id", "contacts.id", "contact_types.id")
                .leftJoin("contacts", j -> j.on("users.id", "contacts.id")
                        .join("contact_types", "contacts.contact_type_id", "=", "contact_types.id"));

        assertEquals("select \"users\".\"id\", \"contacts\".\"id\", \"contact_types\".\"id\" from \"users\" "
                + "left join (\"contacts\" inner join \"contact_types\" on \"contacts\".\"contact_type_id\" = "
                + "\"contact_types\".\"id\") on \"users\".\"id\" = \"contacts\".\"id\"", query.toSql());
    }

    @Test
    void severalLevelsOfNestedJoinsKeepTheirBindingsInOrder() {
        Builder query = db().table("users")
                .select("users.id", "contacts.id", "contact_types.id", "countries.id", "planets.id")
                .leftJoin("contacts", j -> j.on("users.id", "contacts.id")
                        .join("contact_types", "contacts.contact_type_id", "=", "contact_types.id")
                        .leftJoin("countries", q -> q.on("contacts.country", "=", "countries.country")
                                .join("planets", p -> p.on("countries.planet_id", "=", "planet.id")
                                        .where("planet.is_settled", "=", 1)
                                        .where("planet.population", ">=", 10000))));

        assertEquals("select \"users\".\"id\", \"contacts\".\"id\", \"contact_types\".\"id\", \"countries\".\"id\", "
                + "\"planets\".\"id\" from \"users\" left join (\"contacts\" inner join \"contact_types\" on "
                + "\"contacts\".\"contact_type_id\" = \"contact_types\".\"id\" left join (\"countries\" inner join "
                + "\"planets\" on \"countries\".\"planet_id\" = \"planet\".\"id\" and \"planet\".\"is_settled\" = ? "
                + "and \"planet\".\"population\" >= ?) on \"contacts\".\"country\" = \"countries\".\"country\") "
                + "on \"users\".\"id\" = \"contacts\".\"id\"", query.toSql());
        assertEquals(List.of(1, 10000), query.getBindings());
    }

    @Test
    void aNestedJoinWithAnExistsSubqueryInTheOnConditionBindsInOrder() {
        Builder query = db().table("users").select("users.id", "contacts.id", "contact_types.id")
                .leftJoin("contacts", j -> j.on("users.id", "contacts.id")
                        .join("contact_types", "contacts.contact_type_id", "=", "contact_types.id")
                        .whereExists(q -> q.select("*").from("countries")
                                .whereColumn("contacts.country", "=", "countries.country")
                                .join("planets", p -> p.on("countries.planet_id", "=", "planet.id")
                                        .where("planet.is_settled", "=", 1))
                                .where("planet.population", ">=", 10000)));

        assertEquals("select \"users\".\"id\", \"contacts\".\"id\", \"contact_types\".\"id\" from \"users\" "
                + "left join (\"contacts\" inner join \"contact_types\" on \"contacts\".\"contact_type_id\" = "
                + "\"contact_types\".\"id\") on \"users\".\"id\" = \"contacts\".\"id\" and exists (select * from "
                + "\"countries\" inner join \"planets\" on \"countries\".\"planet_id\" = \"planet\".\"id\" and "
                + "\"planet\".\"is_settled\" = ? where \"contacts\".\"country\" = \"countries\".\"country\" and "
                + "\"planet\".\"population\" >= ?)", query.toSql());
        assertEquals(List.of(1, 10000), query.getBindings());
    }
}
