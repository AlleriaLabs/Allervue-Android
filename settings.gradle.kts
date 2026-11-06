pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Explicit Maven Central endpoint fallback for the large VideoLAN LibVLC AAR.
        maven { url = uri("https://repo1.maven.org/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "Allervue"
include(":app")
