package com.allerix.app

/**
 * Semantic version comparison for update checks.
 *
 * Stable releases sort after prereleases with the same numeric core:
 * 1.4.0 > 1.4.0-beta01. Build metadata does not affect precedence.
 */
internal fun isNewerAllervueVersion(candidate: String, installed: String): Boolean {
    data class Version(val core: List<Long>, val prerelease: List<String>?)

    fun parse(raw: String): Version? {
        val value = raw.trim().removePrefix("v").removePrefix("V")
        val withoutBuild = value.substringBefore('+')
        val coreText = withoutBuild.substringBefore('-')
        val preText = withoutBuild.substringAfter('-', "").takeIf { '-' in withoutBuild }
        if (coreText.isBlank()) return null
        val core = coreText.split('.').map { part ->
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            part.toLongOrNull() ?: return null
        }
        val prerelease = preText?.split('.')?.also { parts ->
            if (parts.any { it.isBlank() || it.any { ch -> !ch.isLetterOrDigit() && ch != '-' } }) return null
        }
        return Version(core, prerelease)
    }

    val next = parse(candidate) ?: return false
    val current = parse(installed) ?: return false

    for (index in 0 until maxOf(next.core.size, current.core.size)) {
        val left = next.core.getOrElse(index) { 0L }
        val right = current.core.getOrElse(index) { 0L }
        if (left != right) return left > right
    }

    val nextPre = next.prerelease
    val currentPre = current.prerelease
    if (nextPre == null) return currentPre != null
    if (currentPre == null) return false

    for (index in 0 until maxOf(nextPre.size, currentPre.size)) {
        if (index >= nextPre.size) return false
        if (index >= currentPre.size) return true
        val left = nextPre[index]
        val right = currentPre[index]
        if (left == right) continue
        val leftNumeric = left.toLongOrNull()
        val rightNumeric = right.toLongOrNull()
        return when {
            leftNumeric != null && rightNumeric != null -> leftNumeric > rightNumeric
            leftNumeric != null -> false
            rightNumeric != null -> true
            else -> left.compareTo(right, ignoreCase = true) > 0
        }
    }
    return false
}
