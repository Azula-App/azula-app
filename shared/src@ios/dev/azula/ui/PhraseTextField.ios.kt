@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.azula.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.azula.theme.AzulaColors
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSAttributedString
import platform.Foundation.NSSelectorFromString
import platform.Foundation.create
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIControlContentVerticalAlignmentTop
import platform.UIKit.UIControlEventEditingChanged
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIKeyboardTypeASCIICapable
import platform.UIKit.UITextAutocapitalizationType
import platform.UIKit.UITextAutocorrectionType
import platform.UIKit.UITextBorderStyle
import platform.UIKit.UITextContentTypePassword
import platform.UIKit.UITextField
import platform.UIKit.UITextSpellCheckingType
import platform.darwin.NSObject
import androidx.compose.ui.graphics.Color as ComposeColor

/** One line of the 13sp monospace text style [ComposePhraseTextField] uses. */
private val PHRASE_FIELD_HEIGHT = 20.dp

/**
 * iOS: a real `UITextField` embedded in the Compose tree, which is what makes
 * AutoFill offer a saved recovery phrase. Compose's own text field never
 * triggers it -- Apple's heuristics inspect the live UIKit view hierarchy for a
 * field carrying a credential `textContentType`, and a Skia-drawn field isn't
 * there to be found (CMP-5802).
 *
 * `.password` rather than `.newPassword`: this is the *restore* step, reading a
 * credential the back-up step already wrote. `secureTextEntry` stays false --
 * masking 24 words the user is checking against their vault would be actively
 * hostile, and AutoFill engages on the content type without it.
 *
 * Deliberately single-line, unlike the Compose field it replaces: UIKit has no
 * multi-line field with reliable credential AutoFill (`UITextView` takes a
 * `textContentType` but is not what the password heuristics look for), and the
 * realistic ways a 24-word phrase reaches this field are AutoFill and paste,
 * neither of which needs the wrap. Typing it by hand scrolls horizontally.
 *
 * If AutoFill doesn't engage, this degrades to an ordinary field and the user
 * pastes, exactly as before -- a failure here must not break restore.
 */
@Composable
actual fun PhraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier,
) {
    // UIKit delivers edits through target/action, which is outside the composition:
    // capture the latest callback so the observer created once in `factory` never
    // calls a stale one.
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    // Held here because addTarget's reference is weak -- nothing else in ARC keeps
    // the observer alive once `factory` returns, and a deallocated target makes the
    // field silently stop reporting edits.
    val observer = remember { PhraseFieldObserver() }
    observer.onChange = { latestOnValueChange(it) }

    UIKitView(
        factory = {
            UITextField(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
                textContentType = UITextContentTypePassword
                secureTextEntry = false
                borderStyle = UITextBorderStyle.UITextBorderStyleNone
                // The control is as tall as the box (see the height modifier below),
                // but draws one line. Pin that line to the top so it sits exactly
                // where the wrapping Compose field's first line sits on the platforms
                // that wrap -- centring it would move the text on iOS only.
                setContentVerticalAlignment(UIControlContentVerticalAlignmentTop)
                // A recovery phrase is BIP-39 lowercase ASCII: autocorrect and
                // capitalisation would corrupt words that are already correct, and the
                // checksum would then reject a phrase the user filled correctly.
                autocorrectionType = UITextAutocorrectionType.UITextAutocorrectionTypeNo
                autocapitalizationType = UITextAutocapitalizationType.UITextAutocapitalizationTypeNone
                spellCheckingType = UITextSpellCheckingType.UITextSpellCheckingTypeNo
                keyboardType = UIKeyboardTypeASCIICapable
                // Colour-matched to the container rather than transparent: an embedded
                // UIKit view is composited under Compose's own drawing, so a clear
                // background shows whatever is behind the interop layer, not the field's
                // surface. Values come from the theme tokens (surfaceInput / contentHigh
                // in design-system/design.md), never eyeballed -- if the token moves,
                // this moves with it.
                backgroundColor = AzulaColors.inputBg.toUIColor()
                textColor = AzulaColors.text1.toUIColor()
                font = UIFont.monospacedSystemFontOfSize(13.0, UIFontWeightRegular)
                attributedPlaceholder = NSAttributedString.create(
                    string = placeholder,
                    attributes = mapOf<Any?, Any?>(
                        NSForegroundColorAttributeName to AzulaColors.textFaint.toUIColor(),
                    ),
                )
                addTarget(
                    target = observer,
                    action = NSSelectorFromString("editingChanged:"),
                    forControlEvents = UIControlEventEditingChanged,
                )
            }
        },
        // An interop view has no intrinsic size in Compose's layout -- without an
        // explicit height it measures to zero and the field is simply invisible.
        //
        // In the multi-line case it takes the caller box's whole content area rather
        // than the single line it actually draws. The field is still one line
        // (`contentVerticalAlignment = .top` keeps that line where it has always
        // been), but the *control* now covers every pixel that looks like the input.
        // Sizing it to one line instead left ~46dp of the 66dp box inert: it rendered
        // as the field, and taps in it did nothing -- which on a phone is most of
        // where a thumb lands. Verified on the simulator against the restore step.
        modifier = modifier.height(if (singleLine) PHRASE_FIELD_HEIGHT else PhraseFieldContentMinHeight),
        // The Compose half of the two-way sync: AutoFill and paste land here via
        // `editingChanged` -> onValueChange -> state, and anything that sets the state
        // from outside (a filler, a reset) lands back on the field here. The equality
        // guard is what stops the two from fighting: after the user types, the state
        // already matches, so this writes nothing and the caret stays put.
        update = { field ->
            if (field.text != value) field.text = value
        },
        properties = UIKitInteropProperties(
            // A text field must become first responder on the first tap; the default
            // cooperative mode holds touches back to see whether Compose wants them,
            // which reads as a dropped tap on a field.
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            // The interop view *is* the control here, so let VoiceOver reach it
            // directly rather than exposing an empty Compose node over a real field.
            isNativeAccessibilityEnabled = true,
        ),
    )
}

/**
 * Bridges `UIControlEventEditingChanged` back into Compose. [onChange] is a
 * mutable property rather than a constructor argument so the composable can keep
 * it pointed at the current callback without recreating the field.
 */
private class PhraseFieldObserver : NSObject() {
    var onChange: (String) -> Unit = {}

    @ObjCAction
    fun editingChanged(sender: UITextField) {
        onChange(sender.text ?: "")
    }
}

/**
 * Converts a theme [ComposeColor] to its UIKit equivalent, so the native field
 * takes its colours from the same tokens as everything around it. Components are
 * already sRGB floats, which is the space `UIColor(red:green:blue:alpha:)` uses.
 */
private fun ComposeColor.toUIColor(): UIColor = UIColor(
    red = red.toDouble(),
    green = green.toDouble(),
    blue = blue.toDouble(),
    alpha = alpha.toDouble(),
)
