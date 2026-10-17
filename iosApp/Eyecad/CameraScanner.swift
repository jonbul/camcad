import ARKit
import SceneKit
import Shared
import SwiftUI

/// Camera-only capture for iPhones without LiDAR. ARKit reports planes and feature points, and the
/// shared Kotlin CameraScanSession turns them into walls and objects and exports them.
final class CameraScanner: NSObject, ObservableObject, ARSessionDelegate {
    @Published var message = "Move the phone slowly to start tracking…"
    @Published var stats: ScanStats?

    let sceneView = ARSCNView(frame: .zero)
    private var scan = CameraScanSession(mode: .cameraArkit)
    private let location = LocationProvider()
    private var lastPoints: TimeInterval = 0
    private var lastStats: TimeInterval = 0
    private var scanning = false

    func start() {
        scan = CameraScanSession(mode: .cameraArkit)
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        // World -Z points to true north, so the export needs no compass calibration.
        config.worldAlignment = .gravityAndHeading
        sceneView.session.delegate = self
        sceneView.automaticallyUpdatesLighting = false
        sceneView.debugOptions = [.showFeaturePoints]
        sceneView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        location.start()
        scanning = true
        message = "Scanning"
    }

    func finish() throws -> (ScanExport, [URL]) {
        scanning = false
        sceneView.session.pause()
        location.stop()
        let l = location.location
        let export = scan.export(
            device: KotlinBridge.deviceName,
            createdAt: KotlinBridge.now,
            latitude: KotlinBridge.double(l?.coordinate.latitude),
            longitude: KotlinBridge.double(l?.coordinate.longitude),
            altitude: KotlinBridge.double(l.map { $0.altitude }),
            rotationDeg: KotlinDouble(value: 0)
        )
        return (export, try KotlinBridge.stage(export))
    }

    // MARK: Planes

    func session(_ session: ARSession, didAdd anchors: [ARAnchor]) { update(anchors) }
    func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) { update(anchors) }

    func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
        for a in anchors where a is ARPlaneAnchor { scan.removePlane(id: a.identifier.uuidString) }
    }

    private func update(_ anchors: [ARAnchor]) {
        guard scanning else { return }
        for case let p as ARPlaneAnchor in anchors {
            let kind: PlaneKind
            switch p.classification {
            case .wall: kind = .wall
            case .floor: kind = .floor
            case .ceiling: kind = .ceiling
            case .table: kind = .table
            case .seat: kind = .seat
            case .door: kind = .door
            case .window: kind = .window
            default: kind = p.alignment == .vertical ? .wall : .floor
            }
            let boundary = p.geometry.boundaryVertices.map { KotlinBridge.transform(p.transform, $0) }
            scan.upsertPlane(plane: PlaneObservation(
                id: p.identifier.uuidString,
                kind: kind,
                normal: KotlinBridge.vec3(p.transform.columns.1),
                boundary: boundary
            ))
        }
    }

    // MARK: Frames and feature points

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard scanning else { return }
        guard case .normal = frame.camera.trackingState else {
            if message != "Move the phone slowly…" { message = "Move the phone slowly…" }
            return
        }
        if message != "Scanning" { message = "Scanning" }
        if frame.timestamp - lastPoints > 0.3 {
            lastPoints = frame.timestamp
            if let cloud = frame.rawFeaturePoints {
                for (i, p) in cloud.points.enumerated() {
                    scan.addPoint(id: Int64(bitPattern: cloud.identifiers[i]), x: Double(p.x), y: Double(p.y), z: Double(p.z))
                }
            }
            scan.updateCamera(position: KotlinBridge.vec3(frame.camera.transform.columns.3))
        }
        if frame.timestamp - lastStats > 1.0 {
            lastStats = frame.timestamp
            stats = scan.stats()
        }
    }
}

struct CameraScanView: UIViewRepresentable {
    let scanner: CameraScanner
    func makeUIView(context: Context) -> ARSCNView { scanner.sceneView }
    func updateUIView(_ view: ARSCNView, context: Context) {}
}
