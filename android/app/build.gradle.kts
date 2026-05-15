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
        // تفعيل الـ Desugaring لدعم ميزات Java الحديثة على الأجهزة القديمة
        isCoreLibraryDesugaringEnabled = true 
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.ghadhoo_buler"
        minSdk = 23 // متوافق مع متطلبات TFLite
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }
configurations.all {
    exclude(group = "com.google.ai.edge.litert", module = "litert")
    exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    exclude(group = "com.google.ai.edge.litert", module = "litert-runtime")
}
    // --- الإضافة المهمة جداً لملفات الذكاء الاصطناعي ---
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

dependencies {
    // دعم المكتبات الحديثة
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // حل تعارض الإصدارات (تأكد من توحيد الإصدار لـ 1.9.20 لضمان الاستقرار)
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.20") {
            because("تحديث وتوحيد الإصدار لحل تعارض مكتبة network_info_plus")
        }
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20") {
            because("تحديث وتوحيد الإصدار لحل تعارض مكتبة network_info_plus")
        }
    }

    // مكتبات TensorFlow Lite الأساسية للتحليل المحلي
    implementation("org.tensorflow:tensorflow-lite:2.16.1")//2.14.0
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-api:2.16.1")//2.14.0
}