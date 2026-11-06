package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.schema.internal.SchemaNames;

import java.util.ArrayList;

import java.util.Arrays;

import java.util.Collections;

import java.util.List;

/** The column helpers of a {@link Blueprint}: ids, soft deletes, morphs, and the drops that undo them. */
abstract class BlueprintHelpers extends BlueprintColumns {

    BlueprintHelpers(String table, String prefix, boolean isCreating) {
        super(table, prefix, isCreating);
    }

    /**
     * Adds a UUID column named {@code uuid}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition uuid() {
        return uuid("uuid");
    }

    /**
     * Adds a UUID column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition uuid(String column) {
        return simple(column, ColumnType.UUID);
    }

    /**
     * Adds an IP address column named {@code ip_address}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ipAddress() {
        return ipAddress("ip_address");
    }

    /**
     * Adds an IP address column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ipAddress(String column) {
        return simple(column, ColumnType.IP_ADDRESS);
    }

    /**
     * Adds a MAC address column named {@code mac_address}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition macAddress() {
        return macAddress("mac_address");
    }

    /**
     * Adds a MAC address column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition macAddress(String column) {
        return simple(column, ColumnType.MAC_ADDRESS);
    }

    /**
     * Adds a ULID column named {@code ulid}: Laravel's 26-character {@code char}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ulid() {
        return ulid("ulid");
    }

    /**
     * Adds a ULID column: Laravel's 26-character {@code char}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ulid(String column) {
        return charColumn(column, ULID_LENGTH);
    }

    /**
     * Adds a column holding one value out of a list; Laravel's {@code enum}.
     *
     * @param column the column name, never {@code null}
     * @param allowed the allowed values, at least one, without {@code null} or repeats
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} or {@code allowed} is {@code null}
     * @throws IllegalArgumentException if the name is invalid, or the values are empty, hold {@code null} or repeat
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition enumColumn(String column, List<String> allowed) {
        return add(new ColumnDefinition(column, ColumnType.ENUM, 0, 0, 0, ColumnDefinition.NO_PRECISION,
                checkAllowed(allowed)));
    }

    /**
     * Adds a column holding any number of values out of a list.
     *
     * @param column the column name, never {@code null}
     * @param allowed the allowed values, at least one, without {@code null} or repeats
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} or {@code allowed} is {@code null}
     * @throws IllegalArgumentException if the name is invalid, or the values are empty, hold {@code null} or repeat
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition set(String column, List<String> allowed) {
        return add(new ColumnDefinition(column, ColumnType.SET, 0, 0, 0, ColumnDefinition.NO_PRECISION,
                checkAllowed(allowed)));
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} timestamp columns with a time zone.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> timestampsTz() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(timestampTz("created_at").nullable());
        added.add(timestampTz("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} timestamp columns, as {@link Blueprint#timestamps()} does.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> nullableTimestamps() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(timestamp("created_at").nullable());
        added.add(timestamp("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} date and time columns.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> datetimes() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(dateTime("created_at").nullable());
        added.add(dateTime("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds Laravel's nullable {@code deleted_at} timestamp column for soft deletes.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletes() {
        return softDeletes("deleted_at");
    }

    /**
     * Adds a nullable timestamp column for soft deletes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletes(String column) {
        return timestamp(column).nullable();
    }

    /**
     * Adds Laravel's nullable {@code deleted_at} timestamp with a time zone column for soft deletes.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesTz() {
        return softDeletesTz("deleted_at");
    }

    /**
     * Adds a nullable timestamp with a time zone column for soft deletes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesTz(String column) {
        return timestampTz(column).nullable();
    }

    /**
     * Adds Laravel's nullable {@code deleted_at} date and time column for soft deletes.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesDatetime() {
        return softDeletesDatetime("deleted_at");
    }

    /**
     * Adds a nullable date and time column for soft deletes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesDatetime(String column) {
        return dateTime(column).nullable();
    }

    /**
     * Adds Laravel's {@code remember_token}: a nullable text column of 100 characters.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition rememberToken() {
        return string("remember_token", REMEMBER_TOKEN_LENGTH).nullable();
    }

    /**
     * Adds a UUID column, ready for {@link ForeignIdColumnDefinition#constrained()}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignUuid(String column) {
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(self(), column, ColumnType.UUID, 0);
        add(definition);
        return definition;
    }

    /**
     * Adds a ULID column, ready for {@link ForeignIdColumnDefinition#constrained()}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignUlid(String column) {
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(self(), column, ColumnType.CHAR, ULID_LENGTH);
        add(definition);
        return definition;
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, Laravel's default, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void morphs(String name) {
        morphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, Laravel's default, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void morphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableMorphs(String name) {
        nullableMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, true, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void numericMorphs(String name) {
        numericMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void numericMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableNumericMorphs(String name) {
        nullableNumericMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableNumericMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, true, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a UUID id, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void uuidMorphs(String name) {
        uuidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a UUID id, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void uuidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.UUID, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a UUID id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUuidMorphs(String name) {
        nullableUuidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a UUID id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUuidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.UUID, true, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a ULID id, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void ulidMorphs(String name) {
        ulidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a ULID id, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void ulidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.ULID, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a ULID id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUlidMorphs(String name) {
        nullableUlidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a ULID id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUlidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.ULID, true, indexName);
    }

    /**
     * Drops Laravel's {@code created_at} and {@code updated_at} columns.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropTimestamps() {
        dropColumn("created_at", "updated_at");
    }

    /**
     * Drops the {@code created_at} and {@code updated_at} columns that {@link Blueprint#timestampsTz()} adds.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropTimestampsTz() {
        dropColumn("created_at", "updated_at");
    }

    /**
     * Drops Laravel's {@code deleted_at} soft delete column.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletes() {
        dropSoftDeletes("deleted_at");
    }

    /**
     * Drops a soft delete column.
     *
     * @param column the column name, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletes(String column) {
        dropColumn(column);
    }

    /**
     * Drops Laravel's {@code deleted_at} soft delete column.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletesTz() {
        dropSoftDeletesTz("deleted_at");
    }

    /**
     * Drops a soft delete column.
     *
     * @param column the column name, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletesTz(String column) {
        dropColumn(column);
    }

    /**
     * Drops Laravel's {@code remember_token} column.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropRememberToken() {
        dropColumn("remember_token");
    }

    /**
     * Drops a polymorphic relation: its index, then its two columns.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropMorphs(String name) {
        dropMorphs(name, null);
    }

    /**
     * Drops a polymorphic relation whose index has a chosen name: the index, then its two columns.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropMorphs(String name, String indexName) {
        List<String> pair = morphPair(name);
        if (indexName == null) {
            dropIndex(pair);
        } else {
            dropIndex(indexName);
        }
        dropColumn(pair.get(0), pair.get(1));
    }

    void morphColumns(String name, MorphKey key, boolean isNullable, String indexName) {
        List<String> pair = morphPair(name);
        ColumnDefinition type = string(pair.get(0));
        ColumnDefinition id;
        if (key == MorphKey.UUID) {
            id = uuid(pair.get(1));
        } else if (key == MorphKey.ULID) {
            id = ulid(pair.get(1));
        } else {
            id = unsignedBigInteger(pair.get(1));
        }
        if (isNullable) {
            type.nullable();
            id.nullable();
        }
        index(pair, indexName);
    }

    static List<String> morphPair(String name) {
        SchemaNames.check("morph", name);
        return Arrays.asList(name + "_type", name + "_id");
    }
}
