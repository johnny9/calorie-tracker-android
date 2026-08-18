# USDA offline catalog

The branded-food catalog is a replaceable release artifact, not part of the
user's Room database and not rebuilt during an ordinary Android build. This
keeps food-reference updates independent from diary migrations while preserving
a useful offline application after installation.

## Artifact layout

`usda-catalog-pack.jar` is an immutable, signed container with these root
entries:

- `usda-catalog.sqlite` — the read-only search catalog.
- `usda-catalog-manifest.json` — source release, schema, counts, hashes, and
  deterministic-build metadata.
- `SHA256SUMS` — checksums for the two generated files.

The JAR manifest records catalog schema version `1` and the SHA-256 of the
SQLite entry. GitHub Actions signs the JAR with the same permanent certificate
used for continuous APKs. The Android installer reads every signed entry,
requires the catalog signer to match the installed APK signer, verifies the
manifest and database hash, validates the schema and SQLite integrity, and only
then switches the active catalog atomically. A failed or interrupted install
leaves the previous catalog active.

The active catalog lives separately from the mutable Room database. Updating a
catalog can change future search results, but it cannot rewrite existing diary
or recipe snapshots. A selected USDA result is copied into the user's local
food collection with its USDA source identifier before it is logged.

The GitHub release asset is independently downloadable and replaceable. Trusted
APKs currently select one exact catalog release at build time and embed those
same bytes for a clean-install offline experience. The application also has a
verified replacement-installer API for a future file-picker/update screen; that
screen is not part of the current UI.

## Search and compression

Product names and brands are held as separate columns in a SQLite FTS4 index so a query does not require
inflating the entire USDA download. GTIN/UPC values have a separate exact-match
index. A duplicate leading or trailing brand is removed from the display name
while the original USDA description remains unchanged in the source payload.
Search terms use token-prefix matching across both name and brand; arbitrary
mid-token substring matching is intentionally not supported.

Small summary columns provide the values needed to render result rows. Full
canonical USDA JSON records are concatenated into independently zlib-compressed
blocks. Each row stores its block, offset, and length. The application inflates
only the block needed for a selected result and verifies that block before
reading the record. This is block compression rather than one compressed frame
per product, avoiding hundreds of thousands of tiny frames while retaining
random access.

## Measured April 2026 catalog

The complete processing smoke test used USDA's official April 30, 2026 Branded
Foods JSON archive. It produced these measured results:

- 455,458 source rows and 432,565 distinct indexed products after deterministic
  same-GTIN deduplication.
- 24,651 independently compressed payload blocks; the largest record was
  68,947 bytes and the largest uncompressed block was 131,072 bytes.
- A 307,437,568-byte installed SQLite catalog and a 240,438,660-byte signed
  transport JAR (about 229.3 MiB).
- About three minutes of processing time, 32 MB peak process memory, and a
  4.2 GB peak staging database on the test workstation.
- Sixteen offline matches for `chomps* AND beef* AND stick*`; GTIN
  `0856584004190` resolves to FDC ID `2511051`.

The signed release APK containing this pack measured 292,071,311 bytes (about
278.5 MiB). Installation and catalog replacement need room for the APK or
downloaded pack, the previous active database, and the incoming database until
verification and atomic activation finish. Budget roughly 1 GiB of free space
for this catalog size. After a successful activation the application removes
inactive catalog database copies; a failed activation keeps the previous
catalog available.

## Rebuilding locally

The processor accepts USDA's Branded Foods JSON file or its ZIP archive:

```bash
python3 tools/usda-pack/build_catalog.py \
  --input FoodData_Central_branded_food_json_2026-04-30.zip \
  --output dist/usda-catalog.sqlite \
  --manifest dist/usda-catalog-manifest.json \
  --release 2026-04-30
```

The same logical input, options, Python/SQLite/zlib toolchain, and processor
revision must produce byte-identical SQLite and JSON outputs. The processor
streams the multi-gigabyte JSON member rather than loading it into memory,
normalizes search fields, orders rows deterministically, and records its
toolchain in the manifest.

Run the processor tests before changing its schema or normalization rules:

```bash
python3 -m unittest discover -s tools/usda-pack/tests -v
```

## GitHub update workflow

The USDA catalog workflow is manual. Its defaults pin the April 2026 source
URL, release date, and archive SHA-256. A future update must explicitly provide
all three values. The workflow:

1. Restores or downloads the pinned USDA archive and verifies its SHA-256.
2. Runs the deterministic processor twice and compares both outputs.
3. Builds and signs `usda-catalog-pack.jar` with the continuous Android key.
4. Verifies the JAR signature, signer fingerprint, SQLite integrity, manifest,
   and checksums.
5. Publishes an immutable `usda-catalog-v<release>-r<revision>` GitHub release.

The raw USDA archive is cached by release and checksum to avoid repeated
downloads, but the immutable catalog release is the durable build input; GitHub
cache eviction never changes an Android build's selected catalog.

The positive pack revision allows a corrected generator to publish a new
artifact from the same dated USDA source without deleting or mutating an older
release. The repository variable `USDA_CATALOG_RELEASE_TAG` chooses the exact
catalog release consumed by trusted Android builds. There is no implicit "latest"
lookup. The Android workflow downloads and verifies that exact signed asset,
embeds the identical JAR in the APK, and verifies the embedded bytes after the
APK is assembled. Pull requests and ordinary debug builds do not receive
signing secrets and may run without a catalog; the existing small bundled food
set remains available.

To update the production catalog:

1. Select a dated USDA Branded Foods JSON download and calculate its SHA-256.
2. Dispatch the catalog workflow with the new date, pack revision, URL, and hash.
3. Inspect the reported record counts, output sizes, checksums, and build log.
4. Set `USDA_CATALOG_RELEASE_TAG` to the new immutable release tag.
5. Run the Android workflow and verify its catalog build information before
   distributing the APK.

Never embed a FoodData Central API key. Bulk files are built off-device from
USDA's downloadable CC0 data; the Android application does not need a USDA
network request or credential.

The catalog JAR signer must match the final APK app-signing identity. The direct
GitHub APK channel satisfies this by signing both with the permanent continuous
key. A future Google Play build must sign the catalog with the Play app-signing
identity (or preserve that same identity during enrollment); merely signing it
with an upload key is not sufficient.
