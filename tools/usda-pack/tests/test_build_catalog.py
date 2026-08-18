from __future__ import annotations

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "branded_fixture.json"
SPEC = importlib.util.spec_from_file_location("usda_build_catalog", ROOT / "build_catalog.py")
assert SPEC is not None and SPEC.loader is not None
build_catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_catalog)


class SlowBytesIO(io.BytesIO):
    def read(self, size: int = -1) -> bytes:
        return super().read(11 if size < 0 else min(size, 11))


class BuildCatalogTest(unittest.TestCase):
    def build(self, directory: Path, input_path: Path = FIXTURE, name: str = "catalog"):
        database = directory / f"{name}.sqlite"
        manifest = directory / f"{name}.json"
        result = build_catalog.build_catalog(
            input_path,
            database,
            manifest,
            release="2026-04-30",
            block_target_bytes=700,
        )
        return database, manifest, result

    def test_streams_records_across_tiny_reads(self) -> None:
        records = list(build_catalog.iter_branded_foods(SlowBytesIO(FIXTURE.read_bytes())))
        self.assertEqual([200, 201, 300, 400, "not-an-integer"], [row["fdcId"] for row in records])

    def test_schema_search_gtin_payload_and_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database_path, manifest_path, manifest = self.build(Path(temporary))
            self.assertEqual(5, manifest["input_record_count"])
            self.assertEqual(3, manifest["record_count"])
            self.assertEqual(1, manifest["deduplicated_record_count"])
            self.assertEqual(1, manifest["skipped_record_count"])
            self.assertEqual("usda-catalog.sqlite", manifest["database_entry"])
            self.assertEqual("2", manifest["generator_version"])
            self.assertEqual(
                hashlib.sha256((ROOT / "build_catalog.py").read_bytes()).hexdigest(),
                manifest["generator_sha256"],
            )
            self.assertGreater(manifest["largest_record_bytes"], manifest["block_target_bytes"])
            self.assertEqual(
                hashlib.sha256(database_path.read_bytes()).hexdigest(),
                manifest["database_sha256"],
            )
            self.assertEqual(manifest, json.loads(manifest_path.read_text(encoding="utf-8")))

            database = sqlite3.connect(database_path)
            self.addCleanup(database.close)
            tables = {
                row[0]
                for row in database.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                )
            }
            self.assertTrue(
                {
                    "catalog_metadata",
                    "catalog_food",
                    "catalog_food_fts",
                    "catalog_gtin",
                    "catalog_payload_block",
                }.issubset(tables)
            )
            columns = [row[1] for row in database.execute("PRAGMA table_info(catalog_food)")]
            self.assertEqual(
                [
                    "rowid",
                    "fdc_id",
                    "name",
                    "brand",
                    "gtin",
                    "serving_label",
                    "calories_kcal",
                    "protein_g",
                    "carbs_g",
                    "fat_g",
                    "fiber_g",
                    "block_id",
                    "record_offset",
                    "record_length",
                ],
                columns,
            )
            metadata = dict(database.execute("SELECT key, value FROM catalog_metadata"))
            self.assertEqual("1", metadata["schema_version"])
            self.assertEqual("CC0-1.0", metadata["license"])
            self.assertEqual("USDA FoodData Central", metadata["attribution"])
            for required in (
                "pack_id",
                "release_id",
                "release_date",
                "source_url",
            ):
                self.assertTrue(metadata[required])

            matches = list(
                database.execute(
                    """
                    SELECT f.fdc_id, f.name
                    FROM catalog_food_fts x
                    JOIN catalog_food f ON f.rowid=x.rowid
                    WHERE catalog_food_fts MATCH 'chomps* AND beef* AND stick*'
                    """
                )
            )
            self.assertEqual([(201, "Original Beef Stick")], matches)
            cross_field_matches = list(
                database.execute(
                    """
                    SELECT f.fdc_id, f.name, f.brand
                    FROM catalog_food_fts x
                    JOIN catalog_food f ON f.rowid=x.rowid
                    WHERE catalog_food_fts MATCH 'bubbles* AND lime*'
                    """
                )
            )
            self.assertEqual([(300, "Lime Sparkling Water", "Bubbles Company")], cross_field_matches)
            gtin = database.execute(
                """
                SELECT f.fdc_id FROM catalog_gtin g
                JOIN catalog_food f ON f.rowid=g.food_rowid
                WHERE g.gtin=?
                """,
                ("0123456789012",),
            ).fetchone()
            self.assertEqual((300,), gtin)

            chomps = database.execute(
                """
                SELECT fdc_id, calories_kcal, protein_g, carbs_g, fat_g, fiber_g,
                       block_id, record_offset, record_length
                FROM catalog_food WHERE gtin='0856584004190'
                """
            ).fetchone()
            self.assertEqual((201, 100.0, 10.0, 0.0, 7.0, 0.0), chomps[:6])
            block = database.execute(
                """
                SELECT codec, uncompressed_size, record_count, sha256, payload
                FROM catalog_payload_block WHERE block_id=?
                """,
                (chomps[6],),
            ).fetchone()
            self.assertEqual("zlib", block[0])
            decoded = zlib.decompress(block[4])
            self.assertEqual(block[1], len(decoded))
            self.assertEqual(block[3], hashlib.sha256(decoded).hexdigest())
            payload = json.loads(decoded[chomps[7] : chomps[7] + chomps[8]])
            self.assertEqual(201, payload["fdcId"])
            self.assertEqual("Chomps Original Beef Stick", payload["description"])
            self.assertTrue(payload["fixtureUnknownField"]["mustRemain"])
            self.assertIn("foodUpdateLog", payload)
            self.assertIn("ingredients", payload)
            self.assertGreater(block[1], 700)
            self.assertEqual(1, block[2])

            yogurt = database.execute(
                "SELECT fiber_g FROM catalog_food WHERE fdc_id=400"
            ).fetchone()
            self.assertEqual((None,), yogurt)
            self.assertEqual(("ok",), database.execute("PRAGMA integrity_check").fetchone())

    def test_product_titles_remove_only_a_duplicate_brand(self) -> None:
        self.assertEqual(
            "Original Beef Stick",
            build_catalog._product_name("Chomps Original Beef Stick", "Chomps"),
        )
        self.assertEqual(
            "Original Beef Stick",
            build_catalog._product_name("CHOMPS® — Original Beef Stick", "Chomps"),
        )
        self.assertEqual(
            "Original Beef Stick",
            build_catalog._product_name("Original Beef Stick - Chomps", "Chomps"),
        )
        self.assertEqual("Goat Cheese", build_catalog._product_name("Goat Cheese", "Go"))
        self.assertEqual("Chomps", build_catalog._product_name("Chomps", "Chomps"))

    def test_raw_and_zip_builds_are_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            archive_path = directory / "FoodData_Central_branded_food_json_2026-04-30.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("FoodData_Central_branded_food_json_2026-04-30.json", FIXTURE.read_bytes())

            raw_db, raw_manifest, _ = self.build(directory, FIXTURE, "raw")
            zip_db, zip_manifest, _ = self.build(directory, archive_path, "zip")
            self.assertEqual(raw_db.read_bytes(), zip_db.read_bytes())
            self.assertEqual(raw_manifest.read_bytes(), zip_manifest.read_bytes())

    def test_rejects_missing_release_and_malformed_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            malformed = directory / "fixture.json"
            malformed.write_text('{"foods": []}', encoding="utf-8")
            with self.assertRaisesRegex(build_catalog.CatalogBuildError, "release date"):
                build_catalog.build_catalog(
                    malformed,
                    directory / "out.sqlite",
                    directory / "out.json",
                )
            with self.assertRaisesRegex(build_catalog.CatalogBuildError, "BrandedFoods"):
                build_catalog.build_catalog(
                    malformed,
                    directory / "out.sqlite",
                    directory / "out.json",
                    release="2026-04-30",
                )


if __name__ == "__main__":
    unittest.main()
