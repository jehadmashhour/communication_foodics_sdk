import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.native.cocoapods")
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }

    // iOS targets
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // CocoaPods configuration
    cocoapods {
        summary = "Shared module for KMP + Ably"
        homepage = "https://your-project-homepage.com"
        ios.deploymentTarget = "13.0"
        podfile = project.file("../iosApp/Podfile")
        name = "shared"
        version = "1.0.0"

        pod("Ably", "~> 1.2")

        framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":bluetooth"))
                implementation(project(":lan"))
                implementation(libs.kmm.uuid)
                implementation(libs.kotlinx.coroutines.core)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.kmm.voyager.navigator)
                implementation(libs.kmm.voyager.screenmodel)
            }
        }

        val commonTest by getting {
            dependencies { implementation(libs.kotlin.test) }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.nordic.theme)
                api(libs.androidx.activity.compose)
                api(libs.androidx.appcompat)
                api(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.play.services.nearby)
                implementation(libs.ably.java)
                // QR Code
                implementation(libs.zxing.core)
                implementation(libs.mlkit.barcode)
                implementation(libs.camerax.camera2)
                implementation(libs.camerax.lifecycle)
                implementation(libs.camerax.view)
                implementation(libs.lifecycle.runtime.compose)
            }
        }

        // iOS source sets
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting

        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }

        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting

        val iosTest by creating {
            dependsOn(commonTest)
            iosX64Test.dependsOn(this)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }
    }
}

android {
    namespace = "com.foodics.crosscommunicationlibrary"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}