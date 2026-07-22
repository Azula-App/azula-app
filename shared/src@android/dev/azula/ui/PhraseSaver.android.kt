package dev.azula.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import kotlinx.coroutines.launch

/**
 * The credential's username, empty because a recovery phrase doesn't have one --
 * it's a bare secret, not a login. [CreatePasswordRequest] takes `id` as a
 * required (non-null) parameter, but only the *password* is validated non-empty:
 * verified against the 1.3.0 bytecode, where `"password should not be empty"` is
 * the sole such check in both `CreatePasswordRequest` and `PasswordCredential`,
 * so an empty id also survives the round trip back out on a future read.
 *
 * The provider labels the entry with the app itself (or with `azula.app`, once
 * the `get_login_creds` asset-link relation lands), which is the part that
 * actually identifies it in a vault.
 */
private const val VAULT_ENTRY_ID = ""

@Composable
actual fun rememberPlatformPhraseSaver(): (String, (PhraseSaveResult) -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { phrase, onResult ->
            // CredentialManager needs an Activity to present its sheet, not just a
            // Context. Report UNAVAILABLE (-> clipboard fallback) rather than
            // crashing mid-onboarding if the composition isn't hosted in one.
            val activity = context.findActivity()
            if (activity == null) {
                onResult(PhraseSaveResult.UNAVAILABLE)
            } else {
                scope.launch {
                    val outcome = runCatching {
                        CredentialManager.create(activity).createCredential(
                            context = activity,
                            request = CreatePasswordRequest(id = VAULT_ENTRY_ID, password = phrase),
                        )
                    }
                    onResult(
                        outcome.fold(
                            onSuccess = { PhraseSaveResult.SAVED },
                            // Cancellation is the user declining, which must not fall
                            // back to the clipboard; everything else (no provider
                            // installed, no Play Services on API < 34, a provider
                            // error) is UNAVAILABLE and does.
                            onFailure = {
                                if (it is CreateCredentialCancellationException) PhraseSaveResult.CANCELLED
                                else PhraseSaveResult.UNAVAILABLE
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** Unwraps the ContextWrapper chain to the hosting Activity, or null. */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
