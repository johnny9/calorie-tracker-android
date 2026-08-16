# Privacy

Calorie Tracker is designed to work without an account or application server.

- Foods, diary entries, body inputs, targets, activity aggregates, recipes, and fasts are stored in the app's private on-device database.
- The app requests read-only access to active calories from Health Connect only when the user enables the integration and grants permission.
- Health Connect records are not sold, shared, used for advertising, or written back to Health Connect.
- There are no analytics or advertising SDKs.
- Android cloud backup and device-transfer backup are disabled for the app database. The user can explicitly create a local CSV ZIP export.
- The bundled starter food catalog is local. This release performs no online food lookup.

An export can contain sensitive dietary and health information. The person creating it is responsible for storing and sharing it safely.
