# Continuous APK identity

Android will update an installed APK only when all three remain true:

1. The application ID is `com.johnny9.calorietracker`.
2. The APK is signed by the certificate recorded in `signing-cert-sha256.txt`.
3. The new `versionCode` is higher than the installed build.

The `main` workflow preserves those invariants. It calculates `versionCode` as the value in `version-code-base.txt` plus the commit count, signs with the single key held by the `continuous-signing` GitHub Environment, checks the certificate fingerprint, uploads an immutable per-run artifact, and replaces the assets on the `continuous` prerelease.

## Key handling

- The private keystore and passwords must never be committed.
- `.secrets/` is ignored and contains the local recovery copy after running `scripts/generate-continuous-signing-key.sh`.
- Keep at least two encrypted backups in separate places. GitHub Actions secrets cannot be downloaded later.
- A replacement key cannot update existing direct-install APKs. Losing it means choosing a new application ID or requiring every user to uninstall first.
- Pull-request jobs do not receive this key. They build a `.debug` application ID with the ordinary debug certificate.

Run `scripts/upload-continuous-signing-secrets.sh` after authenticating `gh` and creating the GitHub repository. The workflow intentionally fails instead of silently falling back to a debug key when signing secrets are absent.

## Version history rewrites

Do not force-push `main`. If history must be rewritten, first raise `version-code-base.txt` high enough that `base + commit count` is greater than every APK ever distributed.

## Google Play later

If Play-installed and direct continuous APKs must update one another, enroll in Play App Signing by providing this existing app-signing key, then use a separate upload key. If Google generates a different Play app-signing key, the two channels will not be mutually updateable.
