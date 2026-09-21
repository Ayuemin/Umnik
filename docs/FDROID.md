# F-Droid packaging

Umnik is prepared for submission to the official F-Droid repository.

## Upstream metadata

Localized store metadata is kept under:

- `fastlane/metadata/android/en-US/`
- `fastlane/metadata/android/ru-RU/`

A draft for the initial `fdroiddata` merge request is kept at
`docs/fdroid/com.ayuemin.umnik.yml`.

## Network anti-features

Umnik is an OpenRouter client and currently requires that service. The proposed metadata declares:

- `NonFreeNet` because the app depends on a proprietary network service;
- `TetheredNet` because the required service is not replaceable through a user-selectable alternative endpoint.

These labels describe the network dependency and do not by themselves prevent inclusion in F-Droid.

## Build

The F-Droid recipe builds the tagged upstream source with the normal Gradle release task. No signing secrets are required:

```bash
./gradlew :app:assembleRelease
```

Without `UMNIK_KEYSTORE_*` variables, the release APK remains unsigned and can be signed by F-Droid.

Android CI also builds this unsigned release variant so F-Droid-style build breakage is detected before releases.

## Updates

Stable releases use tags in the form `vX.Y.Z`. Version information is stored in `app/build.gradle.kts`. The proposed metadata uses tag-based update detection and accepts only stable numeric tags.

## Upstream-signed reproducible builds

The initial recipe intentionally does not declare `Binaries` or `AllowedAPKSigningKeys`. Those fields should only be enabled after the F-Droid build is verified against the unsigned content of an upstream APK.

Until reproducibility is verified, an official F-Droid package would use the F-Droid signing key and would not be directly interchangeable with the GitHub-signed APK.
