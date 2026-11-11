import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    // The version lives in gradle.properties (VERSION_NAME) and nowhere else in the build.
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// Shared Maven Central settings for the published modules (giovchat-core, giovchat-compose). Each module sets its own
// POM name and description. Credentials and the signing key come from the environment in CI:
// ORG_GRADLE_PROJECT_mavenCentralUsername / _mavenCentralPassword / _signingInMemoryKey / _signingInMemoryKeyPassword.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            // Sign only when a key is provided, so publishToMavenLocal works without one.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                inceptionYear.set("2026")
                url.set("https://github.com/Fegig-Tech/giovchat-android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("fegig-tech")
                        name.set("Fegig Tech")
                        url.set("https://giovchat.app")
                    }
                }
                scm {
                    url.set("https://github.com/Fegig-Tech/giovchat-android")
                    connection.set("scm:git:https://github.com/Fegig-Tech/giovchat-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Fegig-Tech/giovchat-android.git")
                }
            }
        }
    }
}
