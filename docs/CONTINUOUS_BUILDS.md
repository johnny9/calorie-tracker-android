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

Trusted builds select a previously published USDA catalog with the repository Actions variable `USDA_CATALOG_RELEASE_TAG`. It must name an exact immutable tag such as `usda-catalog-v2026-04-30-r1`; the workflow never resolves an implicit latest catalog. It verifies the catalog JAR signature and certificate, embeds the exact asset, and checks the embedded bytes after assembling the APK. See [USDA_CATALOG.md](USDA_CATALOG.md) for the manual update procedure.

The optional repository Actions variable `OPEN_FOOD_FACTS_CONTACT_EMAIL` enables the online fallback after the integration is registered with Open Food Facts. This is provider contact information, not a secret: it is compiled into the APK and sent in the food-lookup User-Agent. A blank value leaves online lookup disabled and does not block an offline USDA-enabled signed build.

## Version history rewrites

Do not force-push `main`. If history must be rewritten, first raise `version-code-base.txt` high enough that `base + commit count` is greater than every APK ever distributed.

## Google Play later

If Play-installed and direct continuous APKs must update one another, enroll in Play App Signing by providing this existing app-signing key, then use a separate upload key. If Google generates a different Play app-signing key, the two channels will not be mutually updateable.

The USDA catalog has the same identity constraint: its JAR entries are signed
and the app requires that signer to match its own installed APK signer. For a
Play-distributed build, sign the catalog with the final Play app-signing key;
the upload key alone will not match after Play re-signs the APK.
