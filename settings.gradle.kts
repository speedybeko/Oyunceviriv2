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

rootProject.name = "OyunCeviri"
include(":core") // Android'den bağımsız saf Kotlin mantığı (test edilebilir)
include(":app")  // telefon uygulaması
include(":tv")   // Android TV overlay uygulaması
