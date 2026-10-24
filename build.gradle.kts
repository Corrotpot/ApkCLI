/*
 * AGP 9 ships built-in Kotlin support and pins the Kotlin Gradle plugin to the
 * version it was built against (2.2.10 for the 9.1 line). This raises it to the
 * version in the catalog.
 *
 * It has to be a buildscript classpath entry rather than a `plugins {}` alias:
 * KGP is already on AGP's own classpath, so the only lever is dependency
 * resolution, and that is what the AGP release notes prescribe for going
 * higher. The Compose compiler ships with Kotlin and has to match it exactly,
 * which is the whole reason for not just accepting 2.2.10.
 */
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // No kotlin.android: AGP 9's built-in Kotlin replaces it, and applying it
    // alongside the new DSL fails with a ClassCastException on BaseExtension.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * `./gradlew envReport` — prints what the native build needs, so a failing
 * cargo step is diagnosable without digging through Gradle output.
 */
tasks.register("envReport") {
    group = "apkcli"
    description = "Report the state of the host toolchain (JDK, Android SDK, NDK, Rust)."
    doLast {
        fun which(cmd: String): String = runCatching {
            val probe = if (System.getProperty("os.name").startsWith("Windows")) {
                listOf("cmd", "/c", "where", cmd)
            } else {
                listOf("sh", "-c", "command -v $cmd")
            }
            val p = ProcessBuilder(probe).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotEmpty()) out.lineSequence().first() else "NOT FOUND"
        }.getOrElse { "NOT FOUND" }

        logger.lifecycle("JDK            : ${System.getProperty("java.version")} (${System.getProperty("java.home")})")
        logger.lifecycle("ANDROID_HOME   : ${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "unset"}")
        logger.lifecycle("ANDROID_NDK    : ${System.getenv("ANDROID_NDK_HOME") ?: "unset (AGP will use sdk/ndk/<version>)"}")
        logger.lifecycle("cargo          : ${which("cargo")}")
        logger.lifecycle("cargo-ndk      : ${which("cargo-ndk")}")
        logger.lifecycle("rustup targets : run `rustup target list --installed`")
    }
}
