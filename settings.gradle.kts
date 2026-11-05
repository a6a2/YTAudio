pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack only for GitHub-hosted libraries (NewPipeExtractor + its helpers),
        // so it can never serve look-alike org.* / androidx.* artifacts.
        maven("https://jitpack.io") { content { includeGroupByRegex("""com\.github\..*""") } }
    }
}
rootProject.name = "YTAudio"
include(":app")
