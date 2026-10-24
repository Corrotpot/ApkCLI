plugins {
    alias(libs.plugins.android.application)
    // Kotlin comes from AGP's built-in support; see the root build file.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val requestedAbis: List<String> = (providers.gradleProperty("apkcli.abis").orNull ?: "arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

/* ---------------------------------------------------------------------------
 * Release signing.
 *
 * An unsigned APK cannot be installed at all. Android rejects it before it ever
 * looks inside, with "the package appears to be invalid" / «пакет повреждён или
 * недействителен» — a message that says nothing about signing and sends you
 * looking for a corrupt download instead. So this is wired up, and when it is
 * not configured the build says which property is missing rather than quietly
 * producing an uninstallable file.
 *
 * Credentials come from Gradle properties in GRADLE_USER_HOME
 * (~/.gradle/gradle.properties), never from the repository:
 *
 *   apkcli.storeFile=/absolute/path/to/apkcli-release.jks
 *   apkcli.storePassword=...
 *   apkcli.keyAlias=...
 *   apkcli.keyPassword=...
 *
 * providers.gradleProperty is used rather than reading a file, because it is
 * tracked by the configuration cache; an untracked file read would hand back a
 * stale signing config after the credentials changed.
 *
 * Debug builds are signed by AGP with the standard debug keystore and need none
 * of this.
 * ------------------------------------------------------------------------- */
val signStoreFile = providers.gradleProperty("apkcli.storeFile")
val signStorePassword = providers.gradleProperty("apkcli.storePassword")
val signKeyAlias = providers.gradleProperty("apkcli.keyAlias")
val signKeyPassword = providers.gradleProperty("apkcli.keyPassword")

val missingSigningProps: List<String> = listOf(
    "apkcli.storeFile" to signStoreFile,
    "apkcli.storePassword" to signStorePassword,
    "apkcli.keyAlias" to signKeyAlias,
    "apkcli.keyPassword" to signKeyPassword,
).filter { (_, provider) -> provider.orNull.isNullOrBlank() }.map { (name, _) -> name }

val canSignRelease = missingSigningProps.isEmpty() &&
    file(signStoreFile.get()).exists()

if (!canSignRelease) {
    val why = when {
        missingSigningProps.isNotEmpty() ->
            "missing ${missingSigningProps.joinToString(", ")}"
        else -> "keystore not found at ${signStoreFile.get()}"
    }
    logger.lifecycle(
        "[apkcli] release builds will be UNSIGNED and cannot be installed ($why). " +
            "See the signing block in app/build.gradle.kts.",
    )
}

android {
    namespace = "dev.apkcli"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "dev.apkcli"
        minSdk = libs.versions.minSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += requestedAbis }
    }

    /* -----------------------------------------------------------------------
     * Two flavours, because targetSdk decides how much of a dev environment we
     * are allowed to be. This is not a style choice — it is the single most
     * consequential line in the whole project.
     *
     *   play  (targetSdk 36): SELinux domain untrusted_app_3x. exec() of files
     *         under /data/data/<pkg>/ is denied. Only binaries shipped inside
     *         the APK (nativeLibraryDir) and /system/bin can be executed.
     *         Installable everywhere, Play-policy compliant.
     *
     *   power (targetSdk 28): SELinux domain untrusted_app_27, which still
     *         holds app_data_file:file execute_no_trans. exec() from
     *         $PREFIX/bin works, so downloaded toolchains and a proot rootfs
     *         become possible. Still sideload-installable: Android 15 and 16
     *         only refuse targetSdk < 24.
     *         Not publishable on Play.
     *
     * See docs/ANDROID_EXEC_NOTES.md.
     * --------------------------------------------------------------------- */
    flavorDimensions += "sandbox"
    productFlavors {
        create("play") {
            dimension = "sandbox"
            targetSdk = libs.versions.targetSdkPlay.get().toInt()
            buildConfigField("boolean", "EXEC_FROM_DATA_DIR", "false")
            buildConfigField("String", "SANDBOX_TIER", "\"play\"")
        }
        create("power") {
            dimension = "sandbox"
            targetSdk = libs.versions.targetSdkPower.get().toInt()
            applicationIdSuffix = ".power"
            buildConfigField("boolean", "EXEC_FROM_DATA_DIR", "true")
            buildConfigField("String", "SANDBOX_TIER", "\"power\"")
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(signStoreFile.get())
                storePassword = signStorePassword.get()
                keyAlias = signKeyAlias.get()
                keyPassword = signKeyPassword.get()
                // v1 (JAR) signing is pointless here: minSdk is 26 and v2 has
                // been understood since 24. AGP decides this from minSdk, so
                // the schemes are left at their defaults on purpose.
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // As in :core: framework calls answer defaults on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Code editor widget (syntax highlighting, TextMate grammars, large files).
    implementation(platform(libs.sora.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    // The same pinned JUnit as :core, for the parts of the UI that are plain
    // functions: the streamed Markdown, the pieces reasoning is drawn in.
    testImplementation(libs.junit)
}
