enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
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
include(":bluetooth")
include(":lan")