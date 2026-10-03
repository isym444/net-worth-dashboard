import AppKit
import SwiftUI
import NetWorthCore
import NetWorthViews

// Dev-only: renders every widget size to a PNG using the synthetic sample data.
//   swift run LayoutRenderer <output-dir>

var calendar = Calendar(identifier: .gregorian)
calendar.timeZone = TimeZone(identifier: "UTC")!
let now = ISO8601DateFormatter().date(from: "2026-09-30T12:38:00Z")!

func state(contributions: Bool = true, fetchedAgoMin: Int64 = 38) -> WidgetState {
    let history = SampleData.history(now: now)
    return WidgetModel.build(
        cache: CacheSnapshot(fetchedAt: Int64(now.timeIntervalSince1970 * 1000) - fetchedAgoMin * 60_000, history: history),
        config: WidgetConfig(withContributions: contributions), settings: AppSettings(baseUrl: "https://sample/", apiKey: "k"),
        status: SyncStatus(), now: now, calendar: calendar)
}

@MainActor func render(_ name: String, _ state: WidgetState, _ layout: LayoutKind, _ scheme: ColorScheme, into dir: URL) {
    let palette = Palette(scheme)
    let view = NetWorthWidgetView(state: state, layout: layout)
        .environment(\.colorScheme, scheme)
        .frame(width: layout.size.width, height: layout.size.height)
        .background(palette.background)
        .clipShape(RoundedRectangle(cornerRadius: 22))
    let renderer = ImageRenderer(content: view)
    renderer.scale = 2
    guard let tiff = renderer.nsImage?.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
          let png = rep.representation(using: .png, properties: [:]) else { print("FAILED \(name)"); return }
    try? png.write(to: dir.appendingPathComponent("\(name).png"))
    print("wrote \(name).png")
}

let outDir = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "renders", isDirectory: true)
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

MainActor.assumeIsolated {
    let ready = state()
    for layout in LayoutKind.allCases {
        for scheme in [ColorScheme.dark, .light] {
            render("\(layout)-\(scheme == .dark ? "dark" : "light")", ready, layout, scheme, into: outDir)
        }
    }
    render("large-no-contributions", state(contributions: false), .large, .dark, into: outDir)
    render("extraLarge-no-contributions", state(contributions: false), .extraLarge, .dark, into: outDir)
    render("extraLarge-stale", state(fetchedAgoMin: 500), .extraLarge, .dark, into: outDir)
    render("small-not-configured", .notConfigured, .small, .dark, into: outDir)
    render("medium-empty", .empty("初回の同期を待っています"), .medium, .dark, into: outDir)
}
