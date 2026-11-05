/**
 * Runs seeders: classes that fill a database with starting data.
 *
 * <p>This package holds {@link org.aristonis.diwan.database.seeding.Seeder}, the
 * {@link org.aristonis.diwan.database.seeding.SeedContext} each one receives, the
 * {@link org.aristonis.diwan.database.seeding.SeederListener} that observes a run, the
 * {@link org.aristonis.diwan.database.seeding.SeedOptions} of one run, and the
 * {@link org.aristonis.diwan.database.seeding.SeedRunner} the manager delegates to. Seeders are
 * created by suppliers such as {@code UserSeeder::new}; nothing is found by reflection.
 *
 * <p>This package does not hold a console command, progress output or model-event handling.
 */
package org.aristonis.diwan.database.seeding;
