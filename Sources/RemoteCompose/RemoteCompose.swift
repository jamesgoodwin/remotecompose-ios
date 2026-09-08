import SwiftUI
import UIKit
import RemoteComposeShared

/// A RemoteCompose document, held by whoever is showing it.
///
/// Make one when you have the bytes, hand it to a ``RemoteComposeView``, and use it to fill in the
/// values the document asks for and to hear about the actions it sends back. A document that needs
/// nothing from its host does not need one of these — ``RemoteComposeView/init(data:onAction:)``
/// makes its own.
///
/// Hold it somewhere that outlives a view update — `@StateObject` in SwiftUI, a stored property in
/// a view controller. Use it from the main thread.
public final class RemoteComposeDocument: ObservableObject {

    let controller: RemoteComposeController

    /// Reads `data` as a `.rc` document. Nothing is parsed until the document is first shown, so
    /// this does not fail on bytes that turn out to be malformed; a document that cannot be parsed
    /// draws nothing.
    public init(data: Data) {
        controller = RemoteComposeController(data: data)
    }

    /// The names this document expects to be told, once it has been shown at least once.
    ///
    /// Empty before the first frame: the names come out of the document itself, and it is parsed
    /// when it is first drawn. Values set before then are kept and applied when it is.
    public var names: [String] { controller.namedValues }

    /// What the document does when it runs a host action — the id it declared, and the URL it
    /// carried, if any.
    public var onAction: ((RemoteComposeAction) -> Void)? {
        didSet {
            guard let onAction else {
                controller.onAction = nil
                return
            }
            controller.onAction = { id, target in
                onAction(RemoteComposeAction(id: id.int32Value, url: target.flatMap(URL.init(string:))))
            }
        }
    }

    /// Puts a float into one of the document's named slots.
    ///
    /// Returns `false` if the document has been parsed and named no such value, or named it as a
    /// different kind — so a host pushing a value that has nowhere to go finds out rather than
    /// being quietly ignored. Before the first frame there is nothing to check against, so the
    /// value is queued and this returns `true`.
    ///
    /// These are five names rather than one overloaded `set`, because `set("n", to: 23)` with
    /// overloads on `Float`, `Int32` and `Int64` is ambiguous: an integer literal in Swift resolves
    /// to `Int` by default, and none of the three is `Int`. Named methods take the literal without
    /// a cast at the call site.
    @discardableResult
    public func setFloat(_ name: String, _ value: Float) -> Bool { controller.setNamedFloat(name: name, value: value) }

    /// Puts a string into one of the document's named slots. See ``setFloat(_:_:)`` for the return.
    @discardableResult
    public func setString(_ name: String, _ value: String) -> Bool { controller.setNamedString(name: name, value: value) }

    /// Puts a 32-bit integer into one of the document's named slots. See ``setFloat(_:_:)``.
    @discardableResult
    public func setInteger(_ name: String, _ value: Int32) -> Bool { controller.setNamedInteger(name: name, value: value) }

    /// Puts a 64-bit integer into one of the document's named slots. See ``setFloat(_:_:)``.
    @discardableResult
    public func setLong(_ name: String, _ value: Int64) -> Bool { controller.setNamedLong(name: name, value: value) }

    /// Puts a colour into one of the document's named slots, as `0xAARRGGBB`. See ``setFloat(_:_:)``.
    @discardableResult
    public func setColor(_ name: String, argb: Int32) -> Bool { controller.setNamedColor(name: name, argb: argb) }

    /// A view controller drawing this document, for UIKit. Make it once and keep it; each call
    /// starts its own frame loop.
    public func makeViewController() -> UIViewController { controller.makeViewController() }
}

/// A host action a document ran: the id the document's author gave it, and the URL it carried.
public struct RemoteComposeAction: Hashable, Sendable {
    /// The id declared by the document, which is how its author tells one action from another.
    public let id: Int32
    /// The URL the action carried, when it carried one that parses.
    public let url: URL?
}

/// Draws a RemoteCompose document, scaled to fit the space it is given without distortion.
///
/// ```swift
/// RemoteComposeView(data: try Data(contentsOf: url))
///     .ignoresSafeArea()
/// ```
///
/// Gestures, animation and the frame loop are handled inside: a document that scrolls follows a
/// finger, one that animates keeps its own time, and one that does neither is drawn once. The
/// document carries a palette for each of light and dark, and this follows the environment's
/// `colorScheme`.
public struct RemoteComposeView: UIViewControllerRepresentable {

    @Environment(\.colorScheme) private var colorScheme

    private let data: Data?
    private let provided: RemoteComposeDocument?
    private let onAction: ((RemoteComposeAction) -> Void)?

    /// Draws the document in `data`, making and keeping a ``RemoteComposeDocument`` for it.
    ///
    /// Use this when nothing needs to be pushed into the document after it is on screen. When
    /// something does, make the document yourself and use ``init(document:)``.
    public init(data: Data, onAction: ((RemoteComposeAction) -> Void)? = nil) {
        self.data = data
        self.provided = nil
        self.onAction = onAction
    }

    /// Draws a document you are holding, so you can set its named values and read its actions.
    public init(document: RemoteComposeDocument) {
        self.data = nil
        self.provided = document
        self.onAction = nil
    }

    /// Keeps the document alive across the view struct being rebuilt, which SwiftUI does freely.
    public final class Coordinator {
        let document: RemoteComposeDocument
        init(document: RemoteComposeDocument) { self.document = document }
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(document: provided ?? RemoteComposeDocument(data: data ?? Data()))
    }

    public func makeUIViewController(context: Context) -> UIViewController {
        let document = context.coordinator.document
        if let onAction { document.onAction = onAction }
        document.controller.dark = colorScheme == .dark
        return document.makeViewController()
    }

    public func updateUIViewController(_ controller: UIViewController, context: Context) {
        context.coordinator.document.controller.dark = colorScheme == .dark
    }
}
