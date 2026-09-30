import AppIntents
import NetWorthCore
import NetWorthViews
import SwiftUI
import WidgetKit

/// Edit Widget → 積立を含める (default on), like the Android widget's per-widget option.
struct NetWorthIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "純資産"
    static var description = IntentDescription("目標までの期間に毎月の積立を含めるかどうかを選びます。")

    @Parameter(title: "積立を含める", default: true)
    var withContributions: Bool
}

struct NetWorthEntry: TimelineEntry {
    let date: Date
    let state: WidgetState
    let dashboardURL: URL?
}

struct NetWorthProvider: AppIntentTimelineProvider {
    typealias Entry = NetWorthEntry
    typealias Intent = NetWorthIntent

    func placeholder(in context: Context) -> NetWorthEntry {
        NetWorthEntry(date: Date(), state: SampleData.state(), dashboardURL: nil)
    }

    func snapshot(for configuration: NetWorthIntent, in context: Context) async -> NetWorthEntry {
        // The widget gallery preview uses sample data; a real snapshot renders from the cache without touching the network.
        if context.isPreview { return NetWorthEntry(date: Date(), state: SampleData.state(), dashboardURL: nil) }
        return entries(for: configuration, start: Date()).first!
    }

    func timeline(for configuration: NetWorthIntent, in context: Context) async -> Timeline<NetWorthEntry> {
        let env = SharedEnvironment.make()
        await Sync(cache: env.cache, source: { env.settings.source() }, onResult: env.settings.recordSync).refresh()
        let now = Date()
        return Timeline(entries: entries(for: configuration, start: now), policy: .after(now.addingTimeInterval(EntryPlan.refreshAfter)))
    }

    /// Entries built from the cache only (never the network), one per `EntryPlan` step.
    private func entries(for configuration: NetWorthIntent, start: Date) -> [NetWorthEntry] {
        let env = SharedEnvironment.make()
        let settings = env.settings.load()
        let url = settings.webUrl.isEmpty ? nil : UrlPolicy.parse(settings.webUrl)
        return EntryPlan.states(
            cache: env.cache.load(), config: WidgetConfig(withContributions: configuration.withContributions),
            settings: settings, status: env.settings.status(), start: start, calendar: .current
        ).map { NetWorthEntry(date: $0.date, state: $0.state, dashboardURL: url) }
    }
}

struct NetWorthEntryView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme
    let entry: NetWorthEntry

    private var layout: LayoutKind {
        switch family {
        case .systemSmall: return .small
        case .systemMedium: return .medium
        case .systemLarge: return .large
        default: return .extraLarge
        }
    }

    var body: some View {
        NetWorthWidgetView(state: entry.state, layout: layout)
            .widgetURL(entry.dashboardURL)      // nil opens the host app
            .containerBackground(for: .widget) { Palette(scheme).background }
    }
}

@main
struct NetWorthWidgetBundle: WidgetBundle {
    var body: some Widget { NetWorthWidget() }
}

struct NetWorthWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "NetWorthWidget", intent: NetWorthIntent.self, provider: NetWorthProvider()) { entry in
            NetWorthEntryView(entry: entry)
        }
        .configurationDisplayName("純資産")
        .description("純資産、目標までの期間、将来の予想を表示します。")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
        .contentMarginsDisabled()
    }
}
