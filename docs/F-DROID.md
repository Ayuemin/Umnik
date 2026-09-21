# F-Droid packaging notes

Umnik is being prepared for submission to the official F-Droid repository.

## Application identity

- Application ID: `com.ayuemin.umnik`
- License: `GPL-3.0-or-later`
- Minimum Android version: API 26
- Prepared release: `1.19.9`
- versionCode: `142`
- release commit: `0f573321f89da7dd5ef74e53bce1913b144fda99`

## Network model

Umnik is an OpenRouter client. The user supplies their own OpenRouter API key.

The proposed F-Droid metadata declares `NonFreeNet`, because OpenRouter is a proprietary network service.

This matches the treatment of existing OpenRouter clients in the official F-Droid catalog. It is an informational anti-feature label, not a bundled proprietary SDK.

## Build

Without the private signing environment variables, the release variant remains unsigned and can be built by an external build system:

```bash
./gradlew --no-daemon :app:assembleRelease
```

Android CI also builds this unsigned release variant so the F-Droid build path is continuously checked.

The current dependencies are resolved from the standard repositories declared in `settings.gradle.kts`. The app does not include Firebase, Google Play Services, advertising SDKs or analytics SDKs.

## Upstream metadata

Store listing text is kept in:

```
fastlane/metadata/android/en-US/
fastlane/metadata/android/ru-RU/
```

A candidate fdroiddata recipe is kept in:

```
docs/fdroiddata/com.ayuemin.umnik.yml
```

This file is a submission aid. The actual official copy must be added to the F-Droid `fdroiddata` repository.

## Reproducible build

Umnik v1.19.9 has been verified reproducible against the published upstream APK.

The verification rebuilt the exact `v1.19.9` tag without signing secrets and compared the resulting unsigned APK with `Umnik-v1.19.9.apk` using `apksigcopier 1.1.1`. Signature copying and APK verification succeeded.

Reference values:

- release commit: `0f573321f89da7dd5ef74e53bce1913b144fda99`
- upstream APK SHA-256: `f66a3cbc2ee54271941946f6aed0726df8825e40b34181a2d85aa4590c7ed510`
- signing certificate SHA-256: `bc0c8bfe9031c29fc3148fdc52c1cb84d897682b83af73e37f3af670f047d79e`

The candidate recipe therefore uses `Binaries` and `AllowedAPKSigningKeys`, allowing F-Droid to publish the upstream developer-signed APK only when its own source build verifies against it.

## Remaining submission work

1. Optionally add at least one real screenshot or feature graphic to the upstream Fastlane metadata. The launcher icon is already included in the APK.
2. Submit `docs/fdroiddata/com.ayuemin.umnik.yml` as `metadata/com.ayuemin.umnik.yml` in a merge request to `fdroid/fdroiddata`.
3. Follow the F-Droid GitLab CI and packager review; apply any requested metadata-only adjustments upstream as well.
