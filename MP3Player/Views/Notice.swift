import SwiftUI
import UIKit

/// Kurzer Hinweis nach einer Aktion („Als Nächstes“, „Zu Lieblingssongs hinzugefügt“ …), wie in den Apple-Apps.
@MainActor
final class NoticeCenter: ObservableObject {
    static let shared = NoticeCenter()

    struct Message: Identifiable, Equatable {
        let id = UUID()
        let text: String
        let symbol: String
    }

    @Published private(set) var current: Message?
    private var hideTask: Task<Void, Never>?

    /// Wie lange ein Hinweis stehen bleibt (in automatischen Tests länger, damit er sicher zu sehen ist)
    private let seconds: Double = ProcessInfo.processInfo.arguments.contains("-uiTestReset") ? 6 : 2.4

    func show(_ text: String, symbol: String) {
        hideTask?.cancel()
        let message = Message(text: text, symbol: symbol)
        current = message
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        let wait = UInt64(seconds * 1_000_000_000)
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: wait)
            guard !Task.isCancelled, let self else { return }
            if self.current?.id == message.id { self.current = nil }
        }
    }
}

enum Notice {
    /// Zeigt den Hinweis kurz oben auf dem Bildschirm
    @MainActor
    static func show(_ text: String, symbol: String = "checkmark.circle.fill") {
        NoticeCenter.shared.show(text, symbol: symbol)
    }
}

private struct NoticeHost: ViewModifier {
    @ObservedObject private var center = NoticeCenter.shared

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if let message = center.current {
                    Label(message.text, systemImage: message.symbol)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.regularMaterial, in: Capsule())
                        .overlay(Capsule().stroke(Color.primary.opacity(0.08), lineWidth: 0.5))
                        .shadow(color: .black.opacity(0.25), radius: 12, y: 4)
                        .padding(.horizontal, 24)
                        .padding(.top, 6)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("notice")
                        .allowsHitTesting(false)
                        .id(message.id)
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.85), value: center.current)
    }
}

extension View {
    /// Zeigt Hinweise oben in dieser Ansicht
    func noticeHost() -> some View { modifier(NoticeHost()) }
}
