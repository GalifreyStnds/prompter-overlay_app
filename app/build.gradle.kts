plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "us.jeremycombs.prompter"
    compileSdk = 36

    defaultConfig {
        applicationId = "us.jeremycombs.prompter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Ship the built APK under a real product name instead of the default
// app-debug.apk / app-release.apk output names.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("App-Teleprompter-Overlay.apk")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
