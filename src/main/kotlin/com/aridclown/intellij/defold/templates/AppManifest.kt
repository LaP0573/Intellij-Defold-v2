package com.aridclown.intellij.defold.templates

/**
 * Built-in Defold engine modules a user can elect to strip from the final
 * native build via the app-manifest mechanism. Mirrors the choices offered
 * by britzl/manifestation and the Defold editor's own "App Manifest" UI.
 */
enum class AppManifestExclusion(val displayName: String) {
    PHYSICS_2D("Physics 2D (Box2D)"),
    PHYSICS_3D("Physics 3D (Bullet)"),
    RECORD("Record"),
    PROFILER("Profiler"),
    SOUND("Sound"),
    INPUT("Input"),
    LIVEUPDATE("LiveUpdate"),
    BASIS_TRANSCODER("Basis Transcoder")
}

/**
 * Graphics adapter selection. Defold ships with both an OpenGL and a Vulkan
 * adapter on native platforms; the user can opt into either alone.
 */
enum class GraphicsAdapter(val displayName: String) {
    BOTH("OpenGL + Vulkan"),
    OPENGL_ONLY("OpenGL only"),
    VULKAN_ONLY("Vulkan only")
}

data class AppManifestConfig(
    val exclusions: Set<AppManifestExclusion> = emptySet(),
    val graphics: GraphicsAdapter = GraphicsAdapter.BOTH
)

/**
 * Per-platform context block embedded under `platforms.<platform>.context`
 * in the generated app-manifest YAML.
 */
internal data class PlatformContext(
    val excludeLibs: List<String> = emptyList(),
    val excludeSymbols: List<String> = emptyList(),
    val libs: List<String> = emptyList(),
    val frameworks: List<String> = emptyList()
) {
    fun merge(other: PlatformContext): PlatformContext = PlatformContext(
        excludeLibs = (excludeLibs + other.excludeLibs).distinct(),
        excludeSymbols = (excludeSymbols + other.excludeSymbols).distinct(),
        libs = (libs + other.libs).distinct(),
        frameworks = (frameworks + other.frameworks).distinct()
    )
}
