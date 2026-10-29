import SwiftUI

/// Nine-zone gesture editor drawn as a phone screen. Each zone sits where it is on the real player
/// (three equal rows; columns 20% / 60% / 20%) and holds its four gesture controls in place.
struct GestureSettings: View {
    @State private var version = 0
    @State private var confirmReset = false

    private static let kindBadge: [String: String] = ["tap": "单", "double": "双", "hold": "长", "drag": "拖"]
    private static let rowTint: [Color] = [Color.gray, Color.blue, Color.orange]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                legend
                phone
                    .frame(maxWidth: 430)
                    .padding(.horizontal, 12)
                Text("点任意一格里的动作即可更换。上中下三行等高，左右两列各占 20%。顶部与底部控件区域不响应手势。缩放后只能平移，双击恢复宽度适屏。")
                    .font(.footnote).foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
                Button(role: .destructive) { confirmReset = true } label: { Text("恢复默认") }
                    .padding(.bottom, 24)
            }
            .padding(.top, 8)
        }
        .navigationTitle("九宫格手势")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("恢复九宫格默认手势？", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("恢复默认", role: .destructive) { Store.shared.resetGestures(); version += 1 }
        }
    }

    private var legend: some View {
        HStack(spacing: 12) {
            ForEach(GestureBindings.kinds, id: \.0) { kind, title in
                HStack(spacing: 4) { badge(kind); Text(title).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }

    private func badge(_ kind: String) -> some View {
        Text(Self.kindBadge[kind] ?? "?")
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 16, height: 16)
            .background(Circle().fill(Color.accentColor))
    }

    // MARK: phone

    private var phone: some View {
        VStack(spacing: 0) {
            // Status bar + player top controls (not part of the gesture area).
            chrome(height: 44, icons: ["chevron.down", "rotate.right", "pip.enter", "speedometer"])
            GeometryReader { geo in
                let w = geo.size.width, h = geo.size.height
                let colW = [w * 0.2, w * 0.6, w * 0.2]
                VStack(spacing: 0) {
                    ForEach(0..<3, id: \.self) { row in
                        HStack(spacing: 0) {
                            ForEach(0..<3, id: \.self) { col in
                                zone(row * 3 + col)
                                    .frame(width: colW[col], height: h / 3)
                            }
                        }
                    }
                }
            }
            .aspectRatio(9.0 / 13.0, contentMode: .fit)
            // Player bottom controls + timeline.
            chrome(height: 52, icons: ["info.circle", "bookmark", "play.fill", "lock.open", "ellipsis.circle"])
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 34, style: .continuous).fill(Color.black))
        .overlay(RoundedRectangle(cornerRadius: 34, style: .continuous).stroke(Color(.systemGray3), lineWidth: 3))
        .overlay(alignment: .top) {
            Capsule().fill(Color(white: 0.12)).frame(width: 72, height: 18).padding(.top, 14)
        }
        .id(version)
        .accessibilityElement(children: .contain)
    }

    private func chrome(height: CGFloat, icons: [String]) -> some View {
        HStack {
            ForEach(icons, id: \.self) { Image(systemName: $0).frame(maxWidth: .infinity) }
        }
        .font(.system(size: 12))
        .foregroundStyle(Color(white: 0.45))
        .frame(height: height)
        .frame(maxWidth: .infinity)
        .background(Color(white: 0.08))
        .clipShape(RoundedRectangle(cornerRadius: height == 44 ? 26 : 22, style: .continuous))
    }

    private func zone(_ z: Int) -> some View {
        let tint = Self.rowTint[z / 3]
        return VStack(alignment: .leading, spacing: 5) {
            Text(GestureBindings.zoneNames[z])
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(tint.opacity(0.9))
                .frame(maxWidth: .infinity, alignment: .center)
            ForEach(GestureBindings.kinds, id: \.0) { kind, title in
                actionMenu(zone: z, kind: kind, title: title)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 3).padding(.vertical, 5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(tint.opacity(0.14))
        .overlay(Rectangle().stroke(Color.white.opacity(0.18), style: StrokeStyle(lineWidth: 0.5, dash: [4, 3])))
    }

    private func actionMenu(zone z: Int, kind: String, title: String) -> some View {
        let current = Store.shared.gestureAction(z, kind)
        let off = current == "none"
        return Menu {
            Picker("\(GestureBindings.zoneNames[z]) · \(title)", selection: Binding(
                get: { Store.shared.gestureAction(z, kind) },
                set: { Store.shared.setGestureAction(z, kind, $0); version += 1 })) {
                ForEach(GestureBindings.actions(kind), id: \.self) { Text(GestureBindings.label($0)).tag($0) }
            }
        } label: {
            HStack(spacing: 3) {
                badge(kind).opacity(off ? 0.45 : 1)
                Text(GestureBindings.shortLabel(current))
                    .font(.system(size: 12, weight: off ? .regular : .semibold))
                    .foregroundStyle(off ? Color(white: 0.5) : .white)
                    .lineLimit(1).minimumScaleFactor(0.7)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 4).padding(.vertical, 3)
            .background(RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(off ? 0.04 : 0.12)))
        }
        .accessibilityIdentifier("zone-\(z)-\(kind)")
        .accessibilityLabel("\(GestureBindings.zoneNames[z]) \(title)：\(GestureBindings.label(current))")
    }
}
