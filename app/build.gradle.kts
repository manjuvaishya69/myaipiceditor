plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dlab.myaipiceditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dlab.myaipiceditor"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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

    // --- Language / JVM settings ---
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // --- Enable Jetpack Compose ---
    buildFeatures {
        compose = true
    }
    
}

dependencies {

    // --- Core Android + Compose ---
    implementation(libs.androidx.core.ktx)                       // Kotlin extensions
    implementation(libs.androidx.lifecycle.runtime.ktx)          // Lifecycle support
    implementation(libs.androidx.activity.compose)               // Compose integration for activities
    implementation(platform(libs.androidx.compose.bom))           // BOM (keeps Compose versions in sync)
    implementation(libs.androidx.ui)                             // Compose UI
    implementation(libs.androidx.ui.graphics)                    // Graphics
    implementation(libs.androidx.ui.tooling.preview)             // Preview in Studio
    implementation(libs.androidx.material3)                      // Material3 UI components

    // --- AI Models ---
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")
    // ONNX Runtime → needed to load and run .onnx AI models offline

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // --- Image Processing ---
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    // Helps handle image rotation/metadata correctly

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7")

    // --- Permissions ---
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    // Compose-friendly way to request/manage camera & storage permissions

    // --- Navigation ---
    implementation("androidx.navigation:navigation-compose:2.7.6")
    // For navigating between screens in Compose

    // --- ViewModel ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    // Integrates ViewModel with Compose UI

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Needed for background AI tasks (so UI doesn’t freeze)

    // --- Material Icons ---
    implementation("androidx.compose.material:material-icons-extended")
    // Extra Material icons (not included by default in Compose)


    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}