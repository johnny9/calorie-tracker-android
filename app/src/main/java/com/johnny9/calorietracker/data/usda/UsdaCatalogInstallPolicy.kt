package com.johnny9.calorietracker.data.usda

import java.io.File

internal object UsdaCatalogInstallPolicy {
    private const val VALIDATION_VERSION = 1
    private val catalogFileName = Regex("catalog-([0-9a-f]{64})\\.db")

    fun digestFromFileName(fileName: String): String? = catalogFileName.matchEntire(fileName)?.groupValues?.get(1)

    fun verificationMarker(digest: String): String {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        return "$VALIDATION_VERSION:$digest"
    }

    fun canReuseBundled(activeFileName: String, bundledDigest: String, storedVerificationMarker: String?): Boolean {
        val activeDigest = digestFromFileName(activeFileName) ?: return false
        return activeDigest == bundledDigest && storedVerificationMarker == verificationMarker(activeDigest)
    }

    /** Deletes only inactive, digest-addressed catalog databases. Returns files that could not be removed. */
    fun pruneInactive(directory: File, active: File): List<File> {
        require(active.parentFile == directory && digestFromFileName(active.name) != null && active.isFile)
        return directory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name != active.name && digestFromFileName(file.name) != null }
            .filterNot(File::delete)
    }
}
