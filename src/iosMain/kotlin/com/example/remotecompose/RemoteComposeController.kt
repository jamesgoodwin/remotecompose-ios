package com.example.remotecompose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.example.remotecompose.model.RemoteAction
import com.example.remotecompose.parser.RemoteComposeDocument
import com.example.remotecompose.ui.RemoteComposeCanvas
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.UIKit.UIViewController
import platform.posix.memcpy

/**
 * A `.rc` document as one object a Swift caller can hold: a view controller to put on screen, the
 * host values the document asks to be told, and the actions it sends back.
 *
 * The Kotlin API this wraps is Compose-shaped — `RemoteComposeCanvas` is a `@Composable`, which is
 * not representable in Objective-C and so cannot be called from Swift at all. This is the seam.
 * It stays deliberately thin: everything with a policy in it is in common code, and the Swift
 * package's own `RemoteComposeView` supplies the defaults, because Kotlin's default arguments do
 * not cross the Objective-C boundary and every parameter would otherwise have to be passed at
 * every call site.
 *
 * Not thread-safe, and not meant to be: make it, configure it and read from it on the main thread,
 * which is where UIKit hands you a view controller anyway.
 */
class RemoteComposeController(data: NSData) {

    private val bytes: ByteArray = data.toByteArray()

    /** Set once the canvas has parsed the document; null until then. */
    private var document: RemoteComposeDocument? = null

    /**
     * Values set before the canvas parsed anything, replayed when it has.
     *
     * A caller has the document's bytes before it has a view, and the natural thing to do with
     * `setNamedString` is to call it there. The document cannot exist that early — it has to be
     * parsed with the text measurer that only exists inside composition, or every string in it
     * would be measured by the arithmetic estimate instead of the font — so what is set early is
     * kept here and applied in the order it arrived.
     */
    private val pending = mutableListOf<Pair<String, Any>>()

    /** Which of the document's two palettes to paint. Changing it repaints. */
    var dark: Boolean by mutableStateOf(false)

    /**
     * `HOST_ACTION`: the id the document declared, and the URL it carried, if any.
     *
     * Objective-C blocks cannot be `sealed`, so the [RemoteAction] hierarchy is flattened to its
     * fields here rather than exported as a class Swift would have to switch over.
     */
    var onAction: ((actionId: Int, targetUrl: String?) -> Unit)? = null

    /** `HOST_NAMED_ACTION`: a name and one value — a Float, Int, String, FloatArray, or null. */
    var onNamedAction: ((name: String, value: Any?) -> Unit)? = null

    /** The names this document expects a host to fill in, empty until the first frame is drawn. */
    val namedValues: List<String> get() = document?.namedValues?.keys?.toList() ?: emptyList()

    /**
     * The view controller that draws this document, sized to whatever it is put inside.
     *
     * Call it once and keep the result; each call makes a new controller with its own frame loop.
     */
    fun makeViewController(): UIViewController {
        warnIfFrameRateIsCapped()
        return ComposeUIViewController(
            // Compose Multiplatform otherwise throws at launch when the *host app's* Info.plist
            // has no CADisableMinimumFrameDurationOnPhone, which is a reasonable thing for an app
            // to enforce on itself and not a reasonable thing for a library to do to the app that
            // embedded it. The check is turned off and the cost is reported instead; see
            // [warnIfFrameRateIsCapped].
            configure = { enforceStrictPlistSanityCheck = false },
        ) {
            RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize(),
                dark = dark,
                onAction = { action ->
                    if (action is RemoteAction.Click) onAction?.invoke(action.actionId, action.targetUrl)
                },
                onDocument = { loaded ->
                    document = loaded
                    loaded.onNamedAction = { name, value -> onNamedAction?.invoke(name, value) }
                    for ((name, value) in pending) apply(loaded, name, value)
                    pending.clear()
                },
            )
        }
    }

    /**
     * Says once, on the console, that this app will draw at 60Hz on a display that can do 120.
     *
     * `CADisableMinimumFrameDurationOnPhone` is read from the app's own Info.plist, not the
     * framework's, so no library can set it for you — but a document that animates is exactly the
     * kind of thing the difference shows on.
     */
    private fun warnIfFrameRateIsCapped() {
        if (warnedAboutFrameRate) return
        warnedAboutFrameRate = true
        val entry = NSBundle.mainBundle.objectForInfoDictionaryKey(FRAME_DURATION_KEY) as? NSNumber
        if (entry?.boolValue != true) {
            println(
                "RemoteCompose: add <key>$FRAME_DURATION_KEY</key><true/> to this app's Info.plist " +
                    "to draw at the display's full refresh rate. Without it iOS caps Compose at 60Hz.",
            )
        }
    }

    /**
     * Puts [value] into the float the document named [name].
     *
     * Returns false when the document is on screen and named no such float, so a host pushing a
     * value that has nowhere to go finds out. Before the document is parsed there is nothing to
     * check against, so the value is queued and this returns true.
     */
    fun setNamedFloat(name: String, value: Float): Boolean = set(name, value)

    /** `setNamedIntegerOverride`; see [setNamedFloat] for the return. */
    fun setNamedInteger(name: String, value: Int): Boolean = set(name, value)

    /** `setNamedLongOverride`; see [setNamedFloat] for the return. */
    fun setNamedLong(name: String, value: Long): Boolean = set(name, value)

    /** `setNamedColorOverride`, as 0xAARRGGBB; see [setNamedFloat] for the return. */
    fun setNamedColor(name: String, argb: Int): Boolean = set(name, RemoteColor(argb))

    /** `setNamedStringOverride`; see [setNamedFloat] for the return. */
    fun setNamedString(name: String, value: String): Boolean = set(name, value)

    private fun set(name: String, value: Any): Boolean {
        val loaded = document ?: run {
            pending += name to value
            return true
        }
        return apply(loaded, name, value)
    }

    private fun apply(loaded: RemoteComposeDocument, name: String, value: Any): Boolean = when (value) {
        is Float -> loaded.setNamedFloat(name, value)
        is Int -> loaded.setNamedInteger(name, value)
        is Long -> loaded.setNamedLong(name, value)
        is RemoteColor -> loaded.setNamedColor(name, value.argb)
        is String -> loaded.setNamedString(name, value)
        else -> false
    }

    /** Distinguishes a colour from the Int it is packed in, so [apply] can tell them apart. */
    private class RemoteColor(val argb: Int)

    private companion object {
        const val FRAME_DURATION_KEY = "CADisableMinimumFrameDurationOnPhone"
        var warnedAboutFrameRate = false
    }
}

/**
 * Copies an `NSData` into a Kotlin `ByteArray`.
 *
 * The Swift side of this package deals in `Data`, which is what a file read or a network response
 * hands you; `ByteArray` crosses to Swift as `KotlinByteArray`, which nothing else produces.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
