package dev.apkcli.di

import android.content.Context
import dev.apkcli.ApkCliApplication
import dev.apkcli.core.AgentRuntime

/**
 * Dependency access.
 *
 * A locator rather than a DI framework. The graph is one object with a dozen
 * eagerly-constructed members; Hilt would add an annotation processor, a KSP
 * version to keep aligned with every Kotlin bump, and generated indirection
 * over something that is already a single field on Application.
 *
 * The cost is that ViewModels reach for this instead of receiving dependencies.
 * Accepted knowingly: they are all constructed by a factory that reads from
 * here once, so swapping in a fake for a test means swapping one object.
 */
object ServiceLocator {

    fun runtime(context: Context): AgentRuntime =
        (context.applicationContext as ApkCliApplication).runtime

    fun startupReport(context: Context): AgentRuntime.StartupReport? =
        (context.applicationContext as ApkCliApplication).startupReport

    fun appScope(context: Context) =
        (context.applicationContext as ApkCliApplication).appScope
}
