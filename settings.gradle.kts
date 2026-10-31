pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "diwan-database"

include("core", "sqlite", "pgsql")
