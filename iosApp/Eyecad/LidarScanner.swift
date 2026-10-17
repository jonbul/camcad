import RoomPlan
import Shared
import SwiftUI
import UIKit

/// LiDAR capture with Apple's RoomPlan. Swift is only the glue: RoomPlan's results are Swift-only,
/// so they're handed to the shared Kotlin code as plain values, which builds the plan and exports.
final class LidarScanner: ObservableObject {
    @Published var message = "Point the camera at a wall and walk around the room."
    let controller = LidarCaptureController()
    private let location = LocationProvider()
    private let heading = HeadingCalibrator()
    private var timer: Timer?

    static var isSupported: Bool { RoomCaptureSession.isSupported }

    func start() {
        location.start()
        controller.start()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.sampleHeading() }
    }

    /// Stops capture; RoomPlan then processes the room and calls back with the export files.
    func finish(completion: @escaping (Result<(ScanExport, [URL]), Error>) -> Void) {
        timer?.invalidate()
        message = "Processing the room…"
        controller.onFinished = { [weak self] room, error in
            guard let self else { return }
            self.location.stop()
            if let error { completion(.failure(error)); return }
            guard let room else {
                completion(.failure(NSError(domain: "Eyecad", code: 1, userInfo: [NSLocalizedDescriptionKey: "RoomPlan returned no room."])))
                return
            }
            let l = self.location.location
            let export = ScanExporter.shared.exportRoomPlan(
                input: Self.input(from: room),
                heading: self.heading,
                device: KotlinBridge.deviceName,
                createdAt: KotlinBridge.now,
                latitude: KotlinBridge.double(l?.coordinate.latitude),
                longitude: KotlinBridge.double(l?.coordinate.longitude),
                altitude: KotlinBridge.double(l.map { $0.altitude })
            )
            completion(Result { (export, try KotlinBridge.stage(export)) })
        }
        controller.stop()
    }

    private func sampleHeading() {
        guard let frame = controller.captureView?.captureSession.arSession.currentFrame,
              let bearing = location.bearing else { return }
        let m = frame.camera.transform
        let forward = KotlinBridge.vec3(-m.columns.2)
        heading.addSample(cameraForward: forward, trueBearingDeg: bearing)
    }

    static func input(from room: CapturedRoom) -> RoomPlanInput {
        let input = RoomPlanInput()
        func confidence(_ c: CapturedRoom.Confidence) -> Double {
            switch c {
            case .high: return 0.9
            case .medium: return 0.6
            case .low: return 0.3
            @unknown default: return 0.3
            }
        }
        func add(_ s: CapturedRoom.Surface, _ kind: String) {
            let t = s.transform
            input.addSurface(
                kind: kind,
                center: KotlinBridge.vec3(t.columns.3),
                xAxis: KotlinBridge.vec3(t.columns.0),
                width: Double(s.dimensions.x),
                height: Double(s.dimensions.y),
                confidence: confidence(s.confidence),
                polygon: s.polygonCorners.map { KotlinBridge.transform(t, $0) }
            )
        }
        room.walls.forEach { add($0, "wall") }
        room.doors.forEach { add($0, "door") }
        room.windows.forEach { add($0, "window") }
        room.openings.forEach { add($0, "opening") }
        room.floors.forEach { add($0, "floor") }
        for o in room.objects {
            let t = o.transform
            input.addObject(
                category: RoomPlanConverter.shared.category(roomPlanName: String(describing: o.category)),
                center: KotlinBridge.vec3(t.columns.3),
                xAxis: KotlinBridge.vec3(t.columns.0),
                width: Double(o.dimensions.x),
                height: Double(o.dimensions.y),
                depth: Double(o.dimensions.z),
                confidence: confidence(o.confidence)
            )
        }
        return input
    }
}

final class LidarCaptureController: UIViewController, RoomCaptureViewDelegate {
    private(set) var captureView: RoomCaptureView?
    var onFinished: ((CapturedRoom?, Error?) -> Void)?
    private var pendingStart = false

    init() { super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { super.init(coder: coder) }

    override func viewDidLoad() {
        super.viewDidLoad()
        let v = RoomCaptureView(frame: view.bounds)
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        v.delegate = self
        view.addSubview(v)
        captureView = v
        if pendingStart { start() }
    }

    func start() {
        guard let v = captureView else { pendingStart = true; return }
        pendingStart = false
        v.captureSession.run(configuration: RoomCaptureSession.Configuration())
    }

    func stop() { captureView?.captureSession.stop() }

    func captureView(shouldPresent roomDataForProcessing: CapturedRoomData, error: Error?) -> Bool { true }

    func captureView(didPresent processedResult: CapturedRoom, error: Error?) {
        onFinished?(error == nil ? processedResult : nil, error)
        onFinished = nil
    }
}

struct LidarScanView: UIViewControllerRepresentable {
    let scanner: LidarScanner
    func makeUIViewController(context: Context) -> LidarCaptureController { scanner.controller }
    func updateUIViewController(_ controller: LidarCaptureController, context: Context) {}
}
