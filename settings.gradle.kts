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
        // Add JitPack repository for PersistentCookieJar
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "AtsuPager"
include(":app")
