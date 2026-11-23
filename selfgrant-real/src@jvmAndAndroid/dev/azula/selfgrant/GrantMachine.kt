package dev.azula.selfgrant

/**
 * The pure state machine of the grant flow. It consumes [Event]s and returns the
 * next [MachineState] plus the [Effect]s the runner must perform; it does no I/O,
 * so every transition is unit-tested on the JVM. The persisted slice is
 * ([MachineState.phase], [MachineState.paired]); [GrantMachine.resume] rebuilds a
 * live state from it after process death.
 */
internal data class MachineState(
    val phase: GrantPhase = GrantPhase.Idle,
    /** True once our adb key is registered with this device's Wireless debugging. */
    val paired: Boolean = false,
    val pairingPort: Int? = null,
    val error: GrantError? = null,
    val detail: String? = null,
)

internal enum class Service { Pairing, Connect }

internal sealed interface Event {
    data object Start : Event
    data object Cancel : Event
    data class ServiceUp(val service: Service, val port: Int) : Event
    data class ServiceDown(val service: Service) : Event
    data class CodeSubmitted(val code: String) : Event
    data object PairingSucceeded : Event
    data class PairingFailed(val wrongCode: Boolean, val detail: String?) : Event
    data object GrantSucceeded : Event

    /** [unpaired]: the device rejected our key (the pairing was forgotten). */
    data class GrantFailed(val unpaired: Boolean, val detail: String?) : Event
    data object DiscoveryTimedOut : Event
    data object NotificationsBlocked : Event
}

internal sealed interface Effect {
    data class StartDiscovery(val service: Service) : Effect
    data class StopDiscovery(val service: Service) : Effect

    /** Show the "enter the 6-digit pairing code" prompt; [retry] when the last code failed. */
    data class ShowCodePrompt(val retry: Boolean) : Effect
    data object ClearCodePrompt : Effect
    data class Pair(val port: Int, val code: String) : Effect

    /** Connect to the `_adb-tls-connect` [port] and run `pm grant`. */
    data class Grant(val port: Int) : Effect
    data object ShowDone : Effect
    data class ShowFailure(val error: GrantError) : Effect
}

internal data class Step(val state: MachineState, val effects: List<Effect> = emptyList())

internal object GrantMachine {
    private val CODE = Regex("^[0-9]{6}$")

    fun reduce(s: MachineState, e: Event): Step = when (e) {
        Event.Start -> when (s.phase) {
            GrantPhase.Idle, GrantPhase.Failed, GrantPhase.Granted -> begin(s)
            else -> Step(s)
        }
        Event.Cancel -> Step(
            MachineState(paired = s.paired),
            listOf(Effect.StopDiscovery(Service.Pairing), Effect.StopDiscovery(Service.Connect), Effect.ClearCodePrompt),
        )
        is Event.ServiceUp -> when {
            e.service == Service.Pairing && s.phase == GrantPhase.Discovering ->
                Step(
                    s.copy(phase = GrantPhase.AwaitingCode, pairingPort = e.port),
                    listOf(Effect.ShowCodePrompt(retry = s.error == GrantError.WrongCode)),
                )
            e.service == Service.Pairing && s.phase == GrantPhase.AwaitingCode ->
                Step(s.copy(pairingPort = e.port))
            e.service == Service.Connect && s.phase == GrantPhase.Connecting ->
                Step(
                    s.copy(phase = GrantPhase.Granting, error = null, detail = null),
                    listOf(Effect.StopDiscovery(Service.Connect), Effect.Grant(e.port)),
                )
            else -> Step(s)
        }
        is Event.ServiceDown -> when {
            e.service == Service.Pairing && s.phase == GrantPhase.AwaitingCode ->
                Step(s.copy(phase = GrantPhase.Discovering, pairingPort = null), listOf(Effect.ClearCodePrompt))
            else -> Step(s)
        }
        is Event.CodeSubmitted -> {
            val code = e.code.trim()
            when {
                s.phase != GrantPhase.AwaitingCode || s.pairingPort == null -> Step(s)
                !CODE.matches(code) -> Step(
                    s.copy(error = GrantError.WrongCode, detail = "the code is 6 digits"),
                    listOf(Effect.ShowCodePrompt(retry = true)),
                )
                else -> Step(
                    s.copy(phase = GrantPhase.Pairing, error = null, detail = null),
                    listOf(Effect.Pair(s.pairingPort, code)),
                )
            }
        }
        Event.PairingSucceeded -> if (s.phase != GrantPhase.Pairing) Step(s) else Step(
            s.copy(phase = GrantPhase.Connecting, paired = true, pairingPort = null, error = null, detail = null),
            listOf(Effect.ClearCodePrompt, Effect.StopDiscovery(Service.Pairing), Effect.StartDiscovery(Service.Connect)),
        )
        is Event.PairingFailed -> if (s.phase != GrantPhase.Pairing) Step(s) else Step(
            s.copy(
                phase = GrantPhase.Discovering,
                pairingPort = null,
                error = if (e.wrongCode) GrantError.WrongCode else GrantError.PairingFailed,
                detail = e.detail,
            ),
            // Restart discovery: the service may still be up, and the runner only
            // reports changes, so without this a retry would never see it again.
            listOf(Effect.ClearCodePrompt, Effect.StartDiscovery(Service.Pairing)),
        )
        Event.GrantSucceeded -> Step(
            s.copy(phase = GrantPhase.Granted, error = null, detail = null),
            listOf(Effect.ShowDone),
        )
        is Event.GrantFailed -> when {
            s.phase != GrantPhase.Granting -> Step(s)
            e.unpaired -> Step(
                s.copy(phase = GrantPhase.Discovering, paired = false, error = GrantError.ConnectFailed, detail = e.detail),
                listOf(Effect.StartDiscovery(Service.Pairing)),
            )
            else -> Step(
                s.copy(phase = GrantPhase.Failed, error = GrantError.GrantFailed, detail = e.detail),
                listOf(Effect.ShowFailure(GrantError.GrantFailed)),
            )
        }
        Event.DiscoveryTimedOut -> Step(
            s.copy(phase = GrantPhase.Failed, error = GrantError.DiscoveryTimeout, detail = null),
            listOf(
                Effect.StopDiscovery(Service.Pairing),
                Effect.StopDiscovery(Service.Connect),
                Effect.ClearCodePrompt,
                Effect.ShowFailure(GrantError.DiscoveryTimeout),
            ),
        )
        Event.NotificationsBlocked -> Step(
            s.copy(phase = GrantPhase.Failed, error = GrantError.NotificationsBlocked, detail = null),
            listOf(Effect.StopDiscovery(Service.Pairing), Effect.ShowFailure(GrantError.NotificationsBlocked)),
        )
    }

    private fun begin(s: MachineState): Step =
        if (s.paired) Step(
            s.copy(phase = GrantPhase.Connecting, error = null, detail = null),
            listOf(Effect.StartDiscovery(Service.Connect)),
        ) else Step(
            s.copy(phase = GrantPhase.Discovering, error = null, detail = null),
            listOf(Effect.StartDiscovery(Service.Pairing)),
        )

    /**
     * Rebuilds a live state from the persisted slice after process death or an
     * app switch. Anything mid-flight restarts at its discovery step: ports and
     * the typed code never outlive the process, but the key and `paired` do.
     *
     * [allHeld] covers the one death the flow causes itself: granting these
     * permissions changes the app's gids, so the system kills the process right
     * after `pm grant`. A mid-flight phase that wakes up to both permissions
     * already held is therefore a finished grant, not one to repeat.
     */
    fun resume(persistedPhase: GrantPhase, paired: Boolean, allHeld: Boolean = false): Step = when (persistedPhase) {
        GrantPhase.Discovering, GrantPhase.AwaitingCode, GrantPhase.Pairing,
        GrantPhase.Connecting, GrantPhase.Granting ->
            if (allHeld) Step(MachineState(phase = GrantPhase.Granted, paired = paired), listOf(Effect.ShowDone))
            else begin(MachineState(paired = paired))
        else -> Step(MachineState(phase = persistedPhase, paired = paired))
    }
}
