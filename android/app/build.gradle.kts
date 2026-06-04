plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.ghadhoo_buler"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    defaultConfig {
        applicationId = "com.ghadhoo_buler"
        minSdk = 23
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/x86/libtensorflowlite_jni.so",
                "lib/x86_64/libtensorflowlite_jni.so",
                "lib/armeabi-v7a/libtensorflowlite_jni.so",
                "lib/arm64-v8a/libtensorflowlite_jni.so"
            )
        }
    }

    @Suppress("DEPRECATION")
    aaptOptions {
        noCompress("tflite")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

// ✅ JitPack هنا على مستوى الـ project وليس settings — يتوافق مع Flutter
repositories {
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("com.google.ai.edge.litert:litert:2.1.4")

}