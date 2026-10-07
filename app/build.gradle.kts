plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lightcourse.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lightcourse.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/lightcourse.jks")
            storePassword = "lightcourse123"
            keyAlias = "lightcourse"
            keyPassword = "lightcourse123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/versions/**",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // 仅用其中的 HSSF 读取老式 .xls；.xlsx 由内置 XlsxParser 解析，无需 poi-ooxml
    implementation("org.apache.poi:poi:5.2.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.poi:poi:5.2.5")
}
