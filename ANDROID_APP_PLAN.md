# Offline-First Android Calorie and Macro Tracker

Product specification and implementation plan, researched 2026-08-15.

Implementation note (2026-08-16): the repository begins with a usable `0.1` vertical slice covering the offline diary, custom foods, serving-count recipes, fixed/calculated targets, Health Connect active calories, correct rolling windows, manual fasting, CSV ZIP export, and explicit Open Food Facts packaged-food search with validated offline caching. The rest of the version-1 scope below remains a staged roadmap rather than a claim about the first artifact.

## 1. Product goal

Build an original Android application with the useful core of Cronometer:

- Log well-known foods, calories, and macronutrients quickly.
- Create custom foods, recipes, and reusable meal combinations.
- Set calorie and macro targets using transparent body and activity inputs.
- Import activity from Android Health Connect.
- Show intake, activity, net calories, target remaining, and estimated energy balance without ambiguous terminology.
- Show the last seven days and a configurable rolling window without treating pre-installation or missing days as zero.
- Track fasting periods.
- Export all user-owned data as CSV.
- Keep personal, diary, target, fasting, and health data on the device. Network access is optional and limited to public food lookup/data-pack updates.

This is functional inspiration, not a copy of Cronometer's branding, visual design, text, or proprietary food database.

## 2. Research summary: what Cronometer supports

### Calorie and nutrition tracking

The public product and signed-in web application expose:

- A diary organized into meal groups, with quick-add actions for food, exercise, biometrics, notes, water, and fasts.
- Search across common foods, branded products, restaurant foods, supplements, favorites, custom items, and recipes.
- Visible source labels such as NCCDB, USDA, Cronometer's community database, and Custom Food.
- Food entry through text search and barcode scanning; the public site also advertises photo and voice entry.
- Calories, protein, carbohydrates or net carbohydrates, fat, fiber, water, and a large micronutrient breakdown.
- Separate energy figures for consumed, expenditure, remaining, and net.
- A day-complete marker and the ability to use different target templates on different days.
- Copy/paste, multi-select, repeat items, custom meals, custom recipes, and recipe importing. Some are subscription features.

### Custom foods, recipes, and meals

Cronometer distinguishes three useful concepts:

1. A custom food is nutrition-label data for one item. It includes a name, one or more serving sizes, optional gram weights, notes, barcode/category metadata, a label format, and nutrient values.
2. A recipe aggregates ingredient foods. It supports serving-count or weight-based yield, advanced serving sizes, notes, and cooked weight so water loss does not distort portion nutrition.
3. A meal is a reusable bundle of foods and recipes that are usually eaten together. A meal can be expanded into its individual diary entries.

Cronometer also distinguishes retiring an item from deleting historical data. Our application should improve this further: edits or retirement must never silently recalculate or remove historical diary nutrition.

### Targets and BMI

The signed-in target editor confirms:

- Macro targets can be expressed as percentages, fixed grams, or a ketogenic calculation.
- A calorie target can come from a weight goal or be a fixed custom value.
- Weight-goal rate produces a target and forecast.
- BMR can be calculated or manually overridden.
- Baseline activity is progressively replaced by imported tracker activity.
- Thermic effect of food is optional.
- Activity above baseline can optionally be added to the calorie target.
- Target mode shows remaining target; balance mode shows expenditure versus intake.
- BMI is read-only and derived from height and weight. It is not the energy-target formula.

That last distinction matters. BMI alone is not enough to estimate calorie needs. The proposed app will show BMI as context, while using age, height, weight, an equation coefficient or custom BMR, activity, and the user's selected weight goal to calculate calories.

### Trends and reports

Cronometer provides:

- Daily dashboard charts, including a seven-day energy-history chart.
- Configurable charts over periods such as two weeks.
- A nutrition report that shows daily averages for a chosen period and can include or exclude today, supplements, and selected days.
- Weight and other biometric trends.
- Fasting overlays on charts.

The live daily-average view divides over the chosen calendar period, including dates with no food entries. That behavior is not appropriate for the requested early rolling-window experience. Our application will explicitly distinguish missing, partial, complete, and intentional zero-intake days.

### Health and activity integrations

Cronometer's integrations can import activity and health measures including weight, body fat, blood pressure, heart rate/time series, heart-rate variability, oxygen saturation, respiration, recovery, and sleep. The public Android documentation says Health Connect can import general activity, logged exercise, and multiple biometrics, and can receive exported nutrition and water data from Cronometer.

For our first release, Health Connect will be read-only and narrowly scoped to the requested behavior:

- Active calories burned.
- Total calories burned when available.
- Exercise sessions for labels/duration, without adding their calories a second time.
- Optional weight and height import.

Weight trend, body fat, steps, resting heart rate, and sleep can be added after the calorie/activity pipeline is proven.

### Fasting

Cronometer's fasting feature is subscription-gated. It supports:

- Planned-duration and open-ended fasts.
- Immediate or scheduled starts.
- Repeating schedules.
- Timer counting up or down.
- Dashboard and diary widgets.
- A prompt to stop a fast when food is logged.
- History with name, duration, start, finish, and comments.
- Statistics and fasting overlays on charts.

Our app can offer the core timer offline without a subscription. Fasting must not automatically change calorie or macro targets.

### Export

Cronometer can export diary servings, exercises, biometrics, and notes for a selected period as CSV. Custom foods and recipes also have individual export options. The proposed app should offer one complete, schema-stable export covering every user-owned or imported entity and every catalog row referenced by user history, rather than exposing Room metadata or unrelated seed rows.

## 3. MVP scope

### Required in version 1

- Offline onboarding/profile.
- BMI display.
- Transparent BMR/TDEE and calorie-target calculation plus a fixed-target override.
- Macro targets as either percentages or fixed grams.
- Offline food search from a bundled common-food data pack.
- Optional online lookup for foods not found locally.
- Favorites, recent foods, and portion selection.
- Custom foods.
- Recipes with serving-count and cooked/total-weight portions.
- Reusable meals.
- Daily diary with breakfast, lunch, dinner, snacks, and uncategorized groups.
- Health Connect read integration for activity calories; optional weight/height read.
- Daily consumed, active, net, estimated total burn, target, and remaining values.
- Last-seven-days chart.
- Configurable rolling-window chart and average.
- Manual/scheduled fasting timer and history.
- Complete CSV export through Android's Storage Access Framework.
- Fully functional diary, custom-food, recipe, target, trend, fasting, and export workflows in airplane mode.

### Deliberately deferred

- Cloud account, cloud backup, web client, or cross-device sync.
- Social features, coaching, professional/client accounts, and sharing.
- AI photo logging, voice logging, and recipe extraction from arbitrary sites.
- A complete 90-plus nutrient experience and derived nutrition scores.
- Health Connect writes.
- Direct Garmin/Fitbit/Oura vendor integrations; Android Health Connect is the integration boundary.
- Automatic medical or weight-loss recommendations.

The nutrient schema will be extensible, but the version-1 interface will emphasize energy, protein, total/net carbohydrates, fat, and fiber.

## 4. Core calculations

Use `Double` internally for computation and store canonical values as integer milli-units where practical. Round only for display and CSV output.

### Body and target calculations

```text
BMI = weight_kg / height_m^2

Mifflin-St Jeor BMR:
  coefficient A: 10*weight_kg + 6.25*height_cm - 5*age_years + 5
  coefficient B: 10*weight_kg + 6.25*height_cm - 5*age_years - 161
```

The UI must explain why the selected equation coefficient is requested and allow a custom BMR. BMI is informational; it does not select a calorie target by itself.

Offer two mutually exclusive expenditure modes so activity is never counted twice:

1. **Estimated activity mode**

   ```text
   estimated_expenditure = BMR * activity_factor
   calorie_target = estimated_expenditure + goal_adjustment
   ```

   Imported activity is displayed but does not increase the target.

2. **Measured activity mode** (recommended when Health Connect has reliable data)

   ```text
   estimated_expenditure = prorated_BMR + Health_Connect_active_calories
   calorie_target = estimated_expenditure + goal_adjustment
   ```

   If Health Connect provides total calories burned, use that as `estimated_expenditure`. If current-day data is incomplete, show an `Estimated` badge and use the configured fallback baseline until sync completes.

The weight-goal adjustment is user-selected and fully disclosed. Show warnings for aggressive targets and advise consultation with a qualified professional; do not diagnose or prescribe from BMI.

Macro-ratio conversion:

```text
protein_g = calorie_target * protein_percent / 4
carb_g    = calorie_target * carb_percent / 4
fat_g     = calorie_target * fat_percent / 9
```

Ratios must total 100%. Fixed-gram targets may intentionally differ from the calorie target, but the app should show the calculated macro calories and a mismatch warning.

### Unambiguous energy terminology

For each local calendar day:

```text
intake_kcal       = sum(food entry calorie snapshots)
active_kcal       = Health Connect active-calorie aggregate + manual activity not already represented
net_kcal          = intake_kcal - active_kcal
total_burn_kcal   = Health Connect total-calorie aggregate, otherwise BMR + active_kcal
energy_balance    = intake_kcal - total_burn_kcal
remaining_kcal    = calorie_target - intake_kcal
```

Rules:

- `net_kcal` means food minus active calories. A negative value means recorded activity exceeds recorded food.
- `energy_balance` includes basal energy. A negative value is an estimated deficit; a positive value is an estimated surplus.
- Never label both calculations simply `Net`.
- Do not sum workout calories on top of Health Connect's active-calorie aggregate. Exercise sessions are descriptive unless the user manually logs activity that is not present in Health Connect.
- Show whether each burn number is measured, imported, manual, or estimated.

### Rolling windows and missing data

Every date has a status:

- `MISSING`: no intake observation and no confirmed intentional zero.
- `PARTIAL`: only one required component is known or the Health Connect sync is incomplete.
- `COMPLETE`: the user explicitly marked the day complete, and activity is known or explicitly treated as zero.
- `FASTED_ZERO`: the user explicitly completed a zero-intake day. A fast alone never establishes this state.

For a rolling window of `W` days ending on date `D`:

```text
window_start = max(tracking_start_date, D - (W - 1) days)
eligible = COMPLETE and FASTED_ZERO days in [window_start, D]
n = count(eligible)

rolling_net_average(D) = sum(net_kcal for eligible) / n, when n > 0
rolling_net_average(D) = null, when n == 0
```

Presentation requirements:

- The default rolling window is seven days; allow at least 3-30 days.
- Show `n of W days` beside each current rolling result.
- On the first tracked day, divide by 1, not 7.
- Missing and partial dates are gaps, not zeroes.
- A true zero counts only when the user explicitly completes the day; completing a short or partial-day fast is not enough.
- Provide average and known-total views. Label totals `Known total (n days)` until the full window is available.
- “Last week” means the latest seven local calendar dates including today. Also allow the last completed seven days as a separate filter.
- Use configured home-time-zone day boundaries and test daylight-saving transitions.

## 5. User experience and screens

### Onboarding

- Explain local-only storage and optional online food lookup.
- Collect unit system, date of birth/age, height, current weight, target mode, and macro mode.
- Show BMI as a calculated reference, not a diagnosis.
- Let the user choose estimated or measured-activity mode.
- Defer Health Connect permission until the integration screen or until measured activity is selected.

### Today / Diary

- Date navigation and day-complete control.
- Summary cards: Intake, Active, Net, Total burn, Target, Remaining.
- Macro progress for protein, carbs/net carbs, fat, and fiber.
- Meal groups with add, edit, duplicate, move, and delete entry actions.
- Add sheet: food, recipe/meal, custom food, manual activity, weight, note, water, fast.
- Health Connect sync state and last sync time.
- Snapshot nutrition into every diary entry so later food/recipe edits do not rewrite history.

### Food search

Ranking order:

1. Exact barcode.
2. Exact custom-food/recipe/meal name.
3. Favorites and recent foods.
4. Bundled common foods.
5. Cached online results.
6. Optional live results.

Filters: All, Favorites, Common, Branded, Restaurant, Custom, Recipes/Meals. Every result shows its source and whether nutrition is verified, community-provided, or user-entered.

### Custom food editor

- Name, brand, optional barcode, notes, and source label.
- Primary serving amount/name and optional gram weight.
- Additional serving sizes when a gram conversion exists.
- Calories, protein, total carbohydrates, fiber, sugar, fat, saturated fat, and sodium in the first release.
- Advanced generic nutrient rows supported by the data model.
- Non-negative validation and consistency warnings; warnings do not silently rewrite label values.
- Retire instead of destructive deletion once referenced by diary history.

### Recipe editor

- Name, notes/instructions, ingredient search, quantity, and serving.
- Serving-count or weight-based yield.
- Optional cooked/final recipe weight.
- Live total and per-serving nutrition.
- Copy recipe, retire recipe, and preserve historical diary snapshots.

### Reusable meals

- Bundle foods and recipes frequently eaten together.
- Add the whole meal in one action.
- Allow one-time expansion into individual diary entries.
- A meal has no cooking-yield calculation; that belongs to recipes.

### Targets

- Calculation breakdown showing every input and intermediate result.
- Fixed calories or calculated calories.
- Estimated versus measured activity mode.
- Goal adjustment and optional target date forecast.
- Macro percentages or fixed grams.
- Net versus total carbohydrate preference.
- A later release can add day-of-week target templates.

### Trends

- Last-seven-days bars for Intake, Active, Net, Target, and Energy balance.
- Rolling-window line with selectable window and `n/W` completeness.
- Missing/partial day visualization.
- Weight trend and target line.
- Optional fasting overlay.
- Tap any date to open its diary and see calculation provenance.

### Fasting

- Start now with preset or custom duration.
- Open-ended fast.
- Schedule a future start and optional recurrence.
- Count up or down.
- Pause is not supported; correct start/end times instead.
- End-fast action and optional note.
- Warn when caloric food is added during an active fast; never block logging.
- History and chart overlay.
- Optional local notification near planned completion.
- Fasting has no automatic effect on calorie/macronutrient targets.

### Export

Use Android's Storage Access Framework. An explicit export creates:

`calorie-tracker-export-YYYYMMDD-HHMM.zip`

containing UTF-8, RFC 4180-compatible CSV files:

- `profile.csv`
- `foods.csv`
- `food_servings.csv`
- `food_nutrients.csv`
- `recipes.csv`
- `recipe_ingredients.csv`
- `meals.csv`
- `meal_items.csv`
- `diary_entries.csv`
- `daily_summaries.csv`
- `targets.csv`
- `activity_daily.csv`
- `health_measurements.csv`
- `fasting_periods.csv`
- `settings.csv`

Requirements:

- Stable UUIDs preserve relationships between files.
- Include schema version and units in every relevant file.
- Include UTC instant, local date/time, time-zone ID, and offset where applicable.
- Include food/activity provenance and imported-versus-estimated flags.
- `daily_summaries.csv` includes completeness status and rolling-window denominator.
- Quote embedded commas/newlines and neutralize spreadsheet formula injection in user-entered text.
- Export occurs locally and requires no network or broad storage permission.

## 6. Offline food-data strategy

### Recommended version-1 approach

1. Generate a versioned SQLite/FTS4 pack from USDA FoodData Central's dated CC0 bulk data in a manual release workflow, not during every Android build.
2. Keep name/brand/GTIN indexes and serving summaries directly queryable while storing the complete native source record for each indexed product in independently compressed random-access blocks. Deterministically select the newest record when source rows share a normalized GTIN.
3. Sign and publish the catalog as an immutable replaceable artifact, select it by an exact repository release tag, and embed the same verified bytes in trusted APKs. The small fallback food set keeps debug builds useful when no pack is installed.
4. Copy selected catalog items into/cache them through Room so recipes, diary snapshots, and export remain independent from later catalog replacements.
5. Use an explicit, rate-limited Open Food Facts name search and product-label lookup as an optional fallback, then cache only user-selected results locally. Barcode scanning can reuse the same exact-product path later.
6. Keep all diary, health, target, and fasting data out of food-lookup requests.

Why not embed a USDA API key in the APK: FoodData Central requires an API key and explicitly requires that it remain secret. A future name-search service would therefore need a minimal stateless proxy, or the app should rely on periodically published offline packs. The MVP should prefer data packs so no application backend is required.

Open Food Facts is useful for barcode lookup but is community-contributed, rate-limited, and ODbL-licensed. Preserve source and attribution metadata, cache responsibly, and review redistribution obligations before shipping an offline combined data pack.

### Food source quality tiers

- `REFERENCE`: USDA/common-food reference data.
- `BRANDED_COMMUNITY`: online branded data; show label date/completeness when present.
- `USER_CUSTOM`: values entered by the device owner.
- `RECIPE_DERIVED`: calculated from ingredient versions.

Never present all sources as equally verified.

## 7. Android technical design

### Platform

- Kotlin.
- Jetpack Compose and Material 3 with an original design.
- `minSdk 28` to align with current Health Connect availability.
- Pin current stable compile/target SDK and library versions when implementation begins.
- Room with FTS4 for local food search.
- DataStore for small preferences.
- Health Connect Jetpack client.
- WorkManager for optional background refresh after the user grants background-read permission.
- CameraX plus an on-device barcode decoder as a later version-1 enhancement if schedule permits.

Start as one Gradle application module with packages by feature. Split modules only when build/test boundaries justify it.

### Layering

```text
Compose screens / ViewModels
          |
Use cases and calculation services
          |
Repositories
   |           |             |
 Room      Health Connect   Optional public food lookup
   |
CSV export through Storage Access Framework
```

Core services:

- `NutritionCalculator`
- `TargetCalculator`
- `DailyEnergyCalculator`
- `RollingWindowCalculator`
- `HealthConnectRepository`
- `FoodRepository`
- `DiaryRepository`
- `RecipeRepository`
- `FastingRepository`
- `CsvExportService`

### Essential data model

- `Profile`
- `BodyMeasurement`
- `TargetPlan` with effective date range and calculation-input snapshot
- `NutrientDefinition`
- `Food`
- `FoodServing`
- `FoodNutrient`
- `Recipe`
- `RecipeIngredient`
- `Meal`
- `MealItem`
- `DiaryEntry` with immutable nutrition snapshot
- `ActivityDailyAggregate`
- `HealthSyncState`
- `FastingPeriod`
- `FastingSchedule`

Use soft retirement for referenced foods, recipes, and meals. Hard deletion is allowed only for unreferenced objects. Deleting a diary entry does not delete its source item.

## 8. Health Connect behavior

### Permissions

Request incrementally and explain each feature:

- Read active calories burned.
- Read total calories burned.
- Read exercise sessions.
- Optional read weight and height.
- Optional background read.
- Optional historical read only when the user asks to import older history.

Do not request broad biometric access for the MVP. The core diary works when Health Connect is missing, unavailable, or denied.

### Sync and aggregation

- Sync on app foreground and manual pull-to-refresh.
- Add WorkManager only after background-read permission is granted and the device supports it.
- Use Health Connect's aggregate API for daily calories so priority/deduplication rules are applied.
- Use local-day `Instant` boundaries derived from the configured tracking time zone.
- Cache daily aggregates with source, sync time, and completeness.
- Recompute affected summaries after Health Connect changes or permission restoration.
- Never write imported data back to Health Connect.
- Never add exercise-session calories to an active-calorie aggregate unless the session is a manual app-only entry that is provably absent from Health Connect.
- If permissions are revoked, keep previously cached local summaries until the user chooses to clear them, label them stale, and stop synchronization immediately.

## 9. Privacy and security

- No account required.
- No analytics or advertising SDK in the MVP.
- Personal data remains in the app sandbox.
- Rely on Android file-based encryption and device authentication initially; exclude the live database from unencrypted automatic cloud backup.
- Offer explicit local CSV export and later an encrypted backup format.
- Display a warning that exports contain sensitive health and dietary information.
- Public food lookup sends only the search term or barcode, never diary/health/profile data.
- Publish a clear privacy policy before requesting Health Connect permissions.
- Request only Health Connect data types tied to visible features and complete the Google Play health-data declaration.

## 10. Delivery plan

### Milestone 0: calculation and data-contract spike (2-3 days)

- Freeze terminology and sign conventions.
- Write calculation truth tables for target, net, balance, missing days, and rolling windows.
- Validate a small USDA seed import and Room FTS search.
- Validate Health Connect availability and daily aggregation on a test device/emulator.

Exit: formulas, CSV schemas, and data provenance are approved before UI work.

### Milestone 1: local foundation and food search (1 week)

- Compose shell/navigation, Room schema, migrations, DataStore.
- Seed food pack and source-aware search.
- Portions, favorites, recent foods, and custom-food editor.
- Airplane-mode instrumentation test.

Exit: a user can find or create a food entirely offline.

### Milestone 2: diary, recipes, and meals (1-1.5 weeks)

- Diary groups and date navigation.
- Immutable nutrition snapshots.
- Recipe and reusable-meal builders.
- Day-complete and missing-data states.

Exit: complete daily intake and macro totals survive food/recipe edits.

### Milestone 3: targets and profile (1 week)

- BMI, BMR, estimated expenditure, fixed/calculated targets.
- Macro percentages/fixed grams.
- Transparent breakdown, provenance, and warnings.

Exit: every displayed target is reproducible from visible inputs.

### Milestone 4: Health Connect (1-1.5 weeks)

- Availability, permission, foreground sync, aggregate reads, source status.
- Active/total burn, exercise labels, optional weight/height.
- Permission-denied, revoked, duplicate-source, stale, and partial-day behavior.

Exit: no activity double counting in fixture and device tests.

### Milestone 5: trends and rolling windows (1 week)

- Seven-day chart.
- Rolling average/total with `n/W` completeness.
- Missing/partial/zero-day visualization.
- Weight trend and calculation drill-down.

Exit: the first six days of a seven-day window use denominators 1-6, missing days remain gaps, and day seven uses 7 only when all days are complete.

### Milestone 6: fasting and CSV export (1 week)

- Fast timer, schedule, history, food-entry warning, chart overlay.
- Full relational CSV export ZIP through Storage Access Framework.
- Export parser test verifies row counts, IDs, units, escaping, and relationships.

Exit: a clean install can export all locally created and imported data without network access.

### Milestone 7: release hardening (1 week)

- Accessibility, screen-reader labels, large text, color contrast.
- Database migration and process-death tests.
- Time-zone and daylight-saving tests.
- Health Connect/Play policy work and privacy disclosures.
- Performance with realistic food and multi-year diary data.

Expected effort for one experienced Android developer: roughly 7-9 developer-weeks, excluding extensive food-data curation, brand artwork, and store review time.

## 11. Required automated tests

### Calculation unit tests

- BMI and both Mifflin-St Jeor coefficients.
- Static and measured-activity target modes.
- Macro percentage-to-gram conversion and ratio validation.
- Net versus total-burn balance signs.
- No workout/activity double count.
- First-day and first-week rolling denominators.
- Missing versus explicit zero day.
- Window containing gaps.
- DST-short and DST-long days.

### Nutrition tests

- Portion conversion from grams and household servings.
- Recipe total, per-serving, and cooked-weight yield.
- Editing/retiring a food does not alter past entries.
- Label rounding is preserved; the app does not overwrite label energy from macros.

### Health Connect tests

- Unavailable provider, denied permission, partial permission, and revoked permission.
- Aggregate data from overlapping sources.
- Foreground resync and stale cache.
- No current-day data and late-arriving data.

### Export tests

- Every entity type appears in the ZIP.
- UUID references resolve.
- CSV escaping, UTF-8, newlines, commas, quotes, and formula-injection protection.
- Timestamps, time zones, canonical units, provenance, and schema version.

## 12. Definition of done for version 1

The MVP is complete only when:

- The entire personal workflow works without an account or server.
- Airplane mode supports diary, custom foods, recipes/meals, targets, trends, fasting, and export.
- Optional public food lookup fails gracefully and cached foods remain usable.
- Health Connect is optional, least-privilege, and cannot double-count activity.
- Net calories and total energy balance are visibly distinct.
- Rolling windows never convert missing early days into zeroes or divide by a fixed full-window denominator before data exists.
- Historical diary values do not change after editing source foods or recipes.
- Every local table is represented in the CSV export.
- No test or device behavior is claimed without being run.

## 13. Sources

Cronometer product and support:

- https://cronometer.com/features/track-food.html
- https://cronometer.com/features/custom-diet-tracking.html
- https://cronometer.com/features/track-exercises-and-biometrics.html
- https://cronometer.com/features/sync-devices.html
- https://cronometer.com/features/reports-and-charts.html
- https://support.cronometer.com/hc/en-us/articles/360019866351-Mobile-Create-a-Custom-Food
- https://support.cronometer.com/hc/en-us/articles/28780966141204-Pro-Custom-Recipes
- https://support.cronometer.com/hc/en-us/articles/17687459173908-Create-Custom-Meal
- https://support.cronometer.com/hc/en-us/articles/360036016612-Mobile-Fasting
- https://support.cronometer.com/hc/en-us/articles/22731903751316-Health-Connect
- https://support.cronometer.com/hc/en-us/articles/31974307318420-Energy-Expenditure
- https://support.cronometer.com/hc/en-us/articles/31975503009044-Energy-Target
- https://support.cronometer.com/hc/en-us/articles/31308427612180-Targets-Profile
- https://support.cronometer.com/hc/en-us/articles/360018760151-Account-Settings

Android and food-data implementation references:

- https://developer.android.com/health-and-fitness/health-connect/availability
- https://developer.android.com/health-and-fitness/health-connect/aggregate-data
- https://developer.android.com/health-and-fitness/health-connect/read-data
- https://developer.android.com/health-and-fitness/health-connect/data-types
- https://developer.android.com/health-and-fitness/health-connect/ui/permissions
- https://developer.android.com/health-and-fitness/health-connect/publish
- https://fdc.nal.usda.gov/api-guide/
- https://openfoodfacts.github.io/openfoodfacts-server/api/
- https://openfoodfacts.github.io/openfoodfacts-server/api/tutorials/license-be-on-the-legal-side/

The signed-in Cronometer web application was also inspected read-only on 2026-08-15. No account data was added, edited, exported, connected, disconnected, or deleted.
