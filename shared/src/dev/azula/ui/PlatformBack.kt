package dev.azula.ui

import androidx.compose.runtime.Composable

/**
 * Handles the platform "back" gesture/button. On Android this intercepts the
 * system back so it navigates within the app instead of exiting; on JVM/iOS it
 * is a no-op (those platforms don't have an Android-style global back button).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
