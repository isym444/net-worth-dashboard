import SwiftUI

/// The widget palette (same values as the Android widget), resolved from the color scheme so it renders identically in
/// the widget and in the offscreen layout renderer.
public struct Palette {
    public let scheme: ColorScheme

    public init(_ scheme: ColorScheme) { self.scheme = scheme }

    private var dark: Bool { scheme == .dark }
    private func hex(_ v: UInt32) -> Color {
        Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    public var background: Color { dark ? hex(0x1C1C1E) : hex(0xFFFFFF) }
    public var primary: Color { dark ? hex(0xF2F2F7) : hex(0x111111) }
    public var secondary: Color { dark ? hex(0x9A9AA0) : hex(0x6B6B70) }
    public var up: Color { dark ? hex(0x66BB6A) : hex(0x2E7D32) }
    public var down: Color { dark ? hex(0xEF5350) : hex(0xC62828) }
    public var warn: Color { hex(0xFB8C00) }
    public var accent: Color { dark ? hex(0x64B5F6) : hex(0x1E88E5) }
    public var track: Color { dark ? hex(0x3A3A3F) : hex(0xE3E3E8) }

    func direction(_ up: Bool?) -> Color {
        switch up {
        case .some(true): return self.up
        case .some(false): return down
        case .none: return secondary
        }
    }
}
