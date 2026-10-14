plugins { id("com.android.application") }
android {
    namespace = "com.keyflow.pianotiles"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.keyflow.pianotiles"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
}
dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
val prepareAndroidRuntimes by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("tools/prepare_android_runtimes.py"))
    inputs.file(rootProject.file("tools/prepare_android_runtimes.py"))
    outputs.dir(project.file("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(prepareAndroidRuntimes) }
