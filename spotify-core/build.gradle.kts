plugins { id("java-library") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

dependencies {
    // Android provides org.json at runtime, so it must not be packaged.
    compileOnly("org.json:json:20231013")
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
