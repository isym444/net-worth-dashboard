import NetWorthCore
import SwiftUI
import WidgetKit

/// URL, key and dashboard address, plus "Save & test": validates, fetches once, shows the latest total or the error, and
/// reloads every widget. The Keychain and App Group are shared with the widget extension.
struct SettingsView: View {
    @State private var baseUrl = AppSettings.defaultBaseUrl
    @State private var apiKey = ""
    @State private var webUrl = ""
    @State private var errors: [Field: String] = [:]
    @State private var status: String?
    @State private var busy = false
    @State private var summary = ""

    private let env = SharedEnvironment.make()

    var body: some View {
        Form {
            Section {
                field("API URL", text: $baseUrl, error: errors[.baseUrl])
                secureField("Access key", text: $apiKey, error: errors[.apiKey])
                field("Dashboard URL (optional)", text: $webUrl, error: errors[.webUrl])
            } header: {
                Text("純資産 — connection").font(.headline)
            }
            Section {
                HStack {
                    Button("Save & test", action: saveAndTest).disabled(busy).keyboardShortcut(.defaultAction)
                    if busy { ProgressView().controlSize(.small) }
                }
                if let status { Text(status).foregroundStyle(status.hasPrefix("OK") ? Color.green : Color.secondary) }
                if !summary.isEmpty { Text(summary).font(.footnote).foregroundStyle(.secondary) }
            }
            Section {
                Text("Add the widget: right-click the desktop → Edit Widgets → search 純資産. The widget refreshes about once an hour; macOS decides the exact timing.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .padding(.vertical, 8)
        .onAppear(perform: load)
    }

    private func field(_ title: String, text: Binding<String>, error: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            TextField(title, text: text).textContentType(.URL).autocorrectionDisabled()
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        }
    }

    private func secureField(_ title: String, text: Binding<String>, error: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            SecureField(title, text: text)
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        }
    }

    private func load() {
        let s = env.settings.load()
        if !s.baseUrl.isEmpty { baseUrl = s.baseUrl }
        apiKey = s.apiKey
        webUrl = s.webUrl
        summary = syncSummary()
    }

    private func saveAndTest() {
        switch SettingsValidator.validate(baseUrl: baseUrl, apiKey: apiKey, webUrl: webUrl) {
        case .invalid(let e):
            errors = e
            status = nil
        case .valid(let settings):
            errors = [:]
            busy = true
            status = "Connecting…"
            Task {
                do { try env.settings.save(settings) } catch {
                    status = ApiError.storage("\(error)").userMessage
                    busy = false
                    return
                }
                let sync = Sync(cache: env.cache, source: { env.settings.source() }, onResult: env.settings.recordSync)
                switch await sync.refresh() {
                case .failed(let error):
                    status = error.userMessage
                case .updated:
                    if let latest = env.cache.load().history?.snapshots.last {
                        status = "OK — latest \(Money.full(latest.grandTotal, unit: Money.unit(latest.currency)))"
                    } else {
                        status = "OK — no snapshots yet"
                    }
                }
                WidgetCenter.shared.reloadAllTimelines()
                summary = syncSummary()
                busy = false
            }
        }
    }

    private func syncSummary() -> String {
        let s = env.settings.status()
        if s.lastSuccessAt > 0 {
            let date = Date(timeIntervalSince1970: Double(s.lastSuccessAt) / 1000)
            return "Last synced " + date.formatted(date: .abbreviated, time: .shortened)
        }
        return s.lastError ?? "Not synced yet"
    }
}
