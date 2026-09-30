import SwiftUI
import NetWorthCore

/// The four widget sizes; each shows more than the last (see the design spec).
public enum LayoutKind: CaseIterable {
    case small, medium, large, extraLarge

    /// Point sizes of the macOS widget families, used by the layout renderer.
    public var size: CGSize {
        switch self {
        case .small: return CGSize(width: 170, height: 170)
        case .medium: return CGSize(width: 364, height: 170)
        case .large: return CGSize(width: 364, height: 382)
        case .extraLarge: return CGSize(width: 715, height: 382)
        }
    }
}

public struct NetWorthWidgetView: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState
    let layout: LayoutKind

    public init(state: WidgetState, layout: LayoutKind) {
        self.state = state
        self.layout = layout
    }

    public var body: some View {
        Group {
            switch state {
            case .ready(let s):
                switch layout {
                case .small: SmallLayout(s: s)
                case .medium: MediumLayout(s: s)
                case .large: LargeLayout(s: s)
                case .extraLarge: ExtraLargeLayout(s: s)
                }
            case .empty(let message): MessageView(text: message)
            case .notConfigured: MessageView(text: "アプリを開いて設定してください")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct MessageView: View {
    @Environment(\.colorScheme) private var scheme
    let text: String
    var body: some View {
        Text(text).font(.system(size: 13)).multilineTextAlignment(.center)
            .foregroundStyle(Palette(scheme).secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SmallLayout: View {
    let s: WidgetState.Ready
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label11("純資産")
            Headline(state: s, size: 24)
            ChangeLine(state: s)
            Spacer(minLength: 4)
            Footer(state: s)
        }
    }
}

private struct MediumLayout: View {
    @Environment(\.colorScheme) private var scheme
    let s: WidgetState.Ready
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 0) {
                Label11("純資産")
                Headline(state: s, size: 24)
                ChangeLine(state: s)
                Spacer(minLength: 4)
                Footer(state: s)
            }
            .fixedSize(horizontal: true, vertical: false)      // the headline keeps its natural width; the milestones get the rest
            VStack(alignment: .leading, spacing: 0) {
                let next = s.nextMilestones(3)
                if next.isEmpty {
                    Text("全ての目標を達成しました").font(.system(size: 13, weight: .medium)).foregroundStyle(Palette(scheme).up)
                } else {
                    Label11("次の目標")
                    MilestoneRows(rows: next, labelWidth: 78)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct LargeLayout: View {
    @Environment(\.colorScheme) private var scheme
    let s: WidgetState.Ready
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label11("純資産")
            Headline(state: s, size: 30)
            ChangeLine(state: s)
            if let oneYear = s.oneYear {
                Text("1年後の予想 ≈ \(oneYear)").font(.system(size: 12)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            Label11("目標までの期間")
            Text(s.assumption).font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
            MilestoneRows(rows: s.milestones)
            if !s.projections.isEmpty {
                Spacer(minLength: 6)
                Label11("将来の予想純資産")
                ForEach(Array(s.projections.enumerated()), id: \.offset) { _, p in
                    KeyValueRow(label: p.label, value: p.value, labelWidth: 84)
                }
            }
            Spacer(minLength: 4)
            Footer(state: s)
        }
    }
}

private struct ExtraLargeLayout: View {
    @Environment(\.colorScheme) private var scheme
    let s: WidgetState.Ready
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 18) {
                VStack(alignment: .leading, spacing: 0) {
                    Label11("純資産")
                    Headline(state: s, size: 30)
                    ChangeLine(state: s)
                    if let oneYear = s.oneYear {
                        Text("1年後の予想 ≈ \(oneYear)").font(.system(size: 12)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
                    }
                    if let goal = s.nextGoal {
                        Spacer().frame(height: 8)
                        NextGoalBlock(goal: goal)
                    }
                    if !s.periodChanges.isEmpty || s.peak != nil {
                        Spacer().frame(height: 8)
                        Label11("増減の推移")
                        ForEach(Array(s.periodChanges.enumerated()), id: \.offset) { _, c in PeriodRow(change: c) }
                        if let peak = s.peak {
                            Text(peak).font(.system(size: 12)).foregroundStyle(Palette(scheme).secondary).lineLimit(1).padding(.vertical, 1.5)
                        }
                    }
                    Spacer(minLength: 4)
                    Footer(state: s)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 0) {
                    Label11("目標までの期間")
                    Text(s.assumption).font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
                    MilestoneRows(rows: s.milestones)
                    if !s.projectionTable.isEmpty {
                        Spacer().frame(height: 8)
                        Label11("将来の予想純資産")
                        CompareTable(rows: s.projectionTable)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if s.trend.count >= 2 {
                Spacer().frame(height: 6)
                Label11("推移 (過去\(s.trendSpan))")
                TrendChart(points: s.trend).frame(minHeight: 36, maxHeight: .infinity)
            }
        }
    }
}
