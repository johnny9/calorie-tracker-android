# USDA branded-food catalog builder

`build_catalog.py` turns the official FoodData Central Branded Foods JSON
download into a replaceable, read-only SQLite catalog for the Android app. It
runs off-device, uses only the Python standard library, and never makes a
network request.

## Source

USDA publishes the current bulk downloads on the [FoodData Central download
page](https://fdc.nal.usda.gov/download-datasets/). The April 30, 2026 Branded
Foods JSON archive is named
`FoodData_Central_branded_food_json_2026-04-30.zip`; it contains one JSON file
whose root is:

```json
{"BrandedFoods": [{"fdcId": 123, "description": "..."}]}
```

The generator streams that array and does not load the multi-gigabyte JSON
document into memory. The native food object is canonicalized as compact,
key-sorted UTF-8 JSON but is otherwise retained in full, including
`foodUpdateLog`, ingredients, attributes, microbes, trade channels, and fields
not yet known to the generator. Only the small searchable SQLite summary is
macro-focused.

Useful primary references:

- [FoodData Central API guide and public-domain/CC0 notice](https://fdc.nal.usda.gov/api-guide/)
- [Global Branded Food Products Database documentation](https://fdc.nal.usda.gov/GBFPD_Documentation/)
- [FoodData Central API specification](https://fdc.nal.usda.gov/api-spec/fdc_api.html)
- [Download field descriptions](https://fdc.nal.usda.gov/docs/Download_Field_Descriptions_Oct2020.pdf)

## Build

```bash
python3 tools/usda-pack/build_catalog.py \
  --input FoodData_Central_branded_food_json_2026-04-30.zip \
  --output dist/usda-catalog.sqlite \
  --manifest dist/usda-catalog-manifest.json
```

The release date is inferred from an official input/member filename. Use
`--release YYYY-MM-DD` for a locally renamed source and `--source-url` only
when provenance really differs. Blocks target 131,072 uncompressed bytes by
default; `--block-target-bytes` is available for format experiments.

The builder needs temporary disk space for an uncompressed staging table. It
keeps only one JSON object and one target payload block in Python memory, but a
full build can temporarily use several times the final catalog's disk size
while SQLite compacts the database.

Reference measurement for the official 2026-04-30 archive (SHA-256
`57b0f122e61cf2840f03c11e9520275d0d2018dc036e16273fcd3cd370db2256`):

- 455,458 input records became 432,565 indexed foods after 22,893 normalized
  GTIN duplicates; no source record was skipped.
- The catalog is 307,437,568 bytes. Deflating the database and JSON manifest
  as an unsigned outer ZIP produced 240,098,848 bytes; JAR signing adds a
  small amount of metadata.
- The build took 3:02.88 with 32,012 KiB maximum resident memory on the
  measured host. The largest observed named staging database was
  4,198,817,792 bytes; this does not count a transient unlinked SQLite scratch
  file, if the local SQLite build creates one.
- The largest native record was 68,947 bytes and the largest block was exactly
  the 131,072-byte target.

## Artifact contract

The two generator outputs are deliberately unsigned:

- `usda-catalog.sqlite` is the read-only catalog.
- `usda-catalog-manifest.json` is canonical JSON with the database name and
  SHA-256, source/license identity, counts, block/outlier measurements,
  logical-record digest, generator-script SHA-256/version, and runtime
  toolchain versions. It has no timestamp or local path.

Release automation packages those exact entries into the application-signed
catalog JAR. Signing and JAR creation do not belong in this generator.

Schema version 1 contains:

- `catalog_metadata(key, value)`, including `schema_version=1`, `pack_id`,
  `release_id`, `release_date`, `source_url`, `license=CC0-1.0`, and
  `attribution=USDA FoodData Central`.
- `catalog_food`, with stable `rowid`, FDC ID, display name/brand, normalized
  GTIN, native serving label, nullable per-serving label values
  (`calories_kcal`, `protein_g`, `carbs_g`, `fat_g`, `fiber_g`), and a payload
  block locator.
- `catalog_food_fts`, an FTS4 external-content index over name and brand.
- `catalog_gtin`, an exact normalized-GTIN-to-food-row mapping.
- `catalog_payload_block`, independently zlib-compressed native-record blocks
  with uncompressed size, record count, and SHA-256.

Each `record_offset` and `record_length` addresses one complete canonical JSON
object after its block is inflated. Records are concatenated without a
delimiter because the index supplies exact byte boundaries. A record larger
than the target block size is retained intact in its own larger block; the
manifest reports the largest observed record and block so the Android reader
can use evidence-based safety limits.

`labelNutrients` is the only source for indexed per-serving summaries. A
missing value stays SQL `NULL`; the generator never substitutes a per-100-gram
value or silently treats missing nutrition as zero. The full native
`foodNutrients` list remains available in the payload.

## Lookup semantics

Name/brand search is token-prefix search. For example, the Android query
`chomps beef stick` becomes:

```sql
SELECT f.*
FROM catalog_food_fts x
JOIN catalog_food f ON f.rowid = x.rowid
WHERE catalog_food_fts MATCH 'chomps* AND beef* AND stick*'
LIMIT 20;
```

This supports exact words and prefixes, not arbitrary mid-token substrings or
fuzzy spelling. Barcode lookup is an exact `catalog_gtin` query after the same
normalization used by the app:

- 8- and 13-digit values remain unchanged.
- 9- through 12-digit values are left-padded to GTIN-13.
- A 14-digit value beginning with zero is reduced to the equivalent GTIN-13.
- Other values are not indexed as GTINs.

When several source rows normalize to the same GTIN, the newest publication
date wins, followed by modified date and FDC ID. A canonical payload digest is
the final deterministic tie-breaker. Foods without a valid GTIN are keyed by
FDC ID.

## Reproducibility and replacement

Stable deduplication, canonical JSON, stable row/block order, fixed SQLite
pragmas, zlib level 9, and a final `VACUUM` make repeated builds byte-identical
for identical logical input/options under the same Python, SQLite, and zlib
toolchain. Those versions are recorded in the manifest rather than pretending
cross-toolchain byte identity.

Each output is fully written to a sibling temporary file before replacement.
The two separate filesystem moves are not a transaction, so automation must
consume them only after a successful generator exit and cross-check the
manifest digest. The Android installer verifies the signed outer pack and
manifest/database digest before atomically replacing its app-private active
catalog.

## Tests

The checked-in fixture follows the current native USDA shape and includes a
newer/older GTIN pair, an ambiguous liquid serving, a missing nutrient, an
oversized history-bearing record, and an unknown field.

```bash
python3 -m unittest discover -s tools/usda-pack/tests -v
```

The tests cover streaming, raw JSON and ZIP parity, byte determinism, schema
and required provenance, FTS prefix search, GTIN normalization, deduplication,
nullable nutrition, block checksums/locators, oversized singleton blocks, and
full native-payload retention.
