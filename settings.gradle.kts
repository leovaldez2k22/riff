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
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MetrolistGroup.innertubex")
                includeGroup("com.github.MetrolistGroup.quackjs")
            }
        }
    }
}

rootProject.name = "Riff"
include(":app")
include(":benchmark")

// Windows desktop feasibility module; Android modules keep their existing boundaries.
if (providers.gradleProperty("riffDesktop").orNull == "true") include(":desktop", ":desktop-data")

