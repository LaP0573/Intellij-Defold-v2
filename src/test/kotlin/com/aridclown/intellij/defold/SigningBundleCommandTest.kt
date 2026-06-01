package com.aridclown.intellij.defold

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SigningBundleCommandTest {
    private val iosCredentials = BundleCredentials(
        iosProvisioningDebug = "/profiles/debug.mobileprovision",
        iosProvisioningRelease = "/profiles/release.mobileprovision",
        iosIdentityDebug = "iPhone Developer: Dev (DEBUGDEV)",
        iosIdentityRelease = "iPhone Distribution: Studio (PROD1234)"
    )

    private val androidCredentials = BundleCredentials(
        androidKeystore = "/keys/studio.keystore",
        androidKeystorePass = "/keys/studio.keystore.pass",
        androidKeystoreAlias = "studio-prod"
    )

    @Nested
    inner class IosArgvIncludesSigningFlags {
        @Test
        fun `release ios argv includes --mobileprovisioning and --identity for release credentials`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.IOS,
                options = BundleOptions(variant = BundleVariant.RELEASE),
                credentials = iosCredentials
            )

            assertThat(argv).containsSubsequence(
                "--mobileprovisioning",
                "/profiles/release.mobileprovision",
                "--identity",
                "iPhone Distribution: Studio (PROD1234)",
                "bundle"
            )
        }

        @Test
        fun `debug ios argv selects the debug provisioning and identity values`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.IOS,
                options = BundleOptions(variant = BundleVariant.DEBUG),
                credentials = iosCredentials
            )

            assertThat(argv).containsSubsequence(
                "--mobileprovisioning",
                "/profiles/debug.mobileprovision",
                "--identity",
                "iPhone Developer: Dev (DEBUGDEV)",
                "bundle"
            )
        }

        @Test
        fun `ios argv omits signing flags when no credentials are supplied`() {
            val argv = BundleCommandBuilder.build(BundleTarget.IOS)

            assertThat(argv).doesNotContain("--mobileprovisioning", "--identity")
        }

        @Test
        fun `signing flags are not emitted on non-ios targets`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.MACOS,
                credentials = iosCredentials
            )

            assertThat(argv).doesNotContain("--mobileprovisioning", "--identity")
        }
    }

    @Nested
    inner class AndroidReleaseArgvIncludesKeystoreFlags {
        @Test
        fun `release android argv includes keystore, keystore-pass, keystore-alias and --bundle-format`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.ANDROID,
                options = BundleOptions(
                    variant = BundleVariant.RELEASE,
                    androidBundleFormat = AndroidBundleFormat.AAB
                ),
                credentials = androidCredentials
            )

            assertThat(argv).containsSubsequence(
                "--bundle-format", "aab",
                "--keystore", "/keys/studio.keystore",
                "--keystore-pass", "/keys/studio.keystore.pass",
                "--keystore-alias", "studio-prod",
                "bundle"
            )
        }

        @Test
        fun `release android apk argv includes apk bundle-format and keystore flags`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.ANDROID,
                options = BundleOptions(
                    variant = BundleVariant.RELEASE,
                    androidBundleFormat = AndroidBundleFormat.APK
                ),
                credentials = androidCredentials
            )

            assertThat(argv)
                .containsSubsequence("--bundle-format", "apk")
                .containsSubsequence("--keystore", "/keys/studio.keystore")
                .containsSubsequence("--keystore-alias", "studio-prod")
        }

        @Test
        fun `android argv omits keystore flags when no credentials are supplied`() {
            val argv = BundleCommandBuilder.build(BundleTarget.ANDROID)

            assertThat(argv).doesNotContain("--keystore", "--keystore-pass", "--keystore-alias")
        }

        @Test
        fun `keystore flags are not emitted on non-android targets`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.MACOS,
                credentials = androidCredentials
            )

            assertThat(argv).doesNotContain("--keystore", "--keystore-pass", "--keystore-alias")
        }
    }

    @Nested
    inner class BuildServerAndPrivateDependencyFlags {
        @Test
        fun `argv includes --build-server when configured`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.HTML5,
                credentials = BundleCredentials(buildServer = "https://extender.example.com")
            )

            assertThat(argv).containsSubsequence("--build-server", "https://extender.example.com", "bundle")
        }

        @Test
        fun `argv includes --email and --auth when private-dep credentials configured`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.LINUX,
                credentials = BundleCredentials(privateDepEmail = "dev@studio.io", privateDepAuth = "tok-abc")
            )

            assertThat(argv).containsSubsequence("--email", "dev@studio.io", "bundle")
            assertThat(argv).containsSubsequence("--auth", "tok-abc", "bundle")
        }

        @Test
        fun `all credentials together compose in stable order before bundle`() {
            val argv = BundleCommandBuilder.build(
                target = BundleTarget.IOS,
                options = BundleOptions(variant = BundleVariant.RELEASE),
                credentials = BundleCredentials(
                    iosProvisioningRelease = "/profiles/release.mobileprovision",
                    iosIdentityRelease = "iPhone Distribution",
                    buildServer = "https://extender.example.com",
                    privateDepEmail = "dev@studio.io",
                    privateDepAuth = "tok-abc"
                )
            )

            assertThat(argv).containsSubsequence(
                "--mobileprovisioning", "/profiles/release.mobileprovision",
                "--identity", "iPhone Distribution",
                "--build-server", "https://extender.example.com",
                "--email", "dev@studio.io",
                "--auth", "tok-abc",
                "bundle"
            )
        }
    }
}
