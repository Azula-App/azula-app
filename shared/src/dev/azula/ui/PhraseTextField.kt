package dev.azula.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

/**
 * The multi-line phrase box's min height and inner padding, as used by
 * `SetupField`.
 *
 * These live here, next to [PhraseTextField], because the iOS actual has to size
 * its interop view to the *same* content area. An interop view has no intrinsic
 * size in Compose's layout, so its height is set explicitly -- and a field
 * shorter than the box it sits in leaves a band of dead pixels that still looks
 * like the input. Tapping there does nothing, which on a phone is most of where
 * a thumb actually lands. Two copies of these numbers would drift into exactly
 * that bug, so there is one copy.
 */
internal val PhraseFieldBoxMinHeight = 84.dp
internal val PhraseFieldBoxPadding = 9.dp

/** The tappable content area inside [PhraseFieldBoxMinHeight]'s box. */
internal val PhraseFieldContentMinHeight = PhraseFieldBoxMinHeight - PhraseFieldBoxPadding * 2

/**
 * The recovery-phrase input, as distinct from every other text field in setup:
 * it is the one field the platform's credential store should be able to fill.
 *
 * On iOS that requires a real native `UITextField` -- Compose's own
 * `KeyboardType.Password` does not trigger Apple's AutoFill heuristics
 * (CMP-5802), which look for an actual UIKit field with a `textContentType`.
 * Android and desktop have no such requirement and use [ComposePhraseTextField],
 * the same styled field the rest of the flow uses; Android's fill instead goes
 * through the explicit [rememberPhraseFiller] affordance.
 *
 * Callers supply the surrounding label/box chrome (see `SetupField`); this is
 * the inner field only.
 */
@Composable
expect fun PhraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier,
)

/**
 * The plain Compose field shared by every setup input, and by [PhraseTextField]
 * on the platforms that don't need native interop. Kept here rather than in
 * `SetupSteps.kt` so there is exactly one styled field: the iOS actual has to
 * colour-match against these values, and a second copy would drift from it.
 */
@Composable
internal fun ComposePhraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 13.sp, lineHeight = 18.sp),
        cursorBrush = SolidColor(AzulaColors.pink),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
            inner()
        },
    )
}
