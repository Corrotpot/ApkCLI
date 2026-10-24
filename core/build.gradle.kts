import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
    // Kotlin comes from AGP's built-in support; see the root build file.
    alias(libs.plugins.kotlin.serialization)
}

/* ---------------------------------------------------------------------------
 * Native (Rust) build wiring.
 *
 * Everything that must be *executable* on the device has to live in the APK's
 * lib/<abi>/ directory, because that is the only place the installer unpacks
 * into a filesystem location with an exec-permitted SELinux label. So the Rust
 * artifacts are plain cdylibs named lib*.so and dropped into src/main/jniLibs.
 * ------------------------------------------------------------------------- */

val abiToRustTarget = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

val requestedAbis: List<String> = (providers.gradleProperty("apkcli.abis").orNull ?: "arm64-v8a")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val buildRust: Boolean = (providers.gradleProperty("apkcli.buildRust").orNull ?: "true").toBoolean()
val rustProfile: String = providers.gradleProperty("apkcli.rustProfile").orNull ?: "release"

val nativeRoot = rootProject.layout.projectDirectory.dir("native")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "apkcli"
    description = "Cross-compile the Rust core into src/main/jniLibs via cargo-ndk."

    // Deliberately no `onlyIf { buildRust }`.
    //
    // `buildRust` is a script-level val, so a lambda that reads it captures the
    // build script object itself. onlyIf specs and task actions are serialized
    // with the task, and the configuration cache rejects that capture:
    //   "cannot serialize Gradle script object references"
    // (A lambda that touches nothing from the script is fine — see envReport in
    // the root build file, which uses doLast and caches happily.)
    //
    // The apkcli.buildRust switch is applied at the bottom of this file instead,
    // by not wiring the dependency at all, which also keeps the task out of the
    // graph rather than putting it there only to skip it.
    workingDir = nativeRoot.asFile
    inputs.dir(nativeRoot.dir("apkcli-core/src"))
    inputs.dir(nativeRoot.dir("execshim/src"))
    inputs.file(nativeRoot.file("Cargo.toml"))
    outputs.dir(jniLibsDir)

    val args = buildList {
        add("ndk")
        requestedAbis.forEach { abi ->
            require(abiToRustTarget.containsKey(abi)) { "Unknown ABI in apkcli.abis: $abi" }
            add("-t"); add(abi)
        }
        add("-o"); add(jniLibsDir.asFile.absolutePath)
        add("--")
        add("build")
        if (rustProfile == "release") add("--release")
        add("--workspace")
    }

    // cargo-ndk is a cargo subcommand; invoke through cargo so PATH resolution
    // works the same on Windows and Unix.
    executable = "cargo"
    setArgs(args)

    // No doFirst logging the command line: `args` is a script-level local, so
    // the action would capture the script object and hit the same
    // configuration-cache failure described above. Run with --info; Exec
    // reports its own command line.
}

tasks.register<Delete>("cargoClean") {
    group = "apkcli"
    delete(nativeRoot.dir("target"), jniLibsDir)
}

if (buildRust) {
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(cargoBuild) }
}

/* ------------------------------------------------------------------------- */

android {
    namespace = "dev.apkcli.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += requestedAbis }
    }

    sourceSets {
        getByName("main") {
            // Two jniLibs roots: one the cargo task writes into, one holding the
            // bionic-linked CLI tools staged by tools/fetch-prebuilts.
            // `directories` (a MutableSet<String>) rather than the deprecated
            // srcDirs(vararg Any). It is additive over the defaults, and it
            // takes paths as strings, so the prebuilt root is absolutized.
            jniLibs.directories.addAll(
                listOf(
                    "src/main/jniLibs",
                    rootProject.layout.projectDirectory
                        .dir("native/prebuilt").asFile.absolutePath,
                ),
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Mandatory. With the modern (compressed, in-place) packaging the
            // installer never materialises lib/<abi>/*.so on disk, and an
            // unextracted .so cannot be exec()'d.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.util.Log and friends answer defaults instead of throwing,
        // so code that logs can be tested on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.annotation)
    api(libs.androidx.documentfile)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.lifecycle.service)

    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.xz)

    testImplementation(libs.junit)
}
