# Calorie Tracker for Android

An original, offline-first calorie and macro tracker inspired by the useful workflows in products such as Cronometer. It is not affiliated with Cronometer and does not copy its branding or proprietary food database.

This repository currently contains a usable `0.1` vertical slice:

- Log 20 bundled, source-labelled common foods entirely offline.
- Create and archive custom foods.
- Combine foods into serving-count recipes.
- Log immutable food/recipe snapshots into breakfast, lunch, dinner, or snacks.
- Configure fixed or Mifflin–St Jeor-derived calories, BMI context, and percent/fixed-gram macros.
- Optionally read daily active calories from Health Connect without double-counting exercise sessions.
- See daily intake, activity, net calories, target, remaining calories, the latest seven days, and rolling 3–30 day net averages.
- Explicitly complete days so early or missing dates are excluded instead of treated as zero.
- Run a simple planned or open-ended fasting timer with local history.
- Export the local database to schema-versioned CSV files in a ZIP through Android's document picker.

The broader researched product specification and staged roadmap are in [ANDROID_APP_PLAN.md](ANDROID_APP_PLAN.md). Cooked-weight recipe yields, reusable meals, barcode/online lookup, weight trends, scheduled fasts, and wider Health Connect metrics remain future work.

## Install a continuous build

Every successful trusted `main` build updates the [`continuous` prerelease](https://github.com/johnny9/calorie-tracker-android/releases/tag/continuous) with `calorie-tracker-continuous.apk`. It uses one permanent signing identity and a monotonic version code, so a new APK can update the prior continuous installation without clearing local data.

The repository is initially private, so GitHub sign-in is required to download its release assets. See [docs/CONTINUOUS_BUILDS.md](docs/CONTINUOUS_BUILDS.md) before changing the package ID, signing key, version base, workflow, or Play signing setup.

## Build locally

Requirements: Android SDK 37 and JDK 17.

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and uses application ID `com.johnny9.calorietracker.debug`, so it can coexist with the continuous build.

## Technical foundation

- Kotlin 2.4.10 with AGP built-in Kotlin
- Jetpack Compose / Material 3
- Room with exported schema
- Health Connect client 1.1.0
- API 28 minimum; API 37 compile/target
- Integer milli-units for stored nutrition snapshots

No account, analytics, ads, or backend is used. See [PRIVACY.md](PRIVACY.md).

## Safety and accuracy

The bundled food values are a small, rounded starter reference derived from standard-serving data in USDA FoodData Central. Food labels and preparations vary; verify important values and use custom foods when appropriate. BMI and calorie equations are informational estimates, not medical advice.
