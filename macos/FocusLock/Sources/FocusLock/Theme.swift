import AppKit
import SwiftUI

// Otterling design system, ported from the desktop redesign (Figma "Complete User Prompt").
// Material-3 style tokens (indigo primary, teal secondary, amber tertiary, restrained red error),
// rounded surfaces, soft borders, status pills. Light + dark are first-class -- every token below
// resolves per-appearance via `Color.otter(light:dark:)`, so the whole UI follows the system theme.

extension Color {
    init(hex: String) {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        var rgb: UInt64 = 0
        Scanner(string: s).scanHexInt64(&rgb)
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255
        )
    }

    /// A dynamic color that picks `light` or `dark` from the current appearance -- so a single
    /// static token works in both themes without threading `colorScheme` through every view.
    static func otter(_ light: String, _ dark: String) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return NSColor(Color(hex: isDark ? dark : light))
        })
    }
}

enum Otter {
    static let primary = Color.otter("#3F51B5", "#818CF8")
    static let onPrimary = Color.otter("#FFFFFF", "#1E1B4B")
    static let primaryContainer = Color.otter("#E0E7FF", "#3730A3")
    static let onPrimaryContainer = Color.otter("#111827", "#E0E7FF")

    static let secondary = Color.otter("#0D9488", "#2DD4BF")
    static let secondaryContainer = Color.otter("#CCFBF1", "#0F766E")
    static let onSecondaryContainer = Color.otter("#134E4A", "#CCFBF1")

    static let tertiary = Color.otter("#B45309", "#FBBF24") // slightly deepened for light-mode text contrast
    static let tertiaryContainer = Color.otter("#FEF3C7", "#92400E")

    static let error = Color.otter("#DC2626", "#F87171")
    static let errorContainer = Color.otter("#FEE2E2", "#991B1B")

    static let background = Color.otter("#F8FAFC", "#0F172A")
    static let surface = Color.otter("#FFFFFF", "#1E293B")
    static let surfaceVariant = Color.otter("#E2E8F0", "#334155")
    static let onSurface = Color.otter("#0F172A", "#F8FAFC")
    static let onSurfaceVariant = Color.otter("#475569", "#CBD5E1")
    static let outlineVariant = Color.otter("#CBD5E1", "#475569")

    static let cardRadius: CGFloat = 16
}

// MARK: - Card

/// The workhorse container: surface fill, soft hairline border, rounded corners -- the `rounded-2xl`
/// card the whole redesign is built from.
struct Card<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12, content: content)
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Otter.surface)
            .clipShape(RoundedRectangle(cornerRadius: Otter.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Otter.cardRadius, style: .continuous)
                    .stroke(Otter.outlineVariant.opacity(0.4), lineWidth: 1)
            )
    }
}

// MARK: - Pill

enum PillVariant {
    case success, warning, error, neutral, info

    var fg: Color {
        switch self {
        case .success: return Otter.secondary
        case .warning: return Otter.tertiary
        case .error: return Otter.error
        case .info: return Otter.primary
        case .neutral: return Otter.onSurfaceVariant
        }
    }

    var bg: Color {
        switch self {
        case .success: return Otter.secondaryContainer.opacity(0.6)
        case .warning: return Otter.tertiaryContainer.opacity(0.6)
        case .error: return Otter.errorContainer.opacity(0.6)
        case .info: return Otter.primaryContainer.opacity(0.6)
        case .neutral: return Otter.surfaceVariant
        }
    }
}

struct Pill: View {
    let text: String
    var variant: PillVariant = .neutral

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(variant.fg)
            .padding(.horizontal, 9)
            .padding(.vertical, 3)
            .background(variant.bg)
            .clipShape(Capsule())
    }
}

// MARK: - Section label

struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .bold))
            .tracking(0.8)
            .foregroundStyle(Otter.primary)
    }
}

// MARK: - Stat tile

struct StatTile: View {
    let systemImage: String
    let value: String
    let label: String
    let sub: String
    var hue: PillVariant = .info

    var body: some View {
        Card(padding: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(hue.bg)
                    .frame(width: 32, height: 32)
                Image(systemName: systemImage)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(hue.fg)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(value).font(.system(size: 20, weight: .bold))
                Text(label).font(.system(size: 12, weight: .semibold)).foregroundStyle(Otter.onSurface)
                Text(sub).font(.system(size: 10)).foregroundStyle(Otter.onSurfaceVariant)
            }
        }
    }
}

// MARK: - Button styles

struct OtterFilled: ButtonStyle {
    var tint: Color = Otter.primary
    var fg: Color = Otter.onPrimary
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(fg)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(tint.opacity(configuration.isPressed ? 0.85 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

struct OtterOutlined: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Otter.onSurface)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(configuration.isPressed ? Otter.surfaceVariant : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(Otter.outlineVariant, lineWidth: 1)
            )
    }
}

struct OtterTonal: ButtonStyle {
    var variant: PillVariant = .info
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(variant.fg)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(variant.bg.opacity(configuration.isPressed ? 0.7 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

struct OtterText: ButtonStyle {
    var tint: Color = Otter.primary
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(tint.opacity(configuration.isPressed ? 0.6 : 1))
    }
}

// MARK: - Icon in a rounded tile (for card headers, list rows)

struct IconTile: View {
    let systemImage: String
    var hue: PillVariant = .info
    var size: CGFloat = 32
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
                .fill(hue.bg)
                .frame(width: size, height: size)
            Image(systemName: systemImage)
                .font(.system(size: size * 0.44, weight: .semibold))
                .foregroundStyle(hue.fg)
        }
    }
}
