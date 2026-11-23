package dev.azula.selfgrant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrantMachineTest {
    private fun run(start: MachineState, vararg events: Event): Pair<MachineState, List<Effect>> {
        var s = start
        val fx = mutableListOf<Effect>()
        for (e in events) GrantMachine.reduce(s, e).also { s = it.state; fx += it.effects }
        return s to fx
    }

    @Test
    fun happyPathFromScratch() {
        val (s, fx) = run(
            MachineState(),
            Event.Start,
            Event.ServiceUp(Service.Pairing, 41111),
            Event.CodeSubmitted("123456"),
            Event.PairingSucceeded,
            Event.ServiceUp(Service.Connect, 42222),
            Event.GrantSucceeded,
        )
        assertEquals(GrantPhase.Granted, s.phase)
        assertTrue(s.paired)
        assertEquals(
            listOf(
                Effect.StartDiscovery(Service.Pairing),
                Effect.ShowCodePrompt(retry = false),
                Effect.Pair(41111, "123456"),
                Effect.ClearCodePrompt,
                Effect.StopDiscovery(Service.Pairing),
                Effect.StartDiscovery(Service.Connect),
                Effect.StopDiscovery(Service.Connect),
                Effect.Grant(42222),
                Effect.ShowDone,
            ),
            fx,
        )
    }

    @Test
    fun startWhenAlreadyPairedSkipsPairing() {
        val (s, fx) = run(MachineState(paired = true), Event.Start)
        assertEquals(GrantPhase.Connecting, s.phase)
        assertEquals(listOf<Effect>(Effect.StartDiscovery(Service.Connect)), fx)
    }

    @Test
    fun badCodeShapeKeepsWaitingAndFlagsRetry() {
        val (s, fx) = run(
            MachineState(),
            Event.Start, Event.ServiceUp(Service.Pairing, 1), Event.CodeSubmitted("12ab"),
        )
        assertEquals(GrantPhase.AwaitingCode, s.phase)
        assertEquals(GrantError.WrongCode, s.error)
        assertEquals(Effect.ShowCodePrompt(retry = true), fx.last())
    }

    @Test
    fun codeIsIgnoredBeforeAServiceIsFound() {
        val (s, fx) = run(MachineState(), Event.Start, Event.CodeSubmitted("123456"))
        assertEquals(GrantPhase.Discovering, s.phase)
        assertEquals(listOf<Effect>(Effect.StartDiscovery(Service.Pairing)), fx)
    }

    @Test
    fun serviceVanishingWhileAwaitingCodeGoesBackToDiscovering() {
        val (s, fx) = run(
            MachineState(),
            Event.Start, Event.ServiceUp(Service.Pairing, 1), Event.ServiceDown(Service.Pairing),
        )
        assertEquals(GrantPhase.Discovering, s.phase)
        assertEquals(Effect.ClearCodePrompt, fx.last())
    }

    @Test
    fun wrongCodeReturnsToDiscoveringAndPromptsAsRetry() {
        val (s, fx) = run(
            MachineState(),
            Event.Start, Event.ServiceUp(Service.Pairing, 1), Event.CodeSubmitted("111111"),
            Event.PairingFailed(wrongCode = true, detail = null),
            Event.ServiceUp(Service.Pairing, 2),
        )
        assertEquals(GrantPhase.AwaitingCode, s.phase)
        assertEquals(2, s.pairingPort)
        assertEquals(Effect.ShowCodePrompt(retry = true), fx.last())
    }

    @Test
    fun forgottenPairingDuringGrantRestartsPairing() {
        val (s, fx) = run(
            MachineState(paired = true),
            Event.Start, Event.ServiceUp(Service.Connect, 9),
            Event.GrantFailed(unpaired = true, detail = "handshake"),
        )
        assertEquals(GrantPhase.Discovering, s.phase)
        assertEquals(false, s.paired)
        assertEquals(Effect.StartDiscovery(Service.Pairing), fx.last())
    }

    @Test
    fun grantRejectionFails() {
        val (s, _) = run(
            MachineState(paired = true),
            Event.Start, Event.ServiceUp(Service.Connect, 9), Event.GrantFailed(unpaired = false, detail = "denied"),
        )
        assertEquals(GrantPhase.Failed, s.phase)
        assertEquals(GrantError.GrantFailed, s.error)
    }

    @Test
    fun cancelKeepsTheKeyRegistration() {
        val (s, _) = run(MachineState(paired = true), Event.Start, Event.Cancel)
        assertEquals(GrantPhase.Idle, s.phase)
        assertTrue(s.paired)
    }

    @Test
    fun resumeRestartsMidFlightPhasesAtDiscovery() {
        for (p in listOf(GrantPhase.Discovering, GrantPhase.AwaitingCode, GrantPhase.Pairing)) {
            val step = GrantMachine.resume(p, paired = false)
            assertEquals(GrantPhase.Discovering, step.state.phase)
            assertEquals(listOf<Effect>(Effect.StartDiscovery(Service.Pairing)), step.effects)
        }
        for (p in listOf(GrantPhase.Connecting, GrantPhase.Granting)) {
            val step = GrantMachine.resume(p, paired = true)
            assertEquals(GrantPhase.Connecting, step.state.phase)
            assertEquals(listOf<Effect>(Effect.StartDiscovery(Service.Connect)), step.effects)
        }
        assertEquals(GrantPhase.Granted, GrantMachine.resume(GrantPhase.Granted, true).state.phase)
        assertEquals(GrantPhase.Idle, GrantMachine.resume(GrantPhase.Idle, false).state.phase)
    }

    @Test
    fun timeoutAndNotificationFailuresAreTerminal() {
        val (a, _) = run(MachineState(), Event.Start, Event.DiscoveryTimedOut)
        assertEquals(GrantError.DiscoveryTimeout, a.error)
        val (b, _) = run(MachineState(), Event.Start, Event.NotificationsBlocked)
        assertEquals(GrantError.NotificationsBlocked, b.error)
        assertEquals(GrantPhase.Failed, b.phase)
    }
}
