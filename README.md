# Eyecad

Scan a room with your phone and get a **2D/3D GeoJSON map** and a **3D model (OBJ)** with the walls, doors, windows and furniture identified. No colours or textures are stored: only geometry and labels.

| Device | How it scans | What it finds |
|---|---|---|
| iPhone / iPad with LiDAR (Pro models) | Apple RoomPlan | Walls, doors, windows, openings, floor outline, 16 furniture/appliance types, true scale (≈1–2 % error) |
| iPhone without LiDAR | ARKit planes + on-device object detection (YOLOv3-Tiny) | Walls, floor, doors/windows (ARKit plane classification), furniture |
| Android with ARCore | ARCore planes + on-device object detection (MediaPipe EfficientDet-Lite0) | Walls, floor, furniture |

Camera-only scans are less accurate than LiDAR: walls come from detected planes and object depth is estimated from typical sizes.

## Project layout

```
shared/       Kotlin Multiplatform core (Android + iOS): wall reconstruction, object placement and
              tracking, georeferencing, GeoJSON and OBJ export. All the logic lives here.
androidApp/   Android app in Kotlin: ARCore capture, MediaPipe detection, file sharing.
iosApp/       iOS app: thin Swift layer for RoomPlan / ARKit / Vision (these APIs are Swift-only
              or callback-based), passing raw data to the shared Kotlin core.
samples/      Example exports produced by the tests from synthetic rooms.
docs/         Output format reference.
```

## Getting the installers

Every push and pull request runs **Build** in GitHub Actions and attaches:

- `Eyecad-android` → `Eyecad.apk`, installable on any ARCore phone (enable "install unknown apps").
- `Eyecad-ios` → `Eyecad-unsigned.ipa`, or `Eyecad.ipa` when signing secrets are configured.

Every push to `main` also refreshes the **latest** pre-release on the Releases page with both files, and pushing a tag like `v1.0.0` creates a permanent release.

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
curl -L -o iosApp/Eyecad/Models/YOLOv3Tiny.mlmodel \
  https://ml-assets.apple.com/coreml/models/Image/ObjectDetection/YOLOv3Tiny/YOLOv3Tiny.mlmodel
cd iosApp && xcodegen generate && open Eyecad.xcodeproj
```

## Using the app

1. Open Eyecad and tap **Start**. On LiDAR iPhones pick *LiDAR room scan* (best) or *Camera scan*.
2. Walk slowly along the walls, pointing the camera at each wall, the floor and the furniture.
3. Tap **Finish**. The app saves `scan-<date>.geojson` and `scan-<date>.obj` and opens the share sheet.

Allow location access so the map is placed on the globe; without it the GeoJSON uses local meters.

## Output

See [docs/output-format.md](docs/output-format.md). In short, the GeoJSON is a FeatureCollection of footprint polygons (`room`, `wall`, `door`, `window`, `opening`, `object`) whose `base_height`/`height` properties extrude them into 3D in MapLibre, Mapbox, deck.gl or QGIS. The OBJ has one named group per element, e.g. `object_3_sofa`.
