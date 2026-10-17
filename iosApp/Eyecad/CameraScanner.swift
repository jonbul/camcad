import ARKit
import CoreML
import SceneKit
import Shared
import SwiftUI
import Vision

/// Camera-only capture for iPhones without LiDAR. ARKit reports planes and camera poses, Vision runs
/// the object detector, and the shared Kotlin CameraScanSession does all the geometry and export.
final class CameraScanner: NSObject, ObservableObject, ARSessionDelegate {
    @Published var message = "Move the phone slowly to start tracking…"
    @Published var stats: ScanStats?

    let sceneView = ARSCNView(frame: .zero)
    private var scan = CameraScanSession(mode: .cameraArkit)
    private let location = LocationProvider()
    private let detectorQueue = DispatchQueue(label: "eyecad.detector")
    private let model: VNCoreMLModel? = CameraScanner.loadModel()
    private var detecting = false
    private var lastDetection: TimeInterval = 0
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
        message = model == nil ? "Scanning (object detection unavailable)" : "Scanning"
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
        return (export, try KotlinBridge.save(export))
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

    // MARK: Frames and detection

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard scanning else { return }
        guard case .normal = frame.camera.trackingState else {
            message = "Move the phone slowly…"
            return
        }
        if frame.timestamp - lastStats > 0.5 {
            lastStats = frame.timestamp
            stats = scan.stats()
        }
        guard let model, !detecting, frame.timestamp - lastDetection > 0.6 else { return }
        detecting = true
        lastDetection = frame.timestamp

        let camera = frame.camera
        let k = camera.intrinsics
        let size = camera.imageResolution
        let cameraFrame = CameraFrame(
            pose: KotlinBridge.pose(camera.transform),
            fx: Double(k[0][0]), fy: Double(k[1][1]), cx: Double(k[2][0]), cy: Double(k[2][1]),
            imageWidth: Double(size.width), imageHeight: Double(size.height)
        )
        let pixelBuffer = frame.capturedImage
        detectorQueue.async { [weak self] in
            let detections = Self.detect(model: model, pixelBuffer: pixelBuffer, sensorSize: size)
            DispatchQueue.main.async {
                guard let self else { return }
                self.detecting = false
                guard self.scanning, !detections.isEmpty else { return }
                _ = self.scan.addDetections(frame: cameraFrame, detections: detections, hitTest: { [weak self] origin, direction in
                    self?.raycast(origin, direction)
                })
            }
        }
    }

    private func raycast(_ o: Vec3, _ d: Vec3) -> Vec3? {
        let query = ARRaycastQuery(
            origin: simd_float3(Float(o.x), Float(o.y), Float(o.z)),
            direction: simd_float3(Float(d.x), Float(d.y), Float(d.z)),
            allowing: .estimatedPlane,
            alignment: .any
        )
        guard let hit = sceneView.session.raycast(query).first else { return nil }
        return KotlinBridge.vec3(hit.worldTransform.columns.3)
    }

    /// Runs the detector on the portrait-upright image and maps boxes back to sensor pixels.
    private static func detect(model: VNCoreMLModel, pixelBuffer: CVPixelBuffer, sensorSize: CGSize) -> [Detection2D] {
        let request = VNCoreMLRequest(model: model)
        request.imageCropAndScaleOption = .scaleFill
        // Portrait: the sensor image must be rotated 90° clockwise to be upright.
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .right)
        do { try handler.perform([request]) } catch { return [] }
        let results = request.results as? [VNRecognizedObjectObservation] ?? []
        return results.compactMap { o in
            guard let label = o.labels.first, label.confidence >= 0.4 else { return nil }
            let b = o.boundingBox // normalized, origin bottom-left
            return Detection2D.Companion.shared.fromUprightBox(
                label: label.identifier,
                confidence: Double(label.confidence),
                left: Double(b.minX), top: Double(1 - b.maxY), right: Double(b.maxX), bottom: Double(1 - b.minY),
                rotationDeg: 90,
                sensorWidth: Double(sensorSize.width),
                sensorHeight: Double(sensorSize.height)
            )
        }
    }

    /// Any compiled Core ML object detector named "YOLOv3Tiny" in the bundle (added at build time).
    private static func loadModel() -> VNCoreMLModel? {
        guard let url = Bundle.main.url(forResource: "YOLOv3Tiny", withExtension: "mlmodelc"),
              let ml = try? MLModel(contentsOf: url) else { return nil }
        return try? VNCoreMLModel(for: ml)
    }
}

struct CameraScanView: UIViewRepresentable {
    let scanner: CameraScanner
    func makeUIView(context: Context) -> ARSCNView { scanner.sceneView }
    func updateUIView(_ view: ARSCNView, context: Context) {}
}
