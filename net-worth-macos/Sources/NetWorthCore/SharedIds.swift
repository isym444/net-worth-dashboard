import Foundation

/// Identifiers shared by the app and the widget extension. Both read the same Info.plist keys (filled in from the signing
/// team by the build settings in project.yml), so no team ID is hard-coded in source.
public enum SharedIds {
    public static let keychainService = "com.isym444.networth"

    public static func appGroup(_ bundle: Bundle = .main) -> String? { bundle.object(forInfoDictionaryKey: "NWAppGroup") as? String }
    public static func keychainGroup(_ bundle: Bundle = .main) -> String? { bundle.object(forInfoDictionaryKey: "NWKeychainGroup") as? String }
}
