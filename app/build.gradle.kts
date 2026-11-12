import java.util.Properties

plugins {
    id("com.android.application")
}

// release 서명 정보는 저장소 밖에 둡니다. keystore.properties가 없으면 서명 없이 빌드됩니다.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "com.gilstory.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gilstory.app"
        // Pleos Connect는 minSdk 28 이상을 요구합니다 (서명 키 로테이션 요구사항).
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["automotiveRequired"] = "true"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            manifestPlaceholders["automotiveRequired"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    testImplementation("junit:junit:4.13.2")
}
