package dev.azula.selfgrant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "SelfGrant"
private const val PAIRING_TYPE = "_adb-tls-pairing._tcp."
private const val CONNECT_TYPE = "_adb-tls-connect._tcp."

/** How long the flow waits for the user (Wireless debugging, pairing dialog, code) before giving up. */
private const val PATIENCE_MS = 10 * 60 * 1000L

/**
 * Interprets [GrantMachine]: feeds it events one at a time, persists the slice
 * that must survive process death, and performs its effects (mDNS discovery,
 * notifications, the pairing exchange, `pm grant`). One per process, shared by the
 * service, the reply receiver and the app-facing granter.
 */
internal class SelfGrantRunner(
    private val context: Context,
    private val store: SelfGrantStore,
    private val notifier: SelfGrantNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(GrantState(missing = missing()))
    val state: StateFlow<GrantState> = _state

    private var machine = MachineState(phase = store.phase.takeIf { it == GrantPhase.Granted || it == GrantPhase.Failed } ?: GrantPhase.Idle, paired = store.paired)
    private val discoveries = HashMap<Service, Job>()
    private var patience: Job? = null
    private var begun = false

    /** Invoked after each state change; the service uses it to stop itself when the flow ends. */
    @Volatile var onStateChanged: ((GrantState) -> Unit)? = null

    init {
        scope.launch { for (e in events) handle(e) }
    }

    fun missing(): Set<VmPermission> =
        VmPermission.entries.filter { context.checkSelfPermission(it.androidName) != PackageManager.PERMISSION_GRANTED }.toSet()

    fun refresh() {
        val m = missing()
        _state.value = _state.value.copy(missing = m).let {
            if (m.isEmpty() && it.phase != GrantPhase.Granted) it.copy(phase = GrantPhase.Granted, error = null, detail = null) else it
        }
    }

    /** Starts the flow, or resumes a persisted mid-flight one after process death. Idempotent. */
    @Synchronized
    fun startOrResume() {
        val allHeld = missing().isEmpty()
        val persisted = store.phase
        val midFlight = persisted in setOf(
            GrantPhase.Discovering, GrantPhase.AwaitingCode, GrantPhase.Pairing,
            GrantPhase.Connecting, GrantPhase.Granting,
        )
        if (allHeld && !midFlight) { refresh(); return }
        if (begun) return
        begun = true
        Log.d(TAG, "startOrResume persisted=$persisted paired=${store.paired} allHeld=$allHeld")
        if (midFlight) {
            val step = GrantMachine.resume(persisted, store.paired, allHeld)
            machine = step.state
            if (machine.phase == GrantPhase.Granted) begun = false
            commit(step.effects)
        } else {
            events.trySend(Event.Start)
        }
    }

    fun submitCode(code: String) { events.trySend(Event.CodeSubmitted(code)) }

    fun cancel() { begun = false; events.trySend(Event.Cancel) }

    fun notificationsBlocked() { events.trySend(Event.NotificationsBlocked) }

    private fun handle(e: Event) {
        Log.d(TAG, "event $e in ${machine.phase}")
        val step = GrantMachine.reduce(machine, e)
        machine = step.state
        if (e is Event.Cancel || machine.phase == GrantPhase.Granted || machine.phase == GrantPhase.Failed) begun = false
        commit(step.effects)
    }

    private fun commit(effects: List<Effect>) {
        store.phase = machine.phase
        store.paired = machine.paired
        val s = GrantState(machine.phase, missing(), machine.error, machine.detail)
        _state.value = s
        effects.forEach(::perform)
        onStateChanged?.invoke(s)
    }

    private fun perform(effect: Effect) {
        Log.d(TAG, "effect $effect")
        when (effect) {
            is Effect.StartDiscovery -> {
                discoveries.remove(effect.service)?.cancel()
                val type = if (effect.service == Service.Pairing) PAIRING_TYPE else CONNECT_TYPE
                discoveries[effect.service] = scope.launch {
                    discoverAdbService(context, type).distinctUntilChanged().collect { port ->
                        events.send(if (port != null) Event.ServiceUp(effect.service, port) else Event.ServiceDown(effect.service))
                    }
                }
                armPatience()
                notifier.waiting(effect.service, retry = machine.error == GrantError.WrongCode)
            }
            is Effect.StopDiscovery -> discoveries.remove(effect.service)?.cancel()
            is Effect.ShowCodePrompt -> notifier.codePrompt(effect.retry)
            Effect.ClearCodePrompt -> Unit
            is Effect.Pair -> {
                patience?.cancel()
                notifier.working("pairing…")
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { AdbTlsClient(store.adbKey()).pair(effect.port, effect.code, keyName()) }
                    events.send(
                        result.fold(
                            onSuccess = { Event.PairingSucceeded },
                            onFailure = {
                                Log.w(TAG, "pairing failed", it)
                                Event.PairingFailed(it is PairingProtocol.WrongCodeException, it.message)
                            },
                        ),
                    )
                }
            }
            is Effect.Grant -> {
                patience?.cancel()
                notifier.working("granting…")
                scope.launch(Dispatchers.IO) { events.send(grant(effect.port)) }
            }
            Effect.ShowDone -> { patience?.cancel(); notifier.done() }
            is Effect.ShowFailure -> { patience?.cancel(); notifier.failure(effect.error) }
        }
    }

    private fun armPatience() {
        patience?.cancel()
        patience = scope.launch {
            delay(PATIENCE_MS)
            if (machine.phase in setOf(GrantPhase.Discovering, GrantPhase.AwaitingCode, GrantPhase.Connecting)) {
                events.send(Event.DiscoveryTimedOut)
            }
        }
    }

    private fun grant(port: Int): Event {
        val pkg = context.packageName
        val cmd = VmPermission.entries.joinToString("; ") { "pm grant $pkg ${it.androidName}" }
        return try {
            val out = AdbTlsClient(store.adbKey()).shell(port, cmd)
            Log.d(TAG, "pm grant output: ${out.trim()}")
            val still = missing()
            if (still.isEmpty()) Event.GrantSucceeded
            else Event.GrantFailed(unpaired = false, detail = "still missing ${still.joinToString { it.name }}: ${out.trim()}")
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "tls failed, treating as unpaired", e)
            Event.GrantFailed(unpaired = true, detail = e.message)
        } catch (e: Exception) {
            Log.w(TAG, "grant failed", e)
            Event.GrantFailed(unpaired = false, detail = e.message)
        }
    }

    private fun keyName() = "azula@${Build.MODEL}".replace(' ', '-')
}
