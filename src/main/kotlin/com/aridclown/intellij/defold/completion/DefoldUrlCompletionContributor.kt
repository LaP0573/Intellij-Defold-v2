package com.aridclown.intellij.defold.completion

import com.aridclown.intellij.defold.DefoldProjectService.Companion.rootProjectFolder
import com.aridclown.intellij.defold.DefoldScriptType
import com.aridclown.intellij.defold.resources.DefoldIndex
import com.aridclown.intellij.defold.resources.DefoldUrlEntry
import com.aridclown.intellij.defold.resources.DefoldUrlIndexService.Companion.defoldUrlIndexService
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.LuaCallExpr
import com.tang.intellij.lua.psi.LuaLiteralExpr

/**
 * Suggests Defold URLs (`/instance`, `#component`, `/instance#component`) inside Lua string
 * literals in `.script` / `.gui_script` / `.lua` files. The actual index lives in
 * [com.aridclown.intellij.defold.resources.DefoldUrlIndexService]; this contributor only filters
 * and renders. Stays additive next to EmmyLua2 — the result set is not stopped and non-string
 * positions are left untouched.
 */
class DefoldUrlCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val originalFile: PsiFile = parameters.originalFile
        if (originalFile.language != LuaLanguage.INSTANCE) return

        val virtualFile = originalFile.virtualFile ?: return
        val extension = virtualFile.extension ?: return
        if (DefoldScriptType.fromExtension(extension) == null) return

        val literal = findEnclosingStringLiteral(parameters.position) ?: return
        if (encloseCallIsRequire(literal)) return

        val project = originalFile.project
        if (project.rootProjectFolder == null) return

        val innerPrefix = innerStringPrefix(literal, parameters.offset) ?: return
        val index = project.defoldUrlIndexService().getIndex()

        val entries = if (extension == DefoldScriptType.LUA.extension) {
            index.allInstanceUrls()
        } else {
            urlsForScriptHost(project, virtualFile, index)
        }
        if (entries.isEmpty()) return

        val matcher = result.withPrefixMatcher(result.prefixMatcher.cloneWithPrefix(innerPrefix))
        val seen = HashSet<String>()
        for (entry in entries) {
            if (!seen.add(entry.url)) continue
            matcher.addElement(toLookupElement(entry))
        }
    }

    private fun urlsForScriptHost(
        project: Project,
        scriptFile: VirtualFile,
        index: DefoldIndex
    ): List<DefoldUrlEntry> {
        val root = project.rootProjectFolder ?: return emptyList()
        val rel = VfsUtilCore.getRelativePath(scriptFile, root, '/') ?: return emptyList()
        val scriptKey = "/$rel"

        val hostGos = index.hostsForResource(scriptKey)
        if (hostGos.isEmpty()) return emptyList()

        val collected = mutableListOf<DefoldUrlEntry>()
        hostGos.forEach { goKey -> collected += index.urlsForHost(goKey) }

        val parentCollections = hostGos.flatMap { index.collectionsContaining(it) }.toSet()
        parentCollections.forEach { collectionKey -> collected += index.urlsForHost(collectionKey) }

        return collected
    }

    private fun findEnclosingStringLiteral(position: PsiElement): LuaLiteralExpr? {
        val literal = PsiTreeUtil.getParentOfType(position, LuaLiteralExpr::class.java, false) ?: return null
        val text = literal.text
        if (text.length < 2) return null
        val first = text.first()
        return literal.takeIf { first == '"' || first == '\'' }
    }

    private fun encloseCallIsRequire(literal: LuaLiteralExpr): Boolean {
        val call = PsiTreeUtil.getParentOfType(literal, LuaCallExpr::class.java, true) ?: return false
        return call.expr.text.trim() == "require"
    }

    /** Returns the substring of the literal between its opening quote and the caret, or null. */
    private fun innerStringPrefix(literal: LuaLiteralExpr, caretOffset: Int): String? {
        val literalStart = literal.textRange.startOffset
        val literalEnd = literal.textRange.endOffset
        val contentStart = literalStart + 1
        if (caretOffset < contentStart || caretOffset > literalEnd) return null
        val text = literal.text
        val contentEnd = minOf(caretOffset - literalStart, text.length)
        return text.substring(1, contentEnd)
    }

    private fun toLookupElement(entry: DefoldUrlEntry): LookupElementBuilder = LookupElementBuilder
        .create(entry.url)
        .withTypeText(entry.typeLabel, true)
        .withTailText(sourceTailText(entry.sourceKey), true)

    private fun sourceTailText(key: String): String = if (key.isEmpty()) "" else " (${key.substringAfterLast('/')})"
}
