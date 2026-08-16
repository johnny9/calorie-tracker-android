#!/usr/bin/env python3
"""Build a deterministic, offline USDA branded-food SQLite catalog.

The input is either the JSON file or the ZIP published on the FoodData Central
download page.  The output database is intentionally an unsigned build
artifact.  Release automation is responsible for wrapping it in the
application-signed catalog JAR.
"""

from __future__ import annotations

import argparse
import codecs
import contextlib
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import sqlite3
import sys
import unicodedata
from typing import Any, BinaryIO, Iterator, Mapping, Sequence
import zipfile
import zlib


SCHEMA_VERSION = 1
GENERATOR_VERSION = "1"
DEFAULT_BLOCK_TARGET_BYTES = 128 * 1024
DATABASE_ENTRY = "usda-catalog.sqlite"
LICENSE = "CC0-1.0"
ATTRIBUTION = "USDA FoodData Central"
DOWNLOAD_PAGE = "https://fdc.nal.usda.gov/download-datasets/"
DOWNLOAD_URL = (
    "https://fdc.nal.usda.gov/fdc-datasets/"
    "FoodData_Central_branded_food_json_{release}.zip"
)
_READ_CHUNK_BYTES = 256 * 1024
_MAX_ROOT_PREFIX_CHARS = 1024 * 1024
_PREFIX = re.compile(r'^\s*\{\s*"BrandedFoods"\s*:\s*\[')
_RELEASE = re.compile(r"(?<!\d)(20\d{2}-\d{2}-\d{2})(?!\d)")
_DIGITS = re.compile(r"[0-9]{8,14}")
_NUTRIENT_IDS = {
    "calories_kcal": 1008,
    "protein_g": 1003,
    "carbs_g": 1005,
    "fat_g": 1004,
    "fiber_g": 1079,
}
_LABEL_KEYS = {
    "calories_kcal": "calories",
    "protein_g": "protein",
    "carbs_g": "carbohydrates",
    "fat_g": "fat",
    "fiber_g": "fiber",
}


class CatalogBuildError(RuntimeError):
    """The source dataset cannot produce a valid catalog."""


def _reject_nonstandard_number(value: str) -> None:
    raise CatalogBuildError(f"Non-standard JSON number is not supported: {value}")


_JSON_DECODER = json.JSONDecoder(parse_constant=_reject_nonstandard_number)


class _StreamingText:
    def __init__(self, source: BinaryIO) -> None:
        self._source = source
        self._decoder = codecs.getincrementaldecoder("utf-8-sig")("strict")
        self.buffer = ""
        self.eof = False

    def read_more(self) -> bool:
        if self.eof:
            return False
        chunk = self._source.read(_READ_CHUNK_BYTES)
        if chunk:
            self.buffer += self._decoder.decode(chunk)
            return True
        self.buffer += self._decoder.decode(b"", final=True)
        self.eof = True
        return False

    def strip_leading_space(self) -> None:
        self.buffer = self.buffer.lstrip()


def iter_branded_foods(source: BinaryIO) -> Iterator[dict[str, Any]]:
    """Incrementally yield objects from the USDA ``BrandedFoods`` root array."""

    text = _StreamingText(source)
    while True:
        match = _PREFIX.match(text.buffer)
        if match is not None:
            text.buffer = text.buffer[match.end() :]
            break
        if text.eof:
            raise CatalogBuildError('Expected a JSON object containing "BrandedFoods"')
        if len(text.buffer) > _MAX_ROOT_PREFIX_CHARS:
            raise CatalogBuildError('Could not find the "BrandedFoods" root array')
        text.read_more()

    first = True
    while True:
        text.strip_leading_space()
        while not text.buffer and not text.eof:
            text.read_more()
            text.strip_leading_space()
        if not text.buffer:
            raise CatalogBuildError("USDA JSON ended before the BrandedFoods array closed")

        if text.buffer.startswith("]"):
            text.buffer = text.buffer[1:]
            break

        if not first:
            if not text.buffer.startswith(","):
                raise CatalogBuildError("Expected a comma between BrandedFoods records")
            text.buffer = text.buffer[1:]
            text.strip_leading_space()
            while not text.buffer and not text.eof:
                text.read_more()
                text.strip_leading_space()

        while True:
            try:
                record, consumed = _JSON_DECODER.raw_decode(text.buffer)
                break
            except json.JSONDecodeError as error:
                if text.eof:
                    raise CatalogBuildError(
                        f"Malformed USDA JSON near character {error.pos}: {error.msg}"
                    ) from error
                text.read_more()
        if not isinstance(record, dict):
            raise CatalogBuildError("Each BrandedFoods item must be a JSON object")
        text.buffer = text.buffer[consumed:]
        first = False
        yield record

    while not text.eof:
        text.read_more()
        if len(text.buffer) > _MAX_ROOT_PREFIX_CHARS:
            raise CatalogBuildError("Unexpected data follows the BrandedFoods array")
    if not re.fullmatch(r"\s*}\s*", text.buffer):
        raise CatalogBuildError("Unexpected data follows the BrandedFoods array")


def normalize_gtin(raw: Any) -> str | None:
    if not isinstance(raw, str):
        return None
    value = raw.strip()
    if _DIGITS.fullmatch(value) is None:
        return None
    if 9 <= len(value) <= 12:
        return value.zfill(13)
    if len(value) == 14 and value.startswith("0"):
        return value[1:]
    return value


def _text(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = " ".join(value.split())
    return normalized or None


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    result = float(value)
    if not math.isfinite(result) or result < 0:
        return None
    return result


def _integer(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        result = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    if result <= 0 or str(result) != str(value).strip():
        return None
    return result


def _date(value: Any) -> str:
    text = _text(value)
    if text is None:
        return ""
    for pattern in ("%Y-%m-%d", "%m/%d/%Y", "%m/%d/%y"):
        try:
            return dt.datetime.strptime(text, pattern).date().isoformat()
        except ValueError:
            pass
    return ""


def _format_number(value: float) -> str:
    return format(value, ".15g")


def _serving_label(record: Mapping[str, Any]) -> str:
    household = _text(record.get("householdServingFullText"))
    if household is not None:
        return household
    amount = _number(record.get("servingSize"))
    unit = _text(record.get("servingSizeUnit"))
    if amount is not None and unit is not None:
        return f"{_format_number(amount)} {unit}"
    return "Serving not specified"


def _label_summaries(record: Mapping[str, Any]) -> dict[str, float | None]:
    labels = record.get("labelNutrients")
    if not isinstance(labels, dict):
        labels = {}
    result: dict[str, float | None] = {}
    for output_name, label_name in _LABEL_KEYS.items():
        item = labels.get(label_name)
        result[output_name] = _number(item.get("value")) if isinstance(item, dict) else None
    return result


def _per_100_summaries(record: Mapping[str, Any]) -> dict[str, float | None]:
    """Extract per-100 values for diagnostics without changing the payload.

    These are not placed in the serving-summary columns.  Keeping this helper
    explicit documents why a missing label value remains SQL NULL rather than
    being silently substituted with a differently based value.
    """

    wanted = {nutrient_id: name for name, nutrient_id in _NUTRIENT_IDS.items()}
    result: dict[str, float | None] = {name: None for name in _NUTRIENT_IDS}
    nutrients = record.get("foodNutrients")
    if not isinstance(nutrients, list):
        return result
    for item in nutrients:
        if not isinstance(item, dict):
            continue
        nutrient = item.get("nutrient")
        if not isinstance(nutrient, dict):
            continue
        nutrient_id = _integer(nutrient.get("id"))
        output_name = wanted.get(nutrient_id)
        if output_name is not None and result[output_name] is None:
            result[output_name] = _number(item.get("amount"))
    return result


def _sort_key(value: str | None) -> str:
    return unicodedata.normalize("NFKC", value or "").casefold()


def _canonical_json(record: Mapping[str, Any]) -> str:
    try:
        return json.dumps(
            record,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as error:
        raise CatalogBuildError("A USDA food record cannot be serialized canonically") from error


def _normalized_row(record: Mapping[str, Any]) -> tuple[Any, ...] | None:
    fdc_id = _integer(record.get("fdcId"))
    name = _text(record.get("description"))
    if fdc_id is None or name is None:
        return None
    brand = _text(record.get("brandName")) or _text(record.get("brandOwner"))
    gtin = normalize_gtin(record.get("gtinUpc"))
    serving_label = _serving_label(record)
    summaries = _label_summaries(record)
    # Exercise and document extraction of the differently based nutrient list;
    # it deliberately does not fill missing per-serving label values.
    _per_100_summaries(record)
    payload = _canonical_json(record)
    publication_date = _date(record.get("publicationDate"))
    modified_date = _date(record.get("modifiedDate"))
    payload_sha = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    winner_key = f"{publication_date}|{modified_date}|{fdc_id:020d}|{payload_sha}"
    dedupe_key = f"gtin:{gtin}" if gtin is not None else f"fdc:{fdc_id:020d}"
    return (
        dedupe_key,
        winner_key,
        _sort_key(name),
        _sort_key(brand),
        fdc_id,
        name,
        brand,
        gtin,
        serving_label,
        summaries["calories_kcal"],
        summaries["protein_g"],
        summaries["carbs_g"],
        summaries["fat_g"],
        summaries["fiber_g"],
        payload,
    )


@contextlib.contextmanager
def _open_source(path: Path) -> Iterator[tuple[BinaryIO, str | None]]:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            members = sorted(
                (item for item in archive.infolist() if not item.is_dir() and item.filename.lower().endswith(".json")),
                key=lambda item: item.filename,
            )
            if len(members) != 1:
                raise CatalogBuildError(
                    f"Expected exactly one JSON member in {path.name}; found {len(members)}"
                )
            if members[0].flag_bits & 0x1:
                raise CatalogBuildError("Encrypted ZIP members are not supported")
            with archive.open(members[0], "r") as source:
                yield source, members[0].filename
    else:
        with path.open("rb") as source:
            yield source, None


def _infer_release(path: Path, member: str | None, explicit: str | None) -> str:
    candidate = explicit
    if candidate is None:
        for value in (path.name, member or ""):
            match = _RELEASE.search(value)
            if match is not None:
                candidate = match.group(1)
                break
    if candidate is None:
        raise CatalogBuildError(
            "The USDA release date is not in the input name; pass --release YYYY-MM-DD"
        )
    try:
        parsed = dt.date.fromisoformat(candidate)
    except ValueError as error:
        raise CatalogBuildError("--release must be a real date in YYYY-MM-DD form") from error
    if parsed.isoformat() != candidate:
        raise CatalogBuildError("--release must use YYYY-MM-DD form")
    return candidate


def _configure_database(database: sqlite3.Connection) -> None:
    database.execute("PRAGMA page_size = 4096")
    database.execute("PRAGMA encoding = 'UTF-8'")
    database.execute("PRAGMA auto_vacuum = NONE")
    database.execute("PRAGMA journal_mode = OFF")
    database.execute("PRAGMA synchronous = OFF")
    database.execute("PRAGMA temp_store = FILE")
    database.execute("PRAGMA application_id = 1431520321")  # ASCII "USDA"
    database.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")


def _create_staging(database: sqlite3.Connection) -> None:
    database.executescript(
        """
        CREATE TABLE staging_food (
            dedupe_key TEXT PRIMARY KEY,
            winner_key TEXT NOT NULL,
            sort_name TEXT NOT NULL,
            sort_brand TEXT NOT NULL,
            fdc_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            brand TEXT,
            gtin TEXT,
            serving_label TEXT NOT NULL,
            calories_kcal REAL,
            protein_g REAL,
            carbs_g REAL,
            fat_g REAL,
            fiber_g REAL,
            payload TEXT NOT NULL
        ) WITHOUT ROWID;
        """
    )


_STAGING_UPSERT = """
    INSERT INTO staging_food (
        dedupe_key, winner_key, sort_name, sort_brand, fdc_id, name, brand, gtin,
        serving_label, calories_kcal, protein_g, carbs_g, fat_g, fiber_g, payload
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(dedupe_key) DO UPDATE SET
        winner_key=excluded.winner_key,
        sort_name=excluded.sort_name,
        sort_brand=excluded.sort_brand,
        fdc_id=excluded.fdc_id,
        name=excluded.name,
        brand=excluded.brand,
        gtin=excluded.gtin,
        serving_label=excluded.serving_label,
        calories_kcal=excluded.calories_kcal,
        protein_g=excluded.protein_g,
        carbs_g=excluded.carbs_g,
        fat_g=excluded.fat_g,
        fiber_g=excluded.fiber_g,
        payload=excluded.payload
    WHERE excluded.winner_key > staging_food.winner_key
"""


_ORDERED_STAGING = """
    SELECT fdc_id, name, brand, gtin, serving_label,
           calories_kcal, protein_g, carbs_g, fat_g, fiber_g, payload
    FROM staging_food
    ORDER BY sort_name, sort_brand, gtin, fdc_id
"""


def _create_catalog_schema(database: sqlite3.Connection) -> None:
    database.executescript(
        """
        CREATE TABLE catalog_metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE catalog_food (
            rowid INTEGER PRIMARY KEY,
            fdc_id INTEGER NOT NULL UNIQUE,
            name TEXT NOT NULL,
            brand TEXT,
            gtin TEXT,
            serving_label TEXT NOT NULL,
            calories_kcal REAL,
            protein_g REAL,
            carbs_g REAL,
            fat_g REAL,
            fiber_g REAL,
            block_id INTEGER NOT NULL,
            record_offset INTEGER NOT NULL,
            record_length INTEGER NOT NULL
        );

        CREATE TABLE catalog_gtin (
            gtin TEXT PRIMARY KEY,
            food_rowid INTEGER NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE catalog_payload_block (
            block_id INTEGER PRIMARY KEY,
            codec TEXT NOT NULL CHECK (codec = 'zlib'),
            uncompressed_size INTEGER NOT NULL CHECK (uncompressed_size > 0),
            record_count INTEGER NOT NULL CHECK (record_count > 0),
            sha256 TEXT NOT NULL,
            payload BLOB NOT NULL
        );

        CREATE VIRTUAL TABLE catalog_food_fts USING fts4(
            name,
            brand,
            content='catalog_food',
            tokenize=unicode61
        );
        """
    )


def _insert_metadata(
    database: sqlite3.Connection,
    *,
    release: str,
    source_url: str,
    pack_id: str,
    logical_sha256: str,
    record_count: int,
) -> None:
    values = {
        "attribution": ATTRIBUTION,
        "license": LICENSE,
        "logical_records_sha256": logical_sha256,
        "pack_id": pack_id,
        "record_count": str(record_count),
        "release_date": release,
        "release_id": f"FoodData_Central_branded_food_json_{release}",
        "schema_version": str(SCHEMA_VERSION),
        "source_url": source_url,
    }
    database.executemany(
        "INSERT INTO catalog_metadata(key, value) VALUES (?, ?)",
        sorted(values.items()),
    )


def _write_blocks(
    database: sqlite3.Connection,
    block_target_bytes: int,
) -> tuple[str, int, int, int, int]:
    logical_digest = hashlib.sha256()
    record_count = 0
    largest_record = 0
    block_id = 0
    next_rowid = 1
    largest_block = 0
    block_payload = bytearray()
    block_rows: list[tuple[Any, ...]] = []

    def flush() -> None:
        nonlocal block_id, next_rowid, largest_block, block_payload, block_rows
        if not block_rows:
            return
        block_id += 1
        uncompressed = bytes(block_payload)
        largest_block = max(largest_block, len(uncompressed))
        database.execute(
            """
            INSERT INTO catalog_payload_block(
                block_id, codec, uncompressed_size, record_count, sha256, payload
            ) VALUES (?, 'zlib', ?, ?, ?, ?)
            """,
            (
                block_id,
                len(uncompressed),
                len(block_rows),
                hashlib.sha256(uncompressed).hexdigest(),
                sqlite3.Binary(zlib.compress(uncompressed, level=9)),
            ),
        )
        for row, offset, length in block_rows:
            fdc_id, name, brand, gtin, serving_label = row[:5]
            summaries = row[5:10]
            database.execute(
                """
                INSERT INTO catalog_food(
                    rowid, fdc_id, name, brand, gtin, serving_label,
                    calories_kcal, protein_g, carbs_g, fat_g, fiber_g,
                    block_id, record_offset, record_length
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    next_rowid,
                    fdc_id,
                    name,
                    brand,
                    gtin,
                    serving_label,
                    *summaries,
                    block_id,
                    offset,
                    length,
                ),
            )
            if gtin is not None:
                database.execute(
                    "INSERT INTO catalog_gtin(gtin, food_rowid) VALUES (?, ?)",
                    (gtin, next_rowid),
                )
            next_rowid += 1
        block_payload = bytearray()
        block_rows = []

    for row in database.execute(_ORDERED_STAGING):
        encoded = row[-1].encode("utf-8")
        logical_digest.update(encoded)
        logical_digest.update(b"\n")
        record_count += 1
        largest_record = max(largest_record, len(encoded))
        if block_rows and len(block_payload) + len(encoded) > block_target_bytes:
            flush()
        offset = len(block_payload)
        block_payload.extend(encoded)
        block_rows.append((row, offset, len(encoded)))
        # A source record larger than the target is complete and alone.  It is
        # never rejected or split because locators address one JSON value.
        if len(encoded) > block_target_bytes:
            flush()
    flush()
    return (
        logical_digest.hexdigest(),
        record_count,
        largest_record,
        block_id,
        largest_block,
    )


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_manifest(manifest: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(
            manifest,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def build_catalog(
    input_path: Path,
    output_path: Path,
    manifest_path: Path,
    *,
    release: str | None = None,
    source_url: str | None = None,
    block_target_bytes: int = DEFAULT_BLOCK_TARGET_BYTES,
) -> dict[str, Any]:
    input_path = Path(input_path)
    output_path = Path(output_path)
    manifest_path = Path(manifest_path)
    if not input_path.is_file():
        raise CatalogBuildError(f"Input does not exist or is not a file: {input_path}")
    if block_target_bytes <= 0:
        raise CatalogBuildError("--block-target-bytes must be positive")
    resolved = {input_path.resolve(), output_path.resolve(), manifest_path.resolve()}
    if len(resolved) != 3:
        raise CatalogBuildError("Input, database output, and manifest must be different files")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    database_temporary = output_path.with_name(f".{output_path.name}.tmp")
    manifest_temporary = manifest_path.with_name(f".{manifest_path.name}.tmp")
    for temporary in (database_temporary, manifest_temporary):
        if temporary.exists():
            temporary.unlink()

    input_records = 0
    skipped_records = 0
    database: sqlite3.Connection | None = None
    try:
        database = sqlite3.connect(database_temporary)
        _configure_database(database)
        _create_staging(database)
        with _open_source(input_path) as (source, member):
            resolved_release = _infer_release(input_path, member, release)
            resolved_source_url = source_url or DOWNLOAD_URL.format(release=resolved_release)
            with database:
                for record in iter_branded_foods(source):
                    input_records += 1
                    row = _normalized_row(record)
                    if row is None:
                        skipped_records += 1
                        continue
                    database.execute(_STAGING_UPSERT, row)

        # The block-writing pass also computes the logical digest in this
        # order. Indexing compact sort fields prevents SQLite from
        # materializing multi-gigabyte native payloads in an external sorter.
        with database:
            database.execute(
                """
                CREATE INDEX staging_food_order_idx
                ON staging_food(sort_name, sort_brand, gtin, fdc_id)
                """
            )
        with database:
            _create_catalog_schema(database)
            (
                logical_sha256,
                record_count,
                largest_record,
                block_count,
                largest_block,
            ) = _write_blocks(database, block_target_bytes)
            if record_count == 0:
                raise CatalogBuildError("The source contains no indexable branded foods")
            pack_id = f"usda-branded-{resolved_release}-{logical_sha256[:16]}"
            _insert_metadata(
                database,
                release=resolved_release,
                source_url=resolved_source_url,
                pack_id=pack_id,
                logical_sha256=logical_sha256,
                record_count=record_count,
            )
            database.execute(
                """
                INSERT INTO catalog_food_fts(docid, name, brand)
                SELECT rowid, name, COALESCE(brand, '') FROM catalog_food ORDER BY rowid
                """
            )
            database.execute("DROP TABLE staging_food")

        quick_check = database.execute("PRAGMA quick_check(1)").fetchone()
        if quick_check != ("ok",):
            raise CatalogBuildError(f"Generated SQLite quick_check failed: {quick_check!r}")
        database.execute("VACUUM")
        database.close()
        database = None

        database_sha256 = _sha256_file(database_temporary)
        manifest: dict[str, Any] = {
            "attribution": ATTRIBUTION,
            "block_count": block_count,
            "block_target_bytes": block_target_bytes,
            "database_entry": DATABASE_ENTRY,
            "database_sha256": database_sha256,
            "database_size_bytes": database_temporary.stat().st_size,
            "deduplicated_record_count": input_records - skipped_records - record_count,
            "generator_sha256": _sha256_file(Path(__file__).resolve()),
            "generator_version": GENERATOR_VERSION,
            "input_record_count": input_records,
            "largest_block_bytes": largest_block,
            "largest_record_bytes": largest_record,
            "license": LICENSE,
            "logical_records_sha256": logical_sha256,
            "pack_id": pack_id,
            "record_count": record_count,
            "release_date": resolved_release,
            "release_id": f"FoodData_Central_branded_food_json_{resolved_release}",
            "schema_version": SCHEMA_VERSION,
            "skipped_record_count": skipped_records,
            "source_url": resolved_source_url,
            "toolchain": {
                "python": platform.python_version(),
                "sqlite": sqlite3.sqlite_version,
                "zlib": zlib.ZLIB_RUNTIME_VERSION,
            },
        }
        with manifest_temporary.open("wb") as output:
            output.write(_canonical_manifest(manifest))
            output.flush()
            os.fsync(output.fileno())
        os.replace(database_temporary, output_path)
        os.replace(manifest_temporary, manifest_path)
        return manifest
    except (OSError, sqlite3.Error, zipfile.BadZipFile, UnicodeError) as error:
        raise CatalogBuildError(str(error)) from error
    finally:
        if database is not None:
            database.close()
        for temporary in (database_temporary, manifest_temporary):
            if temporary.exists():
                temporary.unlink()


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="USDA branded-food JSON or JSON ZIP")
    parser.add_argument("--output", required=True, type=Path, help="output SQLite path")
    parser.add_argument("--manifest", required=True, type=Path, help="output canonical JSON manifest")
    parser.add_argument("--release", help="USDA release date (inferred from official filenames)")
    parser.add_argument("--source-url", help="override the official source URL stored as provenance")
    parser.add_argument(
        "--block-target-bytes",
        type=int,
        default=DEFAULT_BLOCK_TARGET_BYTES,
        help=f"target uncompressed block size (default: {DEFAULT_BLOCK_TARGET_BYTES})",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        manifest = build_catalog(
            args.input,
            args.output,
            args.manifest,
            release=args.release,
            source_url=args.source_url,
            block_target_bytes=args.block_target_bytes,
        )
    except CatalogBuildError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(
        f"Built {manifest['record_count']} foods in {manifest['block_count']} blocks: "
        f"{args.output} ({manifest['database_sha256']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
