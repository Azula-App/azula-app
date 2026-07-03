package dev.azula.persist

import android.content.Context

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidMessageStore(applicationContext) instead.
actual fun platformMessageStore(): MessageStore? = null

/** Stores each conversation as JSON under filesDir/conversations/<id>.json. */
fun AndroidMessageStore(context: Context): MessageStore = FileMessageStore(context.filesDir)
