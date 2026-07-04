package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.AppScope
import dev.azula.persist.Persona
import dev.azula.persist.ProfileBook
import dev.azula.persist.ProfileStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Owns the user's own personas — the identities offered to a peer when connecting.
 * A first slice carved out of AzulaState: self-contained persona CRUD backed by the
 * injected [ProfileStore]. AzulaState delegates to it (so the UI is unchanged).
 */
@Inject
@SingleIn(AppScope::class)
class PersonaService(private val profileStore: ProfileStore?) {
    /** The saved personas and the default (pre-selected when sharing). */
    val personas = mutableStateListOf<Persona>()
    var defaultPersonaId by mutableStateOf<String?>(null)
        private set

    /** Terminal input mode: smart (swipe/autocorrect/suggestions) vs. the
     *  legacy raw fallback -- see [dev.azula.persist.ProfileBook.terminalSmartInput]. */
    var terminalSmartInput by mutableStateOf(true)
        private set

    fun load() {
        val book = profileStore?.load() ?: return
        personas.clear()
        personas.addAll(book.personas)
        defaultPersonaId = book.defaultId ?: book.personas.firstOrNull()?.id
        terminalSmartInput = book.terminalSmartInput
    }

    private fun save() {
        profileStore?.save(ProfileBook(personas.toList(), defaultPersonaId, terminalSmartInput))
    }

    fun setSmartInput(enabled: Boolean) {
        terminalSmartInput = enabled
        save()
    }

    fun newId(): String {
        var n = personas.size
        while (personas.any { it.id == "persona$n" }) n++
        return "persona$n"
    }

    fun upsert(persona: Persona) {
        val i = personas.indexOfFirst { it.id == persona.id }
        if (i >= 0) personas[i] = persona else personas.add(persona)
        if (defaultPersonaId == null) defaultPersonaId = persona.id
        save()
    }

    fun delete(id: String) {
        personas.removeAll { it.id == id }
        if (defaultPersonaId == id) defaultPersonaId = personas.firstOrNull()?.id
        save()
    }

    fun setDefault(id: String) { defaultPersonaId = id; save() }

    fun byId(id: String?): Persona? = id?.let { pid -> personas.firstOrNull { it.id == pid } }
}
