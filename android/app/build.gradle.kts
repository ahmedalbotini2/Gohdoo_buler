import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

// قراءة ملف local.properties بصيغة Kotlin المتوافقة مع الملف
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
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

    // تفعيل توليد كلاس BuildConfig
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.ghadhoo_buler"
        minSdk = 23
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // جلب المفتاح من اللوكل بروبرتيز وحقنه في الكود
        val openRouterKey = localProperties.getProperty("openrouter.api.key") ?: ""
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterKey\"")
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

    lint {
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
        abortOnError = false
    }
}

flutter {
    source = "../.."
}

repositories {
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("com.google.ai.edge.litert:litert:2.1.4")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}