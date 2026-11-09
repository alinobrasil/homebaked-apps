plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val brandName = providers.gradleProperty("BRAND_NAME").get()
val brandAppId = providers.gradleProperty("BRAND_APP_ID").get()
val brandScheme = providers.gradleProperty("BRAND_SCHEME").get()

android {
    // The Kotlin package stays app.shell whatever the brand is, so a rename never
    // touches source files.
    namespace = "app.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = brandAppId
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"

        resValue("string", "app_name", brandName)
        manifestPlaceholders["linkScheme"] = brandScheme
        buildConfigField("String", "BRAND_NAME", "\"$brandName\"")
        buildConfigField("String", "BRAND_SCHEME", "\"$brandScheme\"")
    }

    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("HOMEBAKED_STORE_FILE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("HOMEBAKED_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("HOMEBAKED_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("HOMEBAKED_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (providers.gradleProperty("HOMEBAKED_STORE_FILE").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")   // turn photos upright
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // QR scanning, entirely on the phone: no Google Play services, nothing sent anywhere.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
