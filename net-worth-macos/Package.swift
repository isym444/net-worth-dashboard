// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NetWorthMac",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "NetWorthCore", targets: ["NetWorthCore"]),
        .library(name: "NetWorthViews", targets: ["NetWorthViews"]),
    ],
    targets: [
        .target(name: "NetWorthCore"),
        .target(name: "NetWorthViews", dependencies: ["NetWorthCore"]),
        .executableTarget(name: "LayoutRenderer", dependencies: ["NetWorthViews", "NetWorthCore"]),
        .testTarget(name: "NetWorthCoreTests", dependencies: ["NetWorthCore"]),
    ],
    swiftLanguageModes: [.v5]
)
