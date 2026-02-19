plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.stepcounter3"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.stepcounter3"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = org.jetbrains.kotlin.config.JvmTarget.JVM_11.toString()
    }
    buildFeatures{
        viewBinding = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.maps.android:maps-compose:6.12.2")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    implementation(platform("androidx.compose:compose-bom:2025.11.01"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.compose.material:material-android:1.9.4")
    implementation("androidx.compose.compiler:compiler:1.5.15")
    implementation("androidx.compose.ui:ui-tooling-preview-android:1.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.mikhaellopez:circularprogressbar:3.1.0")
}