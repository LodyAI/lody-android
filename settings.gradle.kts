pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// updateDaemonJvm needs a toolchain download repository to write the daemon JVM criteria.
// Settings scripts cannot read gradle/libs.versions.toml, so the plugin version stays here.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LodyAndroid"
include(":app")
