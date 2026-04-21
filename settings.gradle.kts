enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroupByRegex("org\\.jetbrains.*")
                includeGroupByRegex("androidx\\.compose.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://repo.eclipse.org/content/repositories/paho-releases/")
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroupByRegex("org\\.jetbrains.*")
                includeGroupByRegex("androidx\\.compose.*")
            }
        }
    }

//    versionCatalogs {
//        create("libs") {
//            from("no.nordicsemi.android.gradle:version-catalog:1.11.3")
//        }
//    }
}

rootProject.name = "CrossCommunicationLibrary"
include(":shared")
include(":androidApp")
include(":androidSample")
include(":bluetooth")
include(":lan")
include(":logger")