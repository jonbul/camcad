import Foundation
import Shared
import simd

/// Small conversions between simd and the shared Kotlin types.
enum KotlinBridge {
    static func vec3(_ v: simd_float4) -> Vec3 {
        Vec3(x: Double(v.x), y: Double(v.y), z: Double(v.z))
    }

    static func vec3(_ v: simd_float3) -> Vec3 {
        Vec3(x: Double(v.x), y: Double(v.y), z: Double(v.z))
    }

    /// World-space point of a local point under a transform.
    static func transform(_ m: simd_float4x4, _ p: simd_float3) -> Vec3 {
        vec3(m * simd_float4(p, 1))
    }

    static func pose(_ m: simd_float4x4) -> Pose {
        Pose(position: vec3(m.columns.3), right: vec3(m.columns.0), up: vec3(m.columns.1), back: vec3(m.columns.2))
    }

    static func double(_ v: Double?) -> KotlinDouble? {
        v.map { KotlinDouble(value: $0) }
    }

    static var deviceName: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        return "Apple \(machine)"
    }

    static var now: String { ISO8601DateFormatter().string(from: Date()) }

    /// Writes the export files to a staging folder, where they stay until the next scan so they can be
    /// saved, shared and previewed any number of times.
    static func stage(_ export: ScanExport) throws -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("scans", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let name = "scan-\(formatter.string(from: Date()))"
        let geo = dir.appendingPathComponent("\(name).geojson")
        let obj = dir.appendingPathComponent("\(name).obj")
        try export.geoJson.write(to: geo, atomically: true, encoding: .utf8)
        try export.obj.write(to: obj, atomically: true, encoding: .utf8)
        guard let capture = export.capture else { return [geo, obj] }
        // Raw planes and points, so the scan can be rebuilt with future versions.
        let raw = dir.appendingPathComponent("\(name)-capture.json")
        try capture.write(to: raw, atomically: true, encoding: .utf8)
        return [geo, obj, raw]
    }
}
