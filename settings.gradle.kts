pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LoopinPlayer2"
include(":app")
include(":core:model")
include(":core:foundation")
include(":core:playback")
include(":core:media-cache")
include(":core:sync")
include(":core:operations")
include(":core:content")
