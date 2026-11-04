package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * Models for the through-relation tests: countries reach posts through users, mechanics reach an
 * owner through a car, staff reach their team's members, and people reach their reports' posts.
 * Every through relation names all four keys; the seeded rows are on {@link ThroughDb#seed()}.
 */
final class ThroughModels {

    private ThroughModels() {
    }

    /**
     * Initialises every model class, so each {@link ModelQuery} entry is registered before a
     * relation names the class.
     *
     * @throws IllegalStateException if a model entry is not initialised
     */
    static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(Country.QUERY, User.QUERY, Post.QUERY, Mechanic.QUERY,
                Car.QUERY, Owner.QUERY, Staff.QUERY, Team.QUERY, Person.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /**
     * A country: has many users, and reaches its posts through them.
     */
    static final class Country extends Model {
        static final ModelQuery<Country> QUERY = ModelQuery.of(Country.class, Country::new);

        @Override
        protected String table() { return "countries"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Country.class).add("users", Country::users).add("posts", Country::posts);
        }

        /**
         * Returns the users whose {@code country_id} is this country's key.
         *
         * @return the has-many relation, never {@code null}
         */
        public HasMany<User> users() {
            return hasMany(User.class, "country_id");
        }

        /**
         * Returns the posts reached through users: {@code users.country_id} to {@code posts.user_id}.
         *
         * @return the through relation, never {@code null}
         */
        public HasManyThrough<Post> posts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id");
        }
    }

    /**
     * A user: belongs to a country, and has many posts.
     */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected String table() { return "users"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class).add("posts", User::posts);
        }

        /**
         * Returns the posts whose {@code user_id} is this user's key.
         *
         * @return the has-many relation, never {@code null}
         */
        public HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    /**
     * A post: written by a user or by a person.
     */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() { return "posts"; }

        @Override
        protected boolean timestamps() { return false; }
    }

    /**
     * A mechanic: has one owner through the car the mechanic services.
     */
    static final class Mechanic extends Model {
        static final ModelQuery<Mechanic> QUERY = ModelQuery.of(Mechanic.class, Mechanic::new);

        @Override
        protected String table() { return "mechanics"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Mechanic.class)
                    .add("owner", Mechanic::owner)
                    .add("ownerOrDefault", Mechanic::ownerOrDefault);
        }

        /**
         * Returns the owner reached through the car: {@code cars.mechanic_id} to {@code owners.car_id}.
         *
         * @return the one-through-one relation, never {@code null}
         */
        public HasOneThrough<Owner> owner() {
            return hasOneThrough(Owner.class, Car.class, "mechanic_id", "car_id", "id", "id");
        }

        /**
         * Returns the same relation with {@code withDefault()}, so a mechanic without an owner gets an
         * unsaved owner instead of {@code null}.
         *
         * @return the one-through-one relation with a default model, never {@code null}
         */
        public HasOneThrough<Owner> ownerOrDefault() {
            return hasOneThrough(Owner.class, Car.class, "mechanic_id", "car_id", "id", "id").withDefault();
        }
    }

    /**
     * A car: belongs to a mechanic and is owned by an owner.
     */
    static final class Car extends Model {
        static final ModelQuery<Car> QUERY = ModelQuery.of(Car.class, Car::new);

        @Override
        protected String table() { return "cars"; }

        @Override
        protected boolean timestamps() { return false; }
    }

    /**
     * The owner of a car.
     */
    static final class Owner extends Model {
        static final ModelQuery<Owner> QUERY = ModelQuery.of(Owner.class, Owner::new);

        @Override
        protected String table() { return "owners"; }

        @Override
        protected boolean timestamps() { return false; }
    }

    /**
     * A staff member: belongs to a team, and reaches the members of the team the member leads.
     */
    static final class Staff extends Model {
        static final ModelQuery<Staff> QUERY = ModelQuery.of(Staff.class, Staff::new);

        @Override
        protected String table() { return "staff"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Staff.class).add("subordinates", Staff::subordinates);
        }

        /**
         * Returns the staff of the team this member leads: {@code teams.lead_id} to {@code staff.team_id}.
         * Self-referencing, so the member is among the results when they lead their own team.
         *
         * @return the through relation, never {@code null}
         */
        public HasManyThrough<Staff> subordinates() {
            return hasManyThrough(Staff.class, Team.class, "lead_id", "team_id", "id", "id");
        }
    }

    /**
     * A team: led by one staff member, and made of staff.
     */
    static final class Team extends Model {
        static final ModelQuery<Team> QUERY = ModelQuery.of(Team.class, Team::new);

        @Override
        protected String table() { return "teams"; }

        @Override
        protected boolean timestamps() { return false; }
    }

    /**
     * A person who may have a manager, who is a person too.
     */
    static final class Person extends Model {
        static final ModelQuery<Person> QUERY = ModelQuery.of(Person.class, Person::new);

        @Override
        protected String table() { return "people"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Person.class).add("reportPosts", Person::reportPosts);
        }

        /**
         * Returns the posts of the people this person manages: {@code people.manager_id} to
         * {@code posts.person_id}. The through table is the people table itself.
         *
         * @return the through relation, never {@code null}
         */
        public HasManyThrough<Post> reportPosts() {
            return hasManyThrough(Post.class, Person.class, "manager_id", "person_id", "id", "id");
        }
    }
}
