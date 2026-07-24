package dev.azula.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import kotlinx.coroutines.launch

/**
 * Android: the exact counterpart of [rememberPlatformPhraseSaver]'s
 * `createCredential`. The app asks, the provider raises its own sheet, and the
 * user picks -- rather than the Autofill framework offering inline hints, which
 * would make "no saved phrase" indistinguishable from a broken request. See
 * the change's design.md ("getCredential with an explicit affordance").
 *
 * Note the credential the save path writes has an empty `id`, so entries render
 * without a username in the provider's picker; the provider identifies them by
 * the app (or by `azula.app`, via the `get_login_creds` asset-link relation).
 */
@Composable
actual fun rememberPlatformPhraseFiller(): (((PhraseFillResult) -> Unit) -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { onResult ->
            // CredentialManager needs an Activity to present its sheet, not just a
            // Context. Report Unavailable rather than crashing mid-onboarding if the
            // composition isn't hosted in one -- the user can still paste.
            val activity = context.findActivity()
            if (activity == null) {
                onResult(PhraseFillResult.Unavailable)
            } else {
                scope.launch {
                    val outcome = runCatching {
                        CredentialManager.create(activity).getCredential(
                            context = activity,
                            request = GetCredentialRequest(listOf(GetPasswordOption())),
                        )
                    }
                    onResult(
                        outcome.fold(
                            onSuccess = { response ->
                                // GetPasswordOption can only yield a PasswordCredential, but the
                                // response type is the open Credential -- fall back rather than cast.
                                val password = (response.credential as? PasswordCredential)?.password
                                if (password.isNullOrEmpty()) PhraseFillResult.Unavailable
                                else PhraseFillResult.Filled(password)
                            },
                            // Cancellation is the user dismissing the picker, which must leave
                            // the input untouched. Everything else is Unavailable:
                            // NoCredentialException (an empty vault) is the expected one, and
                            // no-provider, no Play Services on API < 34, and provider errors
                            // all read the same way to the user -- nothing to fill.
                            onFailure = {
                                if (it is GetCredentialCancellationException) PhraseFillResult.Cancelled
                                else PhraseFillResult.Unavailable
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
