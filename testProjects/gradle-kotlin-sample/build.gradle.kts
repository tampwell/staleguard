plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // version-catalog references (edit lands in gradle/libs.versions.toml;
    // the shared "jackson" version key must trigger the blast-radius dialog)
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)
    implementation(libs.commons.collections)

    // plain string notation, old version on purpose
    implementation("com.google.guava:guava:31.0.1-jre")

    // named-argument notation
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.9.0")
}
