import Shared
import SwiftUI

struct ContentView: View {
    enum Mode: String, CaseIterable, Identifiable {
        case lidar = "LiDAR room scan"
        case camera = "Camera scan"
        var id: String { rawValue }
    }

    enum Phase {
        case idle, scanning, processing
        case done(summary: String, files: [URL])
        case failed(String)
    }

    @State private var mode: Mode = LidarScanner.isSupported ? .lidar : .camera
    @State private var phase: Phase = .idle
    @StateObject private var lidar = LidarScanner()
    @StateObject private var camera = CameraScanner()

    var body: some View {
        switch phase {
        case .idle:
            VStack(spacing: 24) {
                Text("Eyecad").font(.largeTitle.bold())
                Text("Walk around a room to map its walls, doors, windows and furniture. You get a GeoJSON plan and a 3D model.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                if LidarScanner.isSupported {
                    Picker("Mode", selection: $mode) {
                        ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                } else {
                    Text("No LiDAR on this iPhone, so Eyecad uses camera mode.").font(.footnote)
                }
                Button("Start scan") { start() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
            .padding(24)

        case .scanning, .processing:
            ZStack(alignment: .bottom) {
                Group {
                    if mode == .lidar { LidarScanView(scanner: lidar) } else { CameraScanView(scanner: camera) }
                }
                .ignoresSafeArea()
                VStack(spacing: 12) {
                    if mode == .camera {
                        Text(cameraStatus).font(.callout).padding(8).background(.black.opacity(0.6)).foregroundStyle(.white).cornerRadius(8)
                    }
                    if case .processing = phase {
                        ProgressView(lidar.message).tint(.white).foregroundStyle(.white)
                    } else {
                        Button("Finish") { finish() }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                    }
                }
                .padding(.bottom, 40)
            }

        case let .done(summary, files):
            VStack(spacing: 20) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(.green)
                Text(summary).multilineTextAlignment(.center)
                ShareLink(items: files) { Label("Send GeoJSON and 3D model", systemImage: "square.and.arrow.up") }
                    .buttonStyle(.borderedProminent)
                Text("Also saved in Files › On My iPhone › Eyecad › scans").font(.footnote).foregroundStyle(.secondary)
                Button("New scan") { phase = .idle }
            }
            .padding(24)

        case let .failed(message):
            VStack(spacing: 20) {
                Text("Scan failed").font(.title2.bold())
                Text(message).multilineTextAlignment(.center)
                Button("Try again") { phase = .idle }
            }
            .padding(24)
        }
    }

    private var cameraStatus: String {
        guard let s = camera.stats else { return camera.message }
        return "\(camera.message)\nWalls: \(s.walls)  Objects: \(s.objects)  Surfaces: \(s.planes)"
    }

    private func start() {
        phase = .scanning
        if mode == .lidar { lidar.start() } else { camera.start() }
    }

    private func finish() {
        if mode == .lidar {
            phase = .processing
            lidar.finish { result in
                DispatchQueue.main.async { show(result) }
            }
        } else {
            show(Result { try camera.finish() })
        }
    }

    private func show(_ result: Result<(ScanExport, [URL]), Error>) {
        switch result {
        case let .success((export, files)):
            let plan = export.plan
            phase = .done(
                summary: "Mapped \(plan.walls.count) walls, \(plan.openings.count) doors/windows and \(plan.objects.count) objects.",
                files: files
            )
        case let .failure(error):
            phase = .failed(error.localizedDescription)
        }
    }
}
