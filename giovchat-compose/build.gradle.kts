import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "app.giovchat.compose"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":giovchat-core"))
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    api(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime.compose)
    // Keeps a composable-owned controller across configuration changes (rememberGiovChatController).
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.activity.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    // Registers the empty activity the Compose test rule starts, for both unit test variants (debug and release).
    testImplementation(libs.compose.ui.test.manifest)
}

mavenPublishing {
    // The release variant with a sources jar. AGP's javadoc jar runs its bundled Dokka 1, which cannot read Kotlin 2.1
    // metadata, so the -javadoc jar is built from Dokka 2 below instead.
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = false))
    pom {
        name.set("GiovChat Compose")
        description.set("Jetpack Compose chat screen for the Giov visitor chat.")
    }
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "The -javadoc jar: Dokka 2 HTML of the public API."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(dokkaJavadocJar)
        // Write the versions the Compose BOM resolves into the POM and .module, so no dependency is versionless.
        versionMapping {
            usage("java-api") { fromResolutionOf("releaseCompileClasspath") }
            usage("java-runtime") { fromResolutionOf("releaseRuntimeClasspath") }
        }
    }
}
