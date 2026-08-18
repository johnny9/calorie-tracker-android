# Privacy

Calorie Tracker is designed to work without an account or application server.

- Foods, diary entries, body inputs, targets, activity aggregates, recipes, and fasts are stored in the app's private on-device database.
- The app requests read-only access to active calories and basal metabolic rate from Health Connect only when the user enables the integration and grants permission.
- Health Connect records are not sold, shared, used for advertising, or written back to Health Connect.
- There are no analytics or advertising SDKs.
- Android cloud backup and device-transfer backup are disabled for the app database. The user can explicitly create a local CSV ZIP export.
- The bundled starter foods, signed USDA offline catalog, and every packaged food the user saves remain local and usable offline. Searching the USDA catalog sends nothing over the network.
- Online packaged-food search happens only after the user taps **Search Open Food Facts**. The search text, the app's identifying User-Agent (including the developer contact email), the device's IP address, and ordinary connection metadata are sent directly to Open Food Facts. Selecting **Save offline** sends that product's public barcode in a second request so its current nutrition panel can be validated and cached.
- Diary entries, recipes, body measurements, targets, fasting history, Health Connect data, exports, device identifiers, and account credentials are never included in food-lookup requests. The app has no account, analytics, advertising SDK, or application backend.

An export can contain sensitive dietary and health information. The person creating it is responsible for storing and sharing it safely.

Open Food Facts has its own privacy practices and rate limits. See [THIRD_PARTY_DATA.md](THIRD_PARTY_DATA.md) for provider and licensing details.
