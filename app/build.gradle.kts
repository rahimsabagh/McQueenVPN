plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.mcqueenvpn.rmqvpn"
        minSdk = 29
        targetSdk = 36
        versionCode = 761
        versionName = "1.0.0-beta.1"
        buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val ks = file("${rootDir}/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")?.trim()
            val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")?.trim()
            val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")?.trim()
            val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.trim()

            if (!releaseStoreFile.isNullOrEmpty() &&
                !releaseStorePassword.isNullOrEmpty() &&
                !releaseKeyAlias.isNullOrEmpty() &&
                !releaseKeyPassword.isNullOrEmpty()
            ) {
                val direct = file(releaseStoreFile)
                val resolvedStore = if (direct.exists()) direct else rootProject.file(releaseStoreFile)
                if (resolvedStore.isFile) {
                    storeFile = resolvedStore
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    gradle.taskGraph.whenReady {
        val requiresReleaseSigning = allTasks.any { task ->
            task.project == project && (
                task.name == "bundleRelease" ||
                    task.name == "assembleRelease" ||
                    task.name == "packageRelease" ||
                    task.name == "packageReleaseBundle" ||
                    task.name == "signReleaseBundle" ||
                    task.name == "validateSigningRelease"
                )
        }
        if (requiresReleaseSigning) {
            val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")?.trim()
            val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")?.trim()
            val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")?.trim()
            val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.trim()

            val missingVars = buildList {
                if (releaseStoreFile.isNullOrEmpty()) add("RELEASE_STORE_FILE")
                if (releaseStorePassword.isNullOrEmpty()) add("RELEASE_STORE_PASSWORD")
                if (releaseKeyAlias.isNullOrEmpty()) add("RELEASE_KEY_ALIAS")
                if (releaseKeyPassword.isNullOrEmpty()) add("RELEASE_KEY_PASSWORD")
            }
            if (missingVars.isNotEmpty()) {
                throw GradleException(
                    "Release signing failed: missing or blank environment variable(s): ${missingVars.joinToString(", ")}. " +
                        "Configure RELEASE_STORE_FILE (e.g. ../release.jks), RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD."
                )
            }

            val direct = file(releaseStoreFile!!)
            val resolvedStore = if (direct.exists()) direct else rootProject.file(releaseStoreFile)
            if (!resolvedStore.isFile) {
                throw GradleException(
                    "Release signing failed: keystore file not found at '${resolvedStore.path}' (configured RELEASE_STORE_FILE='$releaseStoreFile'; expected ../release.jks relative to app module)."
                )
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "az",
            "bn",
            "bqi-rIR"
        )
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Unused scanner/reorderable dependencies commented out to reduce APK size
    // implementation(libs.camerax.core)
    // implementation(libs.camerax.camera2)
    // implementation(libs.camerax.lifecycle)
    // implementation(libs.camerax.compose)
    // implementation(libs.core)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // implementation(libs.reorderable)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
