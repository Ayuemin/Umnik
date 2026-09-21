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

Umnik already publishes GitHub release APKs signed with a permanent project key. The preferred long-term setup is an F-Droid reproducible build using the upstream signed APK.

Before enabling `Binaries` and `AllowedAPKSigningKeys` in the official fdroiddata recipe, verify the current release certificate SHA-256 directly from a published release asset and verify that an unsigned F-Droid build reproduces the upstream APK byte-for-byte after signature normalization.

Until that verification is complete, the candidate recipe intentionally does not claim reproducible-build compatibility.

## Remaining submission work

2. Optionally add at least one real screenshot or feature graphic to the upstream Fastlane metadata so the app can qualify for F-Droid's Latest tab. The launcher icon is already included in the APK.
2. Run `fdroid readmeta`, `fdroid lint` and `fdroid build` against the pinned v1.19.9 release commit.
4. If reproducibility succeeds, add `Binaries` and `AllowedAPKSigningKeys`.
5. Submit the final metadata as a merge request to `fdroid/fdroiddata`.
