import SwiftUI
import NetWorthCore

struct Label11: View {
    @Environment(\.colorScheme) private var scheme
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.system(size: 11)).foregroundStyle(Palette(scheme).secondary).lineLimit(1) }
}

struct Headline: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState.Ready
    let size: CGFloat
    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 3) {
            Text(state.headlineAmount).font(.system(size: size, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(state.headlineUnit).font(.system(size: size * 0.5, weight: .bold)).lineLimit(1)
        }
        .foregroundStyle(Palette(scheme).primary)
    }
}

struct ChangeLine: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState.Ready
    var body: some View {
        if let text = state.change {
            Text(text).font(.system(size: 12)).foregroundStyle(Palette(scheme).direction(state.changeUp)).lineLimit(1)
        }
    }
}

struct Footer: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState.Ready
    var body: some View {
        Text(state.hint ?? state.updated).font(.system(size: 10))
            .foregroundStyle(state.hint != nil ? Palette(scheme).warn : Palette(scheme).secondary).lineLimit(1)
    }
}

/// A two-column row: a fixed-width leading label and a trailing value.
struct KeyValueRow: View {
    @Environment(\.colorScheme) private var scheme
    let label: String
    let value: String
    let labelWidth: CGFloat
    var valueColor: Color?
    var body: some View {
        HStack(spacing: 0) {
            Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(Palette(scheme).primary)
                .lineLimit(1).frame(width: labelWidth, alignment: .leading)
            Text(value).font(.system(size: 12)).foregroundStyle(valueColor ?? Palette(scheme).secondary).lineLimit(1)
        }
        .padding(.vertical, 1.5)
    }
}

struct MilestoneRows: View {
    @Environment(\.colorScheme) private var scheme
    let rows: [MilestoneRow]
    var labelWidth: CGFloat = 84
    var body: some View {
        ForEach(Array(rows.enumerated()), id: \.offset) { _, m in
            KeyValueRow(label: m.label, value: m.value, labelWidth: labelWidth, valueColor: m.reached ? Palette(scheme).up : nil)
        }
    }
}

struct GoalBarRow: View {
    @Environment(\.colorScheme) private var scheme
    let label: String
    let bar: GoalBar?
    let color: Color
    var body: some View {
        HStack(spacing: 6) {
            Text(label).font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1).frame(width: 54, alignment: .leading)
            if let bar {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Palette(scheme).track)
                        Capsule().fill(color).frame(width: max(6, geo.size.width * bar.fraction))
                    }
                }
                .frame(height: 6)
                Text(bar.percent).font(.system(size: 11, weight: .medium)).foregroundStyle(Palette(scheme).primary).lineLimit(1)
            } else {
                Text("算出不可").font(.system(size: 11)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
                Spacer(minLength: 0)
            }
        }
    }
}

struct NextGoalBlock: View {
    @Environment(\.colorScheme) private var scheme
    let goal: NextGoal
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Label11("\(goal.label)まで・\(goal.remaining)")
            GoalBarRow(label: "資産の寄与", bar: goal.growth, color: Palette(scheme).up)
            GoalBarRow(label: "時間の進捗", bar: goal.time, color: Palette(scheme).accent)
        }
    }
}

struct PeriodRow: View {
    @Environment(\.colorScheme) private var scheme
    let change: PeriodChange
    var body: some View {
        KeyValueRow(label: change.label, value: change.text, labelWidth: 56, valueColor: Palette(scheme).direction(change.up))
    }
}

struct CompareTable: View {
    @Environment(\.colorScheme) private var scheme
    let rows: [ProjectionCompareRow]
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Color.clear.frame(width: 44, height: 1)
                Text("積立あり").frame(maxWidth: .infinity, alignment: .leading)
                Text("積立なし").frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
            ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                HStack(spacing: 0) {
                    Text(r.label).font(.system(size: 13, weight: .medium)).frame(width: 44, alignment: .leading)
                    Text(r.withContributions).frame(maxWidth: .infinity, alignment: .leading)
                    Text(r.withoutContributions).foregroundStyle(Palette(scheme).secondary).frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.system(size: 12)).lineLimit(1).padding(.vertical, 1.5)
                .foregroundStyle(Palette(scheme).primary)
            }
        }
    }
}

/// The trend as a line with a soft fill; points are placed by date, so uneven snapshot spacing is drawn honestly.
struct TrendChart: View {
    @Environment(\.colorScheme) private var scheme
    let points: [TrendPoint]

    var body: some View {
        let up = (points.last?.total ?? 0) >= (points.first?.total ?? 0)
        let color = up ? Palette(scheme).up : Palette(scheme).down
        GeometryReader { geo in
            let pad: CGFloat = 5
            let w = max(geo.size.width - 2 * pad, 1)
            let h = max(geo.size.height - 2 * pad, 1)
            let totals = points.map(\.total)
            let lo = totals.min() ?? 0
            let span = max((totals.max() ?? 0) - lo, 1)
            let t0 = points.first?.date.timeIntervalSince1970 ?? 0
            let tSpan = max((points.last?.date.timeIntervalSince1970 ?? 0) - t0, 1)
            let xy: [CGPoint] = points.map {
                CGPoint(x: pad + w * ($0.date.timeIntervalSince1970 - t0) / tSpan, y: pad + h * (1 - ($0.total - lo) / span))
            }
            ZStack {
                Path { p in
                    guard let first = xy.first, let last = xy.last else { return }
                    p.move(to: CGPoint(x: first.x, y: geo.size.height))
                    xy.forEach { p.addLine(to: $0) }
                    p.addLine(to: CGPoint(x: last.x, y: geo.size.height))
                    p.closeSubpath()
                }
                .fill(LinearGradient(colors: [color.opacity(0.33), color.opacity(0)], startPoint: .top, endPoint: .bottom))
                Path { p in
                    guard let first = xy.first else { return }
                    p.move(to: first)
                    xy.dropFirst().forEach { p.addLine(to: $0) }
                }
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                if let last = xy.last { Circle().fill(color).frame(width: 7, height: 7).position(last) }
            }
        }
    }
}
