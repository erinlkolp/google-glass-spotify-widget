pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glass-spotify-widget"

include(":spotify-core")
// The app module does not exist until Task 9. Re-enabled there.
// include(":app")

// gesture-core is the single source of truth for the touchpad anisotropy maths and
// the empirically-tuned gesture thresholds. It lives in the Gesture Launcher repo and
// is shared rather than copied, so a threshold fix in either project benefits both.
val gestureCore = file("../google-glass-gesture-launcher/gesture-core")
if (!gestureCore.isDirectory) {
    throw GradleException(
        "Cannot find gesture-core at ${gestureCore.absolutePath}.\n" +
        "This project shares gesture-core with the Gesture Launcher, so that repo must " +
        "be checked out beside this one:\n" +
        "  git clone git@github.com:erinlkolp/my-first-google-glass-project.git " +
        "../google-glass-gesture-launcher"
    )
}
include(":gesture-core")
project(":gesture-core").projectDir = gestureCore
