import Foundation

/// Identifiers shared by the app and the widget extension. Both read the same Info.plist keys (filled in from the signing
/// team by the build settings in project.yml), so no team ID is hard-coded in source.
public enum SharedIds {
    public static func appGroup(_ bundle: Bundle = .main) -> String? { bundle.object(forInfoDictionaryKey: "NWAppGroup") as? String }
}
