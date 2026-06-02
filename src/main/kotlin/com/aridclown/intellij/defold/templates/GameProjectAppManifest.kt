package com.aridclown.intellij.defold.templates

import org.ini4j.Ini

/**
 * Writes the `[native_extension] app_manifest = ...` entry in `game.project`
 * so Bob picks up the generated manifest on the next build.
 */
object GameProjectAppManifest {
    const val GENERATED_APP_MANIFEST_FILE: String = "generated.appmanifest"
    const val GENERATED_APP_MANIFEST_VALUE: String = "/$GENERATED_APP_MANIFEST_FILE"

    const val NATIVE_EXTENSION_SECTION: String = "native_extension"
    const val APP_MANIFEST_KEY: String = "app_manifest"

    /**
     * Idempotently set `[native_extension] app_manifest = <value>` on [ini].
     * The section is created if absent; an existing value is overwritten.
     */
    fun apply(ini: Ini, value: String = GENERATED_APP_MANIFEST_VALUE) {
        val section = ini[NATIVE_EXTENSION_SECTION] ?: run {
            ini.add(NATIVE_EXTENSION_SECTION)
            ini[NATIVE_EXTENSION_SECTION]!!
        }
        section[APP_MANIFEST_KEY] = value
    }
}
