import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "nl.boschtv.tv"
    // The SDK we compile against (newest Compose needs 37.1); targetSdk below decides the app's behaviour.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "nl.boschtv.tv"
        minSdk = 26
        targetSdk = 35
        // Bumped by tools/release.sh; the in-app updater compares versionCode.
        val version = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
        versionCode = version.getProperty("versionCode").toInt()
        versionName = version.getProperty("versionName")
        // TMDB read token for trending lists; lives in local.properties (tmdb.token=…), empty = feature off.
        buildConfigField("String", "TMDB_TOKEN", "\"${localProps.getProperty("tmdb.token", "")}\"")
        // Where the app looks for new versions (update.json). Empty = updater off.
        buildConfigField("String", "UPDATE_URL", "\"${localProps.getProperty("update.url", "")}\"")
    }

    // Official releases are signed with a private release key named in local.properties (never in git).
    // Without one (forks, contributors, CI) the standard debug key is used, created on first build.
    // Android only installs an update signed with the same key as the installed app, so another
    // build can never replace an install from the official releases, and vice versa.
    val releaseStore = localProps.getProperty("signing.store")
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = localProps.getProperty("signing.password")
            keyAlias = localProps.getProperty("signing.alias", "boschtv")
            keyPassword = localProps.getProperty("signing.password")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.datastore.preferences)
    implementation(libs.workmanager)
    implementation(libs.tvprovider)
    implementation(libs.profileinstaller)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}

// tools/provider-report.sh: hands the provider login to the ProviderReport run (never stored).
tasks.withType<Test>().configureEach {
    listOf("server", "user", "pass", "language").forEach { k ->
        project.findProperty("xtream.$k")?.let { systemProperty("xtream.$k", it.toString()); outputs.upToDateWhen { false } }
    }
}

// The user database's schema per version (app/schemas, in git): what a migration is written and checked against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// AGP 9 compiles Kotlin itself (no separate Kotlin Android plugin); options go here.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}
