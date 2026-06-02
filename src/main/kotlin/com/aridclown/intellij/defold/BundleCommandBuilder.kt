package com.aridclown.intellij.defold

/**
 * Builds Bob CLI argv for cross-platform bundling.
 *
 * Output is the list of arguments to pass to Bob after the main class
 * (i.e. everything after `com.dynamo.bob.Bob`). The list always starts
 * with `--variant=...` and ends with the literal `bundle` command.
 *
 * Models Defold Kit's `bob.ts` + `config.bundleTargets`. See docs/04-deep-dives.md §3.
 */
object BundleCommandBuilder {
    fun build(
        target: BundleTarget,
        options: BundleOptions = BundleOptions(),
        credentials: BundleCredentials = BundleCredentials()
    ): List<String> = buildList {
        add("--variant=${options.variant.flag}")
        add("--archive")
        add("--platform")
        add(target.platform)
        add("--architectures")
        add(target.architectures.joinToString(","))
        add("--bundle-output")
        add(target.bundleOutputFor(options))
        if (target == BundleTarget.ANDROID) {
            add("--bundle-format")
            add(options.androidBundleFormat.flag)
        }
        options.textureCompression?.let {
            add("--texture-compression")
            add(it.flag)
        }
        if (options.buildReportHtml) {
            add("--build-report-html")
            add(BUILD_REPORT_PATH)
        }
        if (options.withSymbols) {
            add("--with-symbols")
        }
        if (options.liveUpdate) {
            add("--liveupdate")
            add("yes")
        }
        addSigningFlags(target, options.variant, credentials)
        credentials.buildServer?.let {
            add("--build-server")
            add(it)
        }
        credentials.privateDepEmail?.let {
            add("--email")
            add(it)
        }
        credentials.privateDepAuth?.let {
            add("--auth")
            add(it)
        }
        add("bundle")
    }

    private fun MutableList<String>.addSigningFlags(
        target: BundleTarget,
        variant: BundleVariant,
        credentials: BundleCredentials
    ) {
        when (target) {
            BundleTarget.IOS -> {
                credentials.iosProvisioningFor(variant)?.let {
                    add("--mobileprovisioning")
                    add(it)
                }
                credentials.iosIdentityFor(variant)?.let {
                    add("--identity")
                    add(it)
                }
            }

            BundleTarget.ANDROID -> {
                credentials.androidKeystore?.let {
                    add("--keystore")
                    add(it)
                }
                credentials.androidKeystorePass?.let {
                    add("--keystore-pass")
                    add(it)
                }
                credentials.androidKeystoreAlias?.let {
                    add("--keystore-alias")
                    add(it)
                }
            }

            else -> Unit
        }
    }

    private const val BUILD_REPORT_PATH = "build/report.html"
}

enum class BundleTarget(
    val displayName: String,
    val platform: String,
    val architectures: List<String>,
    val bundleOutput: String
) {
    IOS("iOS", "arm64-ios", listOf("arm64-ios"), "bundle/ios"),
    ANDROID("Android", "armv7-android", listOf("armv7-android", "arm64-android"), "bundle/android"),
    WINDOWS("Windows", "x86_64-win32", listOf("x86_64-win32"), "bundle/win32"),
    MACOS("macOS", "x86_64-macos", listOf("x86_64-macos", "arm64-macos"), "bundle/macos"),
    LINUX("Linux", "x86_64-linux", listOf("x86_64-linux"), "bundle/linux"),
    HTML5("HTML5", "js-web", listOf("js-web", "wasm-web"), "bundle/html5");

    fun bundleOutputFor(options: BundleOptions): String = when (this) {
        ANDROID -> "$bundleOutput-${options.androidBundleFormat.flag}"
        else -> bundleOutput
    }
}

enum class BundleVariant(val flag: String) {
    DEBUG("debug"),
    RELEASE("release")
}

enum class AndroidBundleFormat(val flag: String) {
    AAB("aab"),
    APK("apk")
}

enum class TextureCompressionMode(val flag: String) {
    ENABLED("enabled"),
    DISABLED("disabled")
}

data class BundleOptions(
    val variant: BundleVariant = BundleVariant.RELEASE,
    val androidBundleFormat: AndroidBundleFormat = AndroidBundleFormat.AAB,
    val textureCompression: TextureCompressionMode? = null,
    val buildReportHtml: Boolean = false,
    val withSymbols: Boolean = false,
    val liveUpdate: Boolean = false
)

/**
 * Signing + server + private-dep credentials threaded into Bob's bundle invocation.
 *
 * Mirrors Defold Kit's `defoldKit.bundle.*` settings (docs/04-deep-dives.md §3). Persisted
 * in [com.aridclown.intellij.defold.settings.DefoldSettings] and provided per bundle by
 * the bundle action.
 */
data class BundleCredentials(
    val iosProvisioningDebug: String? = null,
    val iosProvisioningRelease: String? = null,
    val iosIdentityDebug: String? = null,
    val iosIdentityRelease: String? = null,
    val androidKeystore: String? = null,
    val androidKeystorePass: String? = null,
    val androidKeystoreAlias: String? = null,
    val buildServer: String? = null,
    val privateDepEmail: String? = null,
    val privateDepAuth: String? = null
) {
    fun iosProvisioningFor(variant: BundleVariant): String? = when (variant) {
        BundleVariant.DEBUG -> iosProvisioningDebug
        BundleVariant.RELEASE -> iosProvisioningRelease
    }

    fun iosIdentityFor(variant: BundleVariant): String? = when (variant) {
        BundleVariant.DEBUG -> iosIdentityDebug
        BundleVariant.RELEASE -> iosIdentityRelease
    }
}
