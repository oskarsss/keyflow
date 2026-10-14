plugins { id("com.android.application") }
android {
    namespace = "com.keyflow.pianotiles"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.keyflow.pianotiles"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYFLOW_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYFLOW_STORE_PASSWORD")
                keyAlias = "keyflow"
                keyPassword = System.getenv("KEYFLOW_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (System.getenv("KEYFLOW_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
