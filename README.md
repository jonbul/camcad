# Eyecad

Scan a room with your phone and get a **2D/3D GeoJSON map** and a **3D model (OBJ)** that separate the room (floor and walls) from the objects in it. No colours or textures are stored: only geometry.

| Device | How it scans | What it finds |
|---|---|---|
| iPhone / iPad with LiDAR (Pro models) | Apple RoomPlan | Walls, doors, windows, openings, floor outline, furniture boxes, true scale (≈1–2 % error) |
| iPhone without LiDAR | ARKit planes + feature points | Walls, floor, doors/windows (ARKit plane classification), objects |
| Android with ARCore | ARCore planes + feature points | Walls, floor, objects |

Objects are found from geometry, not recognised: anything that stands inside the room away from the walls (feature points, table and seat tops, furniture sides) becomes an object box with its footprint and height. In camera scans the room is squared up into a rectangle from the outer wall lines, so rooms that aren't rectangular come out as their bounding rectangle.

## Project layout

```
shared/       Kotlin Multiplatform core (Android + iOS): wall reconstruction, geometric object
              detection, georeferencing, GeoJSON and OBJ export. All the logic lives here.
androidApp/   Android app in Kotlin: ARCore capture, file saving and sharing, preview.
iosApp/       iOS app: thin Swift layer for RoomPlan / ARKit (these APIs are Swift-only),
              passing raw planes and points to the shared Kotlin core.
samples/      Example exports produced by the tests from synthetic rooms.
docs/         Output format reference.
```

## Getting the installers

Every push and pull request runs **Build** in GitHub Actions and attaches:

- `Eyecad-android` → `Eyecad.apk`, installable on any ARCore phone (enable "install unknown apps").
- `Eyecad-ios` → `Eyecad-unsigned.ipa`, or `Eyecad.ipa` when signing secrets are configured.

Both files are also published on the Releases page: each pull request gets a **pr-N-snapshot** pre-release, every push to `main` refreshes the **latest** pre-release, and pushing a tag like `v1.0.0` creates a permanent release.

### Installing on iPhone

Apple only installs signed apps. Two options:

1. **No Apple developer account:** sideload `Eyecad-unsigned.ipa` with [AltStore](https://altstore.io) or [Sideloadly](https://sideloadly.io), which re-sign it with your free Apple ID (valid 7 days).
2. **Apple developer account:** add these repository secrets and the workflow produces a signed `Eyecad.ipa`:
   - `IOS_CERTIFICATE_P12_BASE64`, `IOS_CERTIFICATE_PASSWORD`: distribution (or development) certificate.
   - `IOS_PROVISIONING_PROFILE_BASE64`: ad-hoc or development profile that includes your devices.
   - `IOS_TEAM_ID`.
   - Optional repository variables: `IOS_BUNDLE_ID` (default `com.eyecad.app`, must match the profile) and `IOS_EXPORT_METHOD` (default `release-testing`, i.e. ad-hoc; use `debugging` for a development profile).

### Android release signing (optional)

Without secrets the APK is signed with a debug key, which installs fine. For a stable signing key add `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

## Building locally

```bash
# Shared core tests (no Android SDK or Xcode needed); also regenerates samples/
./gradlew -Peyecad.skipAndroid :shared:jvmTest

# Android APK (needs the Android SDK)
./gradlew :androidApp:assembleRelease

# iOS (macOS with Xcode 16 and XcodeGen)
./gradlew -Peyecad.skipAndroid :shared:assembleSharedReleaseXCFramework
cd iosApp && xcodegen generate && open Eyecad.xcodeproj
```

## Using the app

1. Open Eyecad and tap **Start**. On LiDAR iPhones pick *LiDAR room scan* (best) or *Camera scan*.
2. Walk slowly along the walls, pointing the camera at each wall, the floor and the furniture.
3. Tap **Finish**. The app asks where to save `scan-<date>.geojson` and `scan-<date>.obj`: the default folder (Documents/Eyecad on Android, Files › On My iPhone › Eyecad › scans on iOS) or a folder you choose, which then becomes the default.
4. **Save**, **Share** and **Preview** stay available until you start a new scan. Preview shows the result as a 2D plan or a 3D view you can turn and zoom; **Open…** previews any saved `.geojson` or `.obj`.

Allow location access so the map is placed on the globe; without it the GeoJSON uses local meters.

Camera scans also save `scan-<date>-capture.json` with the raw AR planes and feature points (no location). Opening it in **Preview** rebuilds the room with the current version of the app, and it's the file to share when a scan comes out wrong.

## Output

See [docs/output-format.md](docs/output-format.md). In short, the GeoJSON is a FeatureCollection of footprint polygons (`room`, `wall`, `door`, `window`, `opening`, `object`) whose `base_height`/`height` properties extrude them into 3D in MapLibre, Mapbox, deck.gl or QGIS. The OBJ has one named group per element, e.g. `object_3_sofa`.
