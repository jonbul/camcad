import Shared
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    enum Mode: String, CaseIterable, Identifiable {
        case lidar = "LiDAR room scan"
        case camera = "Camera scan"
        var id: String { rawValue }
    }

    enum Phase {
        case idle, scanning, processing
        case done(summary: String)
        case failed(String)
    }

    @State private var mode: Mode = LidarScanner.isSupported ? .lidar : .camera
    @State private var phase: Phase = .idle
    @StateObject private var lidar = LidarScanner()
    @StateObject private var camera = CameraScanner()
    @StateObject private var saveLocation = SaveLocation()
    /// The last finished scan. Save, Share and Preview stay available until a new scan starts.
    @State private var lastExport: ScanExport?
    @State private var lastFiles: [URL] = []
    @State private var askSave = false
    @State private var pickFolder = false
    @State private var openFile = false
    @State private var preview: PreviewItem?
    @State private var notice: String?

    var body: some View {
        content
            .confirmationDialog("Save scan", isPresented: $askSave, titleVisibility: .visible) {
                Button("Save to \(saveLocation.label)") { save() }
                Button("Choose folder…") { pickFolder = true }
                Button("Not now", role: .cancel) {}
            } message: {
                Text("Where should the GeoJSON and 3D model be saved? A folder you choose becomes the default.")
            }
            .sheet(isPresented: $pickFolder) {
                FolderPicker { url in
                    saveLocation.remember(url)
                    save()
                }
            }
            .sheet(item: $preview) { PreviewView(model: $0.model) }
            .fileImporter(isPresented: $openFile, allowedContentTypes: [.json, .data, .item]) { result in
                if case let .success(url) = result { open(url) }
            }
            .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
                Button("OK", role: .cancel) {}
            }
    }

    @ViewBuilder private var content: some View {
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
                Button("Open a saved scan…") { openFile = true }
                Text("Saving to \(saveLocation.label)").font(.footnote).foregroundStyle(.secondary)
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

        case let .done(summary):
            VStack(spacing: 16) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(.green)
                Text(summary).multilineTextAlignment(.center)
                Button { askSave = true } label: { Label("Save", systemImage: "folder") }
                    .buttonStyle(.borderedProminent)
                ShareLink(items: lastFiles) { Label("Share", systemImage: "square.and.arrow.up") }
                    .buttonStyle(.bordered)
                Button { showPreview() } label: { Label("Preview", systemImage: "cube") }
                    .buttonStyle(.bordered)
                Divider().padding(.vertical, 8)
                Button("New scan") { start() }
                Button("Open a saved scan…") { openFile = true }.font(.footnote)
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
        return "\(camera.message)\nWalls: \(s.walls)  Objects: \(s.objects)  Points: \(s.points)"
    }

    private func start() {
        lastExport = nil
        lastFiles = []
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
            lastExport = export
            lastFiles = files
            let plan = export.plan
            phase = .done(summary: "Mapped \(plan.walls.count) walls, \(plan.openings.count) doors/windows and \(plan.objects.count) objects.")
            askSave = true
        case let .failure(error):
            phase = .failed(error.localizedDescription)
        }
    }

    private func save() {
        do {
            notice = "Saved to \(try saveLocation.save(lastFiles))"
        } catch {
            // A folder that moved or lost access falls back to the built-in default.
            saveLocation.resetToDefault()
            notice = "Couldn't save: \(error.localizedDescription). The default folder is back to \(saveLocation.label)."
        }
    }

    private func showPreview() {
        guard let export = lastExport else { return }
        preview = PreviewItem(model: PreviewModel.Companion.shared.fromPlan(plan: export.plan, title: lastFiles.first?.deletingPathExtension().lastPathComponent ?? "Scan"))
    }

    private func open(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let text = try String(contentsOf: url, encoding: .utf8)
            preview = PreviewItem(model: try PreviewModel.Companion.shared.fromFile(name: url.lastPathComponent, text: text))
        } catch {
            notice = "Couldn't open \(url.lastPathComponent): \(error.localizedDescription)"
        }
    }
}
