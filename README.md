# Calorie Tracker for Android

An original, offline-first calorie and macro tracker inspired by the useful workflows in products such as Cronometer. It is not affiliated with Cronometer and does not copy its branding or proprietary food database.

This repository currently contains a usable `0.1` vertical slice:

- Search a signed, replaceable USDA FoodData Central branded-food catalog by name, brand, or exact GTIN entirely offline in trusted releases.
- Log bundled, source-labelled common foods and a manufacturer-labelled Chomps Original Beef Stick entirely offline when no catalog pack is installed.
- Explicitly search Open Food Facts for packaged foods, validate a selected serving, and save it for later offline use.
- Create and archive custom foods.
- Combine foods into serving-count recipes.
- Log immutable food/recipe snapshots into breakfast, lunch, dinner, or snacks.
- Configure fixed or Mifflin–St Jeor-derived calories, BMI context, and percent/fixed-gram macros.
- Optionally sync the selected 30-day range of daily active calories from Health Connect without double-counting exercise sessions.
- See daily intake, activity, net calories, target, remaining calories, the latest seven days, and rolling 3–30 day net averages. Unknown or stale activity keeps Net unavailable instead of silently becoming zero.
- Explicitly complete days so early or missing dates are excluded instead of treated as zero.
- Run a simple planned or open-ended fasting timer with local history.
- Export the local database to schema-versioned CSV files in a ZIP through Android's document picker.

The broader researched product specification and staged roadmap are in [ANDROID_APP_PLAN.md](ANDROID_APP_PLAN.md). Cooked-weight recipe yields, reusable meals, barcode scanning, weight trends, scheduled fasts, and wider Health Connect metrics remain future work.

## Install a continuous build

Every successful trusted `main` build updates the [`continuous` prerelease](https://github.com/johnny9/calorie-tracker-android/releases/tag/continuous) with `calorie-tracker-continuous.apk`. It uses one permanent signing identity and a monotonic version code, so a new APK can update the prior continuous installation without clearing local data.

The public repository exposes its release assets without GitHub sign-in. The replaceable USDA catalog is published as its own immutable release artifact and embedded byte-for-byte in trusted APKs. See [docs/CONTINUOUS_BUILDS.md](docs/CONTINUOUS_BUILDS.md) before changing the package ID, signing key, version base, workflow, or Play signing setup.

## Build locally

Requirements: Android SDK 37 and JDK 17.

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and uses application ID `com.johnny9.calorietracker.debug`, so it can coexist with the continuous build.

Open Food Facts requires a monitored developer contact in every API User-Agent. Set `OPEN_FOOD_FACTS_CONTACT_EMAIL` in the build environment to enable that optional online fallback. The same-named GitHub Actions repository variable is intentionally visible in the APK and in provider requests, so it is not a secret. Register the integration through the [Open Food Facts API usage form](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/#before-you-start) before distributing an online-enabled build. The signed USDA catalog does not need an API key or contact address.

The USDA processor, manual update workflow, signed-pack format, and exact release-selection procedure are documented in [docs/USDA_CATALOG.md](docs/USDA_CATALOG.md). Ordinary local/debug builds do not regenerate or download the multi-gigabyte source dataset.

## Technical foundation

- Kotlin 2.4.10 with AGP built-in Kotlin
- Jetpack Compose / Material 3
- Room with exported schema for user data, plus a separate immutable SQLite/FTS4 USDA catalog
- Health Connect client 1.1.0
- API 28 minimum; API 37 compile/target
- Integer milli-units for stored nutrition snapshots

No account, analytics, ads, or application backend is used. Packaged-food searches are direct, explicit requests to Open Food Facts. See [PRIVACY.md](PRIVACY.md) and [THIRD_PARTY_DATA.md](THIRD_PARTY_DATA.md).

## Safety and accuracy

The offline branded catalog preserves USDA FoodData Central records and source IDs, but branded entries are manufacturer or industry label submissions rather than USDA laboratory verification. The small fallback generic values are rounded starter references, the bundled Chomps row comes from the current manufacturer label, and Open Food Facts is community-contributed. Food labels and preparations vary; verify important values and use custom foods when appropriate. BMI and calorie equations are informational estimates, not medical advice.
