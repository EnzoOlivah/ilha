import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave de upload da Play Store. Os dados ficam em keystore.properties (fora do código, nunca publicar).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ilha.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ilha.app"
        minSdk = 30
        targetSdk = 36   // a Play exige Android 16 (API 36) para apps novos
        versionCode = 1  // aumente a cada envio para a Play Store
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
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

// Sem dependências externas: só o SDK do Android.
