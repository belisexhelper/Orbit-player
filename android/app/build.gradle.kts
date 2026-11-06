import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Verzija: versionName se menja ručno, versionCode daje CI (broj builda)
val appVersionName = "0.1.0"
val defaultServerUrl = "https://tvojdomen.com"
val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()

// Potpis za release: iz promenljivih okruženja (GitHub Secrets) ili keystore.properties
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signingValue("ORBIT_KEYSTORE_FILE", "storeFile")

android {
    namespace = "com.orbitplayer"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
        // Adresa servera (sajt + panel). Menja se ovde ili promenljivom ORBIT_SERVER_URL u GitHub Actions.
        val serverUrl = System.getenv("ORBIT_SERVER_URL")?.takeIf { it.isNotBlank() } ?: defaultServerUrl
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "APP_NAME", "\"Orbit Player\"")
    }

    signingConfigs {
        getByName("debug") {
            // Isti debug potpis na svakom računaru i u CI-ju, da bi se debug verzije mogle ažurirati preko starih
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("ORBIT_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ORBIT_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ORBIT_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("pro") {
            dimension = "edition"
            applicationId = "com.orbitplayer.pro"
            // Pro: povezivanje sa serverom, uparivanje, launcher/kiosk, samoažuriranje
            buildConfigField("boolean", "SERVER_ENABLED", "true")
        }
        create("lite") {
            dimension = "edition"
            applicationId = "com.orbitplayer.lite"
            // Lite: samo plejer za Google Play, liste se unose ručno
            buildConfigField("boolean", "SERVER_ENABLED", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // isti naziv paketa kao release, da bi se verzije mogle međusobno ažurirati tokom testiranja
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
