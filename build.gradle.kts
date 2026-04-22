plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass.set("com.example.logolsp.Main")
}

tasks.test {
    useJUnitPlatform()
}

// Records in modern Java auto-generate accessors and constructors; requiring @param
// on every one creates noise for no comprehension benefit. Disable the "missing"
// doclint category while keeping the valuable checks (broken @link, bad HTML, etc.).
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.shadowJar {
    archiveBaseName.set("logo-lsp")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "com.example.logolsp.Main")
    }
}
