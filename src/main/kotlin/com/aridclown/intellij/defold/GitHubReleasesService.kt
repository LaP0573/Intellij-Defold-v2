package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.util.SimpleHttpClient
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.time.Duration

/**
 * Minimal client for the public GitHub REST API used by [com.aridclown.intellij.defold.actions.AddDependencyAction].
 * Returns the set of refs (releases + default branch) the user can pick from when adding a Defold dependency.
 */
object GitHubReleasesService {
    private val logger = Logger.getInstance(GitHubReleasesService::class.java)
    private val timeout = Duration.ofSeconds(10)
    private const val MAX_RELEASES = 30

    /**
     * Parsed GitHub repository identifier.
     */
    data class RepoRef(
        val owner: String,
        val repo: String
    )

    /**
     * A user-pickable ref: either a tagged release (with its asset zip URL) or the default branch zip.
     */
    data class SelectableRef(
        val label: String,
        val zipUrl: String
    )

    /**
     * Parses a GitHub repository URL (`https://github.com/<owner>/<repo>` or `git@github.com:<owner>/<repo>.git`)
     * and returns the owner/repo. Returns `null` when the URL is unrecognised.
     */
    fun parseRepoUrl(url: String): RepoRef? {
        val trimmed = url.trim().removeSuffix("/").removeSuffix(".git")
        if (trimmed.isEmpty()) return null

        val (owner, repo) = when {
            trimmed.startsWith("git@github.com:") -> trimmed.removePrefix("git@github.com:").splitOwnerRepo()

            else -> {
                val sansScheme = trimmed.substringAfter("://", trimmed)
                if (!sansScheme.startsWith("github.com/")) return null
                sansScheme.removePrefix("github.com/").splitOwnerRepo()
            }
        } ?: return null

        if (owner.isBlank() || repo.isBlank()) return null
        return RepoRef(owner, repo)
    }

    private fun String.splitOwnerRepo(): Pair<String, String>? {
        val parts = split('/')
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    /**
     * Fetches the most recent releases (capped at [MAX_RELEASES]) plus the default branch.
     * Returns an ordered list — releases first (newest first), default branch last.
     * Throws [IOException] when the network call fails or the API returns a non-2xx response.
     */
    fun listRefs(repo: RepoRef): List<SelectableRef> {
        val releases = fetchReleases(repo)
        val branch = runCatching { fetchDefaultBranch(repo) }
            .onFailure { logger.warn("Failed to query default branch for ${repo.owner}/${repo.repo}", it) }
            .getOrNull()

        return buildList {
            addAll(releases)
            branch?.let(::add)
        }
    }

    private fun fetchReleases(repo: RepoRef): List<SelectableRef> {
        val url = "https://api.github.com/repos/${repo.owner}/${repo.repo}/releases?per_page=$MAX_RELEASES"
        val response = SimpleHttpClient.get(url, timeout)
        if (response.code !in 200..299) {
            throw IOException("GitHub releases API returned ${response.code} for ${repo.owner}/${repo.repo}")
        }
        val body = response.body.orEmpty()
        val array = JsonParser.parseString(body).asJsonArray
        return array.mapNotNull { element ->
            val obj = element.asJsonObject
            val tag = obj.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val assets = obj.getAsJsonArray("assets")
            val zipUrl = assets?.firstOrNull { asset ->
                val name = asset.asJsonObject.get("name")?.asString.orEmpty()
                name.endsWith(".zip")
            }?.asJsonObject?.get("browser_download_url")?.asString
                ?: "https://github.com/${repo.owner}/${repo.repo}/archive/refs/tags/$tag.zip"
            SelectableRef(label = "Release $tag", zipUrl = zipUrl)
        }
    }

    private fun fetchDefaultBranch(repo: RepoRef): SelectableRef? {
        val response = SimpleHttpClient.get("https://api.github.com/repos/${repo.owner}/${repo.repo}", timeout)
        if (response.code !in 200..299) {
            throw IOException("GitHub repo API returned ${response.code} for ${repo.owner}/${repo.repo}")
        }
        val obj = JsonParser.parseString(response.body.orEmpty()).asJsonObject
        val branch = obj.get("default_branch")?.takeIf { !it.isJsonNull }?.asString ?: return null
        return SelectableRef(
            label = "Default branch ($branch)",
            zipUrl = "https://github.com/${repo.owner}/${repo.repo}/archive/refs/heads/$branch.zip"
        )
    }
}
