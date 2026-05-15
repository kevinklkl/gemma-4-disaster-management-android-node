pluginManagement {
    repositories {
<<<<<<< Updated upstream
        google()
=======
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
>>>>>>> Stashed changes
        mavenCentral()
        gradlePluginPortal()
    }
}
<<<<<<< Updated upstream

=======
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
>>>>>>> Stashed changes
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BayanihanNode"
include(":app")
