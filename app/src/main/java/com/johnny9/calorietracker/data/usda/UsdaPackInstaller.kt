package com.johnny9.calorietracker.data.usda

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.jar.JarInputStream

internal class UsdaPackInstaller(private val context: Context) {
    private val directory = File(context.filesDir, "usda-catalog")
    private val activePointer = File(directory, "active")
    private val bundledVersionMarker = File(directory, "bundled-app-version")
    private val verifiedCatalogMarker = File(directory, "verified-catalog")

    fun activeDatabase(): File? {
        if (!activePointer.isFile || activePointer.length() !in 1L..128L) return null
        val name = runCatching { activePointer.readText().trim() }.getOrNull().orEmpty()
        if (UsdaCatalogInstallPolicy.digestFromFileName(name) == null) return null
        return File(directory, name).takeIf(File::isFile)
    }

    /**
     * Rechecks the bundled pack after every APK version change. A failed update leaves the
     * existing active pointer untouched and will be retried after the next process start.
     */
    fun installBundledIfNeeded(force: Boolean = false, validator: (File, UsdaPackManifest) -> Unit): File? {
        val current = activeDatabase()
        val appVersion = applicationVersionMarker()
        val alreadyChecked = bundledVersionMarker.isFile && bundledVersionMarker.length() in 1L..64L &&
            runCatching { bundledVersionMarker.readText().trim() == appVersion }.getOrDefault(false)
        if (alreadyChecked && !force && current != null) return current
        var stream = try {
            context.assets.open(UsdaPackFormat.ASSET_PATH)
        } catch (_: Exception) {
            writeMarker(bundledVersionMarker, appVersion)
            return current
        }
        if (!force && current != null) {
            // This header-only shortcut is restricted to the asset inside the APK, whose bytes
            // are covered by the APK signature. User-selected replacements always use the full
            // JAR-entry signer, manifest, checksum, and database validation path below.
            val header = stream.use(::readBundledHeader)
            if (
                UsdaCatalogInstallPolicy.canReuseBundled(
                    activeFileName = current.name,
                    bundledDigest = header.databaseSha256,
                    storedVerificationMarker = readVerifiedCatalogMarker(),
                )
            ) {
                writeMarker(bundledVersionMarker, appVersion)
                return current
            }
            stream = context.assets.open(UsdaPackFormat.ASSET_PATH)
        }
        val installed = stream.use { installSignedJar(it, validator) }
        writeMarker(bundledVersionMarker, appVersion)
        return installed
    }

    fun installSignedJar(input: InputStream, validator: (File, UsdaPackManifest) -> Unit): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw UsdaCatalogException("Unable to create the USDA catalog directory")
        }
        val temporary = File(directory, "incoming-${System.nanoTime()}.db")
        try {
            val expectedSignerDigests = applicationSignerDigests()
            var databaseDigest: String? = null
            var databaseCertificates: Array<Certificate>? = null
            var manifestBytes: ByteArray? = null
            var manifestCertificates: Array<Certificate>? = null
            var checksumsCertificates: Array<Certificate>? = null
            var databaseSeen = false
            var packManifestSeen = false
            var checksumsSeen = false
            var jarSchemaVersion: String? = null
            var jarDatabaseSha256: String? = null
            var jarRelease: String? = null
            var jarRevision: Long? = null
            var entryCount = 0
            var signatureMetadataBytes = 0

            val jar = try {
                JarInputStream(input.buffered(), true)
            } catch (error: Exception) {
                throw UsdaCatalogException("USDA catalog pack is not a readable signed JAR", error)
            }
            try {
                val attributes = jar.manifest?.mainAttributes
                    ?: throw UsdaCatalogException("USDA catalog pack has no JAR manifest")
                jarSchemaVersion = attributes.getValue("Catalog-Schema-Version")?.trim()
                jarDatabaseSha256 = attributes.getValue("Catalog-SHA-256")?.trim()?.lowercase()?.takeIf(SHA256::matches)
                jarRelease = attributes.getValue("Catalog-Release")?.trim()?.takeIf(String::isNotEmpty)
                jarRevision = attributes.getValue("Catalog-Revision")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (entry.isDirectory) continue
                    entryCount += 1
                    if (entryCount > MAX_JAR_ENTRIES) throw UsdaCatalogException("USDA catalog pack contains too many entries")
                    when (entry.name) {
                        UsdaPackFormat.DATABASE_ENTRY -> {
                            if (databaseSeen) throw UsdaCatalogException("USDA catalog pack contains more than one database")
                            databaseSeen = true
                            val digest = MessageDigest.getInstance("SHA-256")
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(64 * 1_024)
                                var total = 0L
                                while (true) {
                                    val count = jar.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_DATABASE_BYTES) throw UsdaCatalogException("USDA catalog database is too large")
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                            databaseDigest = UsdaPackFormat.hex(digest.digest())
                            databaseCertificates = entry.certificates
                        }

                        UsdaPackFormat.PACK_MANIFEST_ENTRY -> {
                            if (packManifestSeen) throw UsdaCatalogException("USDA catalog pack contains more than one pack manifest")
                            packManifestSeen = true
                            manifestBytes = jar.readBounded(MAX_MANIFEST_BYTES, "pack manifest")
                            manifestCertificates = entry.certificates
                        }

                        CHECKSUMS_ENTRY -> {
                            if (checksumsSeen) throw UsdaCatalogException("USDA catalog pack contains more than one checksum list")
                            checksumsSeen = true
                            jar.readBounded(MAX_MANIFEST_BYTES, "checksum list")
                            checksumsCertificates = entry.certificates
                        }

                        else -> {
                            if (!entry.name.startsWith("META-INF/")) {
                                throw UsdaCatalogException("USDA catalog pack contains an unexpected entry: ${entry.name.take(80)}")
                            }
                            signatureMetadataBytes += jar.readBounded(MAX_SIGNATURE_METADATA_BYTES, "JAR signature metadata").size
                            if (signatureMetadataBytes > MAX_SIGNATURE_METADATA_BYTES) {
                                throw UsdaCatalogException("USDA catalog JAR signature metadata is too large")
                            }
                        }
                    }
                }
            } catch (error: SecurityException) {
                throw UsdaCatalogException("USDA catalog JAR signature is invalid", error)
            } finally {
                jar.close()
            }

            val digest = databaseDigest ?: throw UsdaCatalogException("USDA catalog pack contains no database")
            val packManifest = parsePackManifest(
                manifestBytes ?: throw UsdaCatalogException("USDA catalog pack contains no pack manifest"),
            )
            if (jarSchemaVersion == null || jarDatabaseSha256 == null || jarRelease == null || jarRevision == null) {
                throw UsdaCatalogException("USDA catalog JAR manifest is missing required catalog attributes")
            }
            if (packManifest.schemaVersion != UsdaPackFormat.SCHEMA_VERSION) {
                throw UsdaCatalogException("USDA catalog pack schema is unsupported")
            }
            if (jarSchemaVersion != packManifest.schemaVersion) {
                throw UsdaCatalogException("USDA catalog schema differs between its signed manifests")
            }
            if (packManifest.databaseEntry != UsdaPackFormat.DATABASE_ENTRY) {
                throw UsdaCatalogException("USDA catalog manifest names an unsupported database entry")
            }
            if (packManifest.databaseSha256 != digest) {
                throw UsdaCatalogException("USDA catalog database checksum failed")
            }
            if (jarDatabaseSha256 != packManifest.databaseSha256) {
                throw UsdaCatalogException("USDA catalog checksum differs between its signed manifests")
            }
            if (jarRelease != packManifest.source.releaseDate) {
                throw UsdaCatalogException("USDA catalog release differs between its signed manifests")
            }
            if (packManifest.databaseSizeBytes != temporary.length()) {
                throw UsdaCatalogException("USDA catalog database size does not match its pack manifest")
            }
            verifySignedByApplication(databaseCertificates, expectedSignerDigests, "database")
            verifySignedByApplication(manifestCertificates, expectedSignerDigests, "pack manifest")
            if (!checksumsSeen) throw UsdaCatalogException("USDA catalog pack contains no checksum list")
            verifySignedByApplication(checksumsCertificates, expectedSignerDigests, "checksum list")

            validator(temporary, packManifest)
            val finalName = "catalog-$digest.db"
            val finalFile = File(directory, finalName)
            atomicMove(temporary, finalFile)
            writeMarker(activePointer, finalName)
            writeMarker(verifiedCatalogMarker, UsdaCatalogInstallPolicy.verificationMarker(digest))
            return finalFile
        } finally {
            temporary.delete()
        }
    }

    fun activate(file: File?) {
        if (file == null) {
            activePointer.delete()
            return
        }
        if (file.parentFile != directory || UsdaCatalogInstallPolicy.digestFromFileName(file.name) == null || !file.isFile) {
            throw UsdaCatalogException("Cannot activate an invalid USDA catalog path")
        }
        writeMarker(activePointer, file.name)
    }

    fun pruneInactiveCatalogs(active: File): List<File> = UsdaCatalogInstallPolicy.pruneInactive(directory, active)

    private fun verifySignedByApplication(
        certificates: Array<Certificate>?,
        expectedSignerDigests: Set<String>,
        description: String,
    ) {
        val entrySignerDigests = certificates.orEmpty().map(::certificateDigest).toSet()
        if (entrySignerDigests.intersect(expectedSignerDigests).isEmpty()) {
            throw UsdaCatalogException("USDA catalog $description is not signed by this application's signing identity")
        }
    }

    private fun parsePackManifest(bytes: ByteArray): UsdaPackManifest {
        val values = try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (error: Exception) {
            throw UsdaCatalogException("USDA catalog pack manifest is unreadable", error)
        }
        fun text(vararg keys: String): String? = keys.asSequence()
            .mapNotNull { key -> values[key]?.jsonPrimitive?.contentOrNull?.trim() }
            .firstOrNull(String::isNotEmpty)
        val schemaVersion = text("schema_version", "schemaVersion")
            ?: throw UsdaCatalogException("USDA catalog pack manifest has no schema version")
        val databaseEntry = text("database_entry", "databaseEntry")
            ?: throw UsdaCatalogException("USDA catalog pack manifest has no database entry")
        val databaseSha256 = text("database_sha256", "databaseSha256")
            ?.lowercase()
            ?.takeIf(SHA256::matches)
            ?: throw UsdaCatalogException("USDA catalog pack manifest has no valid database checksum")
        val databaseSizeBytes = text("database_size_bytes", "databaseSizeBytes")
            ?.toLongOrNull()
            ?.takeIf { it in 1..MAX_DATABASE_BYTES }
            ?: throw UsdaCatalogException("USDA catalog pack manifest has no valid database size")
        val releaseDate = text("release_date", "releaseDate")
            ?: throw UsdaCatalogException("USDA catalog pack manifest has no release date")
        fun requiredSource(key: String): String = text(key)
            ?: throw UsdaCatalogException("USDA catalog pack manifest has no $key")
        return UsdaPackManifest(
            schemaVersion = schemaVersion,
            databaseEntry = databaseEntry,
            databaseSha256 = databaseSha256,
            databaseSizeBytes = databaseSizeBytes,
            source = UsdaCatalogSource(
                packId = requiredSource("pack_id"),
                releaseId = requiredSource("release_id"),
                releaseDate = releaseDate,
                sourceUrl = requiredSource("source_url"),
                license = requiredSource("license"),
                attribution = requiredSource("attribution"),
            ),
        )
    }

    private fun readBundledHeader(input: InputStream): BundledHeader {
        try {
            JarInputStream(input.buffered(), false).use { jar ->
                val attributes = jar.manifest?.mainAttributes
                    ?: throw UsdaCatalogException("Bundled USDA catalog has no JAR manifest")
                val schemaVersion = attributes.getValue("Catalog-Schema-Version")?.trim()
                if (schemaVersion != UsdaPackFormat.SCHEMA_VERSION) {
                    throw UsdaCatalogException("Bundled USDA catalog schema is unsupported")
                }
                val digest = attributes.getValue("Catalog-SHA-256")
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(SHA256::matches)
                    ?: throw UsdaCatalogException("Bundled USDA catalog has no valid database checksum")
                val release = attributes.getValue("Catalog-Release")?.trim()?.takeIf(String::isNotEmpty)
                    ?: throw UsdaCatalogException("Bundled USDA catalog has no release")
                val revision = attributes.getValue("Catalog-Revision")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw UsdaCatalogException("Bundled USDA catalog has no positive revision")
                return BundledHeader(digest, release, revision)
            }
        } catch (error: UsdaCatalogException) {
            throw error
        } catch (error: Exception) {
            throw UsdaCatalogException("Bundled USDA catalog header is unreadable", error)
        }
    }

    private fun readVerifiedCatalogMarker(): String? {
        if (!verifiedCatalogMarker.isFile || verifiedCatalogMarker.length() !in 1L..128L) return null
        return runCatching { verifiedCatalogMarker.readText().trim() }.getOrNull()
    }

    private fun applicationSignerDigests(): Set<String> {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = packageInfo.signingInfo ?: throw UsdaCatalogException("Unable to read application signing identity")
        return signingInfo.apkContentsSigners.map { signature -> UsdaPackFormat.sha256(signature.toByteArray()) }.toSet()
    }

    private fun applicationVersionMarker(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.longVersionCode.toString()
    }

    private fun certificateDigest(certificate: Certificate): String = UsdaPackFormat.sha256(certificate.encoded)

    private fun writeMarker(destination: File, value: String) {
        if (!directory.exists() && !directory.mkdirs()) throw UsdaCatalogException("Unable to create the USDA catalog directory")
        val temporary = File(directory, "${destination.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicMove(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun atomicMove(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun InputStream.readBounded(maximumBytes: Int, description: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) throw UsdaCatalogException("USDA catalog $description is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class BundledHeader(val databaseSha256: String, val release: String, val revision: Long)

    companion object {
        private const val MAX_DATABASE_BYTES = 1_500_000_000L
        private const val MAX_MANIFEST_BYTES = 64 * 1_024
        private const val MAX_SIGNATURE_METADATA_BYTES = 1 * 1_024 * 1_024
        private const val MAX_JAR_ENTRIES = 64
        private const val CHECKSUMS_ENTRY = "SHA256SUMS"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
