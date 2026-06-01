package com.aridclown.intellij.defold

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BundleCommandBuilderTest {
    @Nested
    inner class MacOSTarget {
        @Test
        fun `bundles macOS with both architectures and release variant by default`() {
            val argv = BundleCommandBuilder.build(BundleTarget.MACOS)

            assertThat(argv).containsExactly(
                "--variant=release",
                "--archive",
                "--platform", "x86_64-macos",
                "--architectures", "x86_64-macos,arm64-macos",
                "--bundle-output", "bundle/macos",
                "bundle"
            )
        }

        @Test
        fun `honors a debug variant override`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.MACOS,
                BundleOptions(variant = BundleVariant.DEBUG)
            )

            assertThat(argv).startsWith("--variant=debug").endsWith("bundle")
        }
    }

    @Nested
    inner class AndroidTarget {
        @Test
        fun `bundles Android AAB with multi-arch and aab bundle format`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.ANDROID,
                BundleOptions(androidBundleFormat = AndroidBundleFormat.AAB)
            )

            assertThat(argv).containsExactly(
                "--variant=release",
                "--archive",
                "--platform", "armv7-android",
                "--architectures", "armv7-android,arm64-android",
                "--bundle-output", "bundle/android-aab",
                "--bundle-format", "aab",
                "bundle"
            )
        }

        @Test
        fun `bundles Android APK with multi-arch and apk bundle format`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.ANDROID,
                BundleOptions(androidBundleFormat = AndroidBundleFormat.APK)
            )

            assertThat(argv).containsExactly(
                "--variant=release",
                "--archive",
                "--platform", "armv7-android",
                "--architectures", "armv7-android,arm64-android",
                "--bundle-output", "bundle/android-apk",
                "--bundle-format", "apk",
                "bundle"
            )
        }
    }

    @Nested
    inner class Html5Target {
        @Test
        fun `bundles HTML5 with both js-web and wasm-web architectures`() {
            val argv = BundleCommandBuilder.build(BundleTarget.HTML5)

            assertThat(argv).containsExactly(
                "--variant=release",
                "--archive",
                "--platform", "js-web",
                "--architectures", "js-web,wasm-web",
                "--bundle-output", "bundle/html5",
                "bundle"
            )
        }
    }

    @Nested
    inner class OtherTargets {
        @Test
        fun `bundles iOS for arm64`() {
            val argv = BundleCommandBuilder.build(BundleTarget.IOS)

            assertThat(argv)
                .contains("--platform", "arm64-ios")
                .contains("--architectures", "arm64-ios")
                .contains("--bundle-output", "bundle/ios")
                .endsWith("bundle")
        }

        @Test
        fun `bundles Windows for x86_64-win32`() {
            val argv = BundleCommandBuilder.build(BundleTarget.WINDOWS)

            assertThat(argv)
                .contains("--platform", "x86_64-win32")
                .contains("--architectures", "x86_64-win32")
                .contains("--bundle-output", "bundle/win32")
                .endsWith("bundle")
        }

        @Test
        fun `bundles Linux for x86_64-linux`() {
            val argv = BundleCommandBuilder.build(BundleTarget.LINUX)

            assertThat(argv)
                .contains("--platform", "x86_64-linux")
                .contains("--architectures", "x86_64-linux")
                .contains("--bundle-output", "bundle/linux")
                .endsWith("bundle")
        }
    }

    @Nested
    inner class OptionFlags {
        @Test
        fun `appends texture-compression flag when enabled`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.MACOS,
                BundleOptions(textureCompression = TextureCompressionMode.ENABLED)
            )

            assertThat(argv).containsSubsequence("--texture-compression", "enabled", "bundle")
        }

        @Test
        fun `appends build-report-html flag when requested`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.HTML5,
                BundleOptions(buildReportHtml = true)
            )

            assertThat(argv).containsSubsequence("--build-report-html", "build/report.html", "bundle")
        }

        @Test
        fun `appends with-symbols flag when requested`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.IOS,
                BundleOptions(withSymbols = true)
            )

            assertThat(argv).containsSubsequence("--with-symbols", "bundle")
        }

        @Test
        fun `appends liveupdate flag when requested`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.ANDROID,
                BundleOptions(liveUpdate = true)
            )

            assertThat(argv).containsSubsequence("--liveupdate", "yes", "bundle")
        }

        @Test
        fun `combines multiple option flags in stable order`() {
            val argv = BundleCommandBuilder.build(
                BundleTarget.ANDROID,
                BundleOptions(
                    variant = BundleVariant.DEBUG,
                    androidBundleFormat = AndroidBundleFormat.APK,
                    textureCompression = TextureCompressionMode.DISABLED,
                    buildReportHtml = true,
                    withSymbols = true,
                    liveUpdate = true
                )
            )

            assertThat(argv).containsExactly(
                "--variant=debug",
                "--archive",
                "--platform", "armv7-android",
                "--architectures", "armv7-android,arm64-android",
                "--bundle-output", "bundle/android-apk",
                "--bundle-format", "apk",
                "--texture-compression", "disabled",
                "--build-report-html", "build/report.html",
                "--with-symbols",
                "--liveupdate", "yes",
                "bundle"
            )
        }
    }

    @Nested
    inner class AllSixTargetsAreSupported {
        @Test
        fun `every BundleTarget produces an archive bundle argv`() {
            assertThat(BundleTarget.entries)
                .hasSize(6)
                .extracting<List<String>> { BundleCommandBuilder.build(it) }
                .allSatisfy { argv ->
                    assertThat(argv)
                        .startsWith("--variant=release", "--archive")
                        .contains("--platform")
                        .contains("--architectures")
                        .contains("--bundle-output")
                        .endsWith("bundle")
                }
        }
    }
}
