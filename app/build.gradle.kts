plugins { id("com.android.application") }

android {
    namespace = "dev.erinlkolp.glassspotify"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.erinlkolp.glassspotify"
        minSdk = 22
        targetSdk = 22
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        // AGP emits -source/-target here, not --release, so options.release must not
        // be used in this module. spotify-core and gesture-core do use it.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":spotify-core"))
    implementation(project(":gesture-core"))
    testImplementation("junit:junit:4.13.2")
}
