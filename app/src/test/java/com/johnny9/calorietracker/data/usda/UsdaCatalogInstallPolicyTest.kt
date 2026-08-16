package com.johnny9.calorietracker.data.usda

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsdaCatalogInstallPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reusesBundledCatalogOnlyWhenDigestAndValidationMarkerMatch() {
        val digest = "a".repeat(64)
        val fileName = "catalog-$digest.db"
        val marker = UsdaCatalogInstallPolicy.verificationMarker(digest)

        assertTrue(UsdaCatalogInstallPolicy.canReuseBundled(fileName, digest, marker))
        assertFalse(UsdaCatalogInstallPolicy.canReuseBundled(fileName, "b".repeat(64), marker))
        assertFalse(UsdaCatalogInstallPolicy.canReuseBundled(fileName, digest, null))
        assertFalse(UsdaCatalogInstallPolicy.canReuseBundled("catalog.db", digest, marker))
    }

    @Test
    fun prunesOnlyInactiveDigestNamedCatalogs() {
        val directory = temporaryFolder.newFolder("catalogs")
        val active = directory.resolve("catalog-${"a".repeat(64)}.db").apply { writeText("active") }
        val inactive = directory.resolve("catalog-${"b".repeat(64)}.db").apply { writeText("inactive") }
        val unrelated = directory.resolve("notes.db").apply { writeText("keep") }
        val similarlyNamedDirectory = directory.resolve("catalog-${"c".repeat(64)}.db").apply { mkdir() }

        assertTrue(UsdaCatalogInstallPolicy.pruneInactive(directory, active).isEmpty())
        assertTrue(active.isFile)
        assertFalse(inactive.exists())
        assertTrue(unrelated.isFile)
        assertTrue(similarlyNamedDirectory.isDirectory)
    }

    @Test
    fun catalogMetadataMustMatchSignedManifestProvenance() {
        val source = source()
        source.requireMatches(source.copy())

        assertThrows(UsdaCatalogException::class.java) {
            source.requireMatches(source.copy(releaseDate = "2026-05-01"))
        }
        assertThrows(UsdaCatalogException::class.java) {
            source.requireMatches(source.copy(sourceUrl = "https://fdc.nal.usda.gov/other.zip"))
        }
        assertThrows(UsdaCatalogException::class.java) {
            source.requireMatches(source.copy(packId = "different-pack"))
        }
    }

    private fun source() = UsdaCatalogSource(
        packId = "usda-branded-2026-04-30-abc",
        releaseId = "FoodData_Central_branded_food_json_2026-04-30",
        releaseDate = "2026-04-30",
        sourceUrl = "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_branded_food_json_2026-04-30.zip",
        license = "CC0-1.0",
        attribution = "USDA FoodData Central",
    )
}
