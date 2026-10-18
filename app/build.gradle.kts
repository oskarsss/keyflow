plugins { id("com.android.application") }
// Releases come as one APK per processor, each with only its own native code: arm64 for phones
// and x86_64 for emulators. Debug builds hold both in one APK.
val perAbi = gradle.startParameter.taskNames.any { it.contains("Release") }
android {
    namespace = "com.keyflow.pianotiles"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.keyflow.pianotiles"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.6.6"
        if (!perAbi) ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    // The models are read in place from the APK, which needs them stored uncompressed.
    androidResources { noCompress += "onnx" }
    splits {
        abi {
            isEnable = perAbi
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
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
