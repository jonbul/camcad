import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Where scans are saved. The default is Files › On My iPhone › Eyecad › scans; picking another folder
/// makes it the new default (kept as a security-scoped bookmark).
final class SaveLocation: ObservableObject {
    private static let key = "saveFolderBookmark"
    @Published private(set) var folder: URL?

    init() {
        folder = Self.resolveBookmark()
    }

    var label: String { folder?.lastPathComponent ?? "On My iPhone › Eyecad › scans" }

    func remember(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            UserDefaults.standard.set(data, forKey: Self.key)
            folder = url
        }
    }

    func resetToDefault() {
        UserDefaults.standard.removeObject(forKey: Self.key)
        folder = nil
    }

    /// Copies the files into the default folder and returns its name.
    func save(_ files: [URL]) throws -> String {
        let target: URL
        var scoped = false
        if let folder {
            scoped = folder.startAccessingSecurityScopedResource()
            target = folder
        } else {
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            target = docs.appendingPathComponent("scans", isDirectory: true)
            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
        }
        defer { if scoped { folder?.stopAccessingSecurityScopedResource() } }
        for file in files {
            let dest = target.appendingPathComponent(file.lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: file, to: dest)
        }
        return label
    }

    private static func resolveBookmark() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        var stale = false
        return try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
    }
}

/// System folder picker.
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
    }
}
