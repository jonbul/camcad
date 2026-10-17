import Shared
import SwiftUI

/// Shows a scan as a 2D plan or an orbitable 3D view, drawn from the shared Kotlin preview renderer.
struct PreviewView: View {
    let model: PreviewModel
    @Environment(\.dismiss) private var dismiss
    @State private var threeD = true
    @State private var yaw = 30.0
    @State private var pitch = 40.0
    @State private var zoom = 1.0
    @State private var dragStart: (yaw: Double, pitch: Double)?
    @State private var zoomStart: Double?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text(model.summary).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Picker("View", selection: $threeD) {
                    Text("3D").tag(true)
                    Text("2D plan").tag(false)
                }
                .pickerStyle(.segmented)
                canvas
                    .background(Color(white: 0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .gesture(dragGesture.simultaneously(with: zoomGesture))
                Text(threeD ? "Drag to turn · pinch to zoom" : "Pinch to zoom").font(.caption).foregroundStyle(.secondary)
            }
            .padding()
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var canvas: some View {
        Canvas { context, size in
            if model.isEmpty {
                context.draw(Text("Nothing to show in this file").foregroundStyle(.white), at: CGPoint(x: size.width / 2, y: size.height / 2))
                return
            }
            let w = Double(size.width), h = Double(size.height)
            let frame = threeD
                ? PreviewRenderer.shared.perspective(model: model, width: w, height: h, yawDeg: yaw, pitchDeg: pitch, zoom: zoom)
                : PreviewRenderer.shared.topDown(model: model, width: w, height: h, zoom: zoom)
            for polygon in frame.polygons {
                let pts = polygon.points
                guard pts.count >= 3 else { continue }
                var path = Path()
                path.move(to: CGPoint(x: pts[0].x, y: pts[0].y))
                for p in pts.dropFirst() { path.addLine(to: CGPoint(x: p.x, y: p.y)) }
                path.closeSubpath()
                context.fill(path, with: .color(color(polygon.kind, polygon.shade)))
                context.stroke(path, with: .color(.black.opacity(0.5)), lineWidth: 1)
            }
            for label in frame.labels {
                context.draw(Text(label.text).font(.caption.bold()).foregroundStyle(.white), at: CGPoint(x: label.at.x, y: label.at.y))
            }
        }
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { v in
                guard threeD else { return }
                let start = dragStart ?? (yaw, pitch)
                dragStart = start
                yaw = start.yaw + Double(v.translation.width) * 0.4
                pitch = min(90, max(5, start.pitch + Double(v.translation.height) * 0.3))
            }
            .onEnded { _ in dragStart = nil }
    }

    private var zoomGesture: some Gesture {
        MagnificationGesture()
            .onChanged { scale in
                let start = zoomStart ?? zoom
                zoomStart = start
                zoom = min(8, max(0.3, start * Double(scale)))
            }
            .onEnded { _ in zoomStart = nil }
    }

    private func color(_ kind: String, _ shade: Double) -> Color {
        let base: (Double, Double, Double)
        switch kind {
        case "room": base = (0.89, 0.91, 0.94)
        case "wall": base = (0.44, 0.50, 0.59)
        case "door": base = (0.87, 0.42, 0.13)
        case "window": base = (0.26, 0.60, 0.88)
        case "opening": base = (0.63, 0.68, 0.75)
        default: base = (0.28, 0.73, 0.47)
        }
        return Color(red: base.0 * shade, green: base.1 * shade, blue: base.2 * shade)
    }
}

/// Wraps a model so it can drive `.sheet(item:)`.
struct PreviewItem: Identifiable {
    let id = UUID()
    let model: PreviewModel
}
