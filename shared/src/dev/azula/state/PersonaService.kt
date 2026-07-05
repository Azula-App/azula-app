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

    /** [ProfileBook.terminalSmartInput] as last loaded from/written to disk --
     *  frozen, not live state. `terminalSmartInput` now lives in `SettingsService`
     *  (see azula-docs/docs/tech-debt.md §6); this snapshot exists only so (a) a
     *  fresh `SettingsService` can migrate a pre-SettingsStore install's value on
     *  first read ([legacyTerminalSmartInput]), and (b) persona saves round-trip
     *  the field unchanged instead of clobbering it back to the default. */
    private var legacyTerminalSmartInput = true

    /** The value [SettingsService] should adopt if it's never saved anything of
     *  its own yet -- see (a) above. */
    val legacyTerminalSmartInputForMigration: Boolean get() = legacyTerminalSmartInput

    fun load() {
        val book = profileStore?.load() ?: return
        personas.clear()
        personas.addAll(book.personas)
        defaultPersonaId = book.defaultId ?: book.personas.firstOrNull()?.id
        legacyTerminalSmartInput = book.terminalSmartInput
    }

    private fun save() {
        profileStore?.save(ProfileBook(personas.toList(), defaultPersonaId, legacyTerminalSmartInput))
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
