import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

group = rootProject.group

version = rootProject.version

kotlin {
    androidTarget() // <-- This registers Android

    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        macosX64(),
        macosArm64(),
        tvosX64(),
        tvosArm64(),
        tvosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "DNS-SD-KT"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }

        commonTest.dependencies { implementation(kotlin("test")) }

        androidMain.dependencies {
            implementation(libs.androidx.startup)
            implementation(libs.androidx.appcompat)
        }

        jvmMain.dependencies { api(libs.jmdns) }

        appleMain.dependencies {}
    }
}



android {
    namespace = "com.appstractive.dnssd"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
