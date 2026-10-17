# Eyecad output format

Each scan produces two files with the same name: `scan-YYYYMMDD-HHMMSS.geojson` and `.obj`.

## GeoJSON

An [RFC 7946](https://datatracker.ietf.org/doc/html/rfc7946) `FeatureCollection`. Every feature is a footprint `Polygon` (counter-clockwise, closed). Each position has three values: longitude, latitude and the element's **base elevation in meters above the floor**.

Two properties make every feature extrudable into 3D, following the MapLibre/Mapbox `fill-extrusion` convention:

| Property | Meaning |
|---|---|
| `base_height` | Bottom of the element, meters above the floor |
| `height` | Top of the element, meters above the floor |

### Feature kinds

| `kind` | Extra properties |
|---|---|
| `room` | `area_m2`, `ceiling_height_m`, `ceiling_estimated` (true when the ceiling wasn't measured and 2.5 m is assumed) |
| `wall` | `length_m`, `thickness_m`, `confidence` |
| `door`, `window`, `opening` | `width_m`, `height_m`, `confidence` |
| `object` | `category`, `width_m`, `depth_m`, `height_m`, `confidence`, `observations` (how many feature points support it) |

`confidence` is 0–1. For LiDAR scans it is RoomPlan's high/medium/low (0.9/0.6/0.3); for camera objects it grows with how many feature points and surfaces support the box, and for camera walls with how much of the wall was actually seen (0.2 means that side was inferred to close the room).

### Object categories

Camera scans don't recognise objects, so their `category` is always `object`: a box for anything standing in the room that isn't floor or wall.

LiDAR scans keep RoomPlan's category names (`storage`, `refrigerator`, `stove`, `bed`, `sink`, `washer_dryer`, `toilet`, `bathtub`, `oven`, `dishwasher`, `table`, `sofa`, `chair`, `fireplace`, `television`, `stairs`), since RoomPlan provides them for free.

### Top-level `eyecad` member

```json
"eyecad": {
  "version": "1.0.0",
  "capture_mode": "lidar-roomplan | camera-arkit | camera-arcore",
  "device": "Apple iPhone15,3",
  "created": "2026-10-17T15:00:00Z",
  "units": "meters",
  "elevation_reference": "floor",
  "crs": "EPSG:4326",
  "origin": { "latitude": 43.263, "longitude": -2.935, "altitude": 20 },
  "rotation_deg": 92,
  "counts": { "walls": 4, "openings": 2, "objects": 3 }
}
```

- `origin` is the GPS position where the AR session started, `rotation_deg` the true bearing of the scan's local forward axis. Indoor GPS and compass readings can be off by several meters and degrees: the room's internal geometry is accurate, its placement on the globe is approximate.
- When no location fix was available, `crs` is `"local"` and positions are `[x, y, z]` meters in the scan's own frame (y forward, z up from the floor).

## OBJ

Wavefront OBJ, geometry only (no `.mtl`, no colours). Units are meters, **+Y up**, floor at Y = 0, in the AR session's local frame. Groups:

- `floor`: the floor outline, triangulated.
- `wall_N`: wall boxes.
- `door_N`, `window_N`, `opening_N`: thin boxes through the wall (walls are not cut).
- `object_N_<category>`: oriented bounding box of each object, e.g. `object_2_sofa`.

Indices match the GeoJSON feature ids (`wall-0` ↔ `wall_0`, `object-2` ↔ `object_2_sofa`).
