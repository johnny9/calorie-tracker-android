# Third-party food data

Calorie Tracker keeps provider records source-labelled. It does not merge or average conflicting nutrition labels, and historical diary and recipe snapshots do not change when a source changes.

## USDA FoodData Central

Trusted releases can include a signed, replaceable offline catalog generated from USDA FoodData Central's dated Branded Foods JSON download. The name/brand/GTIN search index and serving summaries remain directly queryable; each indexed product retains its complete native USDA record in an independently compressed block that is read only when the result is selected. When multiple source rows share a normalized GTIN, the newest record is indexed and older duplicates are omitted. The pack remains separate from user data, and selected foods retain their FDC ID and source metadata when cached.

The small fallback starter foods contain rounded values derived from standard-serving references. FoodData Central data are public domain in the United States and are published under CC0. Attribution: U.S. Department of Agriculture, Agricultural Research Service, [FoodData Central](https://fdc.nal.usda.gov/).

The Android application does not call the USDA API because USDA requires an API key that must not be exposed publicly. Catalogs are generated off-device from USDA's bulk download, signed with the application's permanent signing identity, and selected by an exact immutable release tag. Branded Foods records are manufacturer or industry label submissions standardized by USDA, not a claim that USDA laboratory-tested each product.

## Manufacturer labels

The bundled Chomps Original Beef Stick uses the nutrition facts published by Chomps for a 33 g stick: 100 kcal, 10 g protein, 0 g carbohydrate, 7 g fat, and 0 g fiber. Source: [Chomps Original Beef](https://chomps.com/collections/chomps/products/gluten-free-snack-beef-jerky-stick-original). Product formulations and labels can change; verify the physical package.

## Open Food Facts

Optional packaged-food searches and selected product reads are sent directly to [Open Food Facts](https://world.openfoodfacts.org/). Search results are transient. A result is stored only after the user selects **Save offline** and the app verifies a complete, finite calorie/protein/carbohydrate/fat/fiber vector on one normalized serving basis.

Distributed builds must identify the application with a monitored developer contact email and must be registered through the Open Food Facts API usage form. The contact is part of the User-Agent and is not an application secret.

The Open Food Facts database is available under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/), and individual database contents are available under the Database Contents License. Attribution and share-alike conditions apply. Open Food Facts data are community-contributed and may be incomplete or wrong; verify the package label. This application does not download or display Open Food Facts product images.

CSV ZIP exports retain each food's provider, provider ID, provider revision and update time when available, retrieval time, quality classification, source URL, and a `data_sources.csv` attribution table.
