package com.jadegenesis.mobile.cognitive

import com.jadegenesis.mobile.brain.PrototypeBrain
import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeRouteKind
import com.jadegenesis.mobile.model.NodeRouteSnapshot
import com.jadegenesis.mobile.model.NodeRouteStatus
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.SelfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservableStateGroundingPolicyTest {
    @Test
    fun networkStateQuestionReplacesInventedMeasurementsWithRuntimeSnapshot() {
        val self = selfModel()
        val generated = BrainResult(
            text = "Le réseau est en 5G à 26 Mbps.",
            backendId = "distributed-local-brain",
            nodeId = "home"
        )

        val decision = ObservableStateGroundingPolicy.apply(
            input = "Explique-moi l’état du réseau entre le Pixel, home et le VPS.",
            result = generated,
            selfModel = self
        )

        assertTrue(decision.applied)
        assertEquals(ObservableStateDomain.NETWORK, decision.domain)
        assertFalse(decision.result.text.contains("26 Mbps"))
        assertTrue(decision.result.text.contains("home — ONLINE"))
        assertTrue(decision.result.text.contains("TAILSCALE ONLINE 743 ms"))
        assertTrue(decision.result.text.contains("VPS-wowjade — ONLINE"))
        assertTrue(decision.result.text.contains("TAILSCALE ONLINE 202 ms"))
        assertTrue(decision.result.text.contains("Débit réseau : non mesuré"))
        assertTrue(decision.result.text.contains("Type d'accès Internet du Pixel : non mesuré"))
    }

    @Test
    fun networkStateQuestionReportsMissingLatencyInsteadOfInventingOne() {
        val self = selfModel(
            homeLatencyMs = null,
            vpsLatencyMs = 202L
        )

        val decision = ObservableStateGroundingPolicy.apply(
            input = "Quel est l'état actuel du réseau et des nœuds ?",
            result = BrainResult(text = "Tout va bien.", nodeId = "home"),
            selfModel = self
        )

        assertTrue(decision.applied)
        assertTrue(decision.result.text.contains("TAILSCALE ONLINE ; latence non mesurée"))
        assertFalse(decision.result.text.contains("743 ms"))
    }

    @Test
    fun nonStateExplanationKeepsGeneratedAnswer() {
        val generated = BrainResult(text = "Tailscale crée un réseau privé entre appareils.")

        val decision = ObservableStateGroundingPolicy.apply(
            input = "Comment fonctionne Tailscale ?",
            result = generated,
            selfModel = selfModel()
        )

        assertFalse(decision.applied)
        assertEquals(generated, decision.result)
    }

    @Test
    fun deviceStateQuestionUsesCapturedDeviceValues() {
        val generated = BrainResult(text = "La batterie est à 42 %.")

        val decision = ObservableStateGroundingPolicy.apply(
            input = "Quel est l'état actuel de la batterie et de la RAM ?",
            result = generated,
            selfModel = selfModel()
        )

        assertTrue(decision.applied)
        assertEquals(ObservableStateDomain.DEVICE, decision.domain)
        assertTrue(decision.result.text.contains("Batterie : 88 %, en charge."))
        assertTrue(decision.result.text.contains("RAM disponible : 6 Go / 8 Go."))
        assertFalse(decision.result.text.contains("42 %"))
    }

    private fun selfModel(
        homeLatencyMs: Long? = 743L,
        vpsLatencyMs: Long? = 202L
    ): SelfModel {
        val prototype = PrototypeBrain()
        return SelfModel(
            identity = JadeIdentity(
                jadeId = "JG-grounding-test",
                version = "0.1.22",
                createdAt = 1L
            ),
            nodeId = "pixel-pixel-10-test",
            device = device(),
            resourceBudget = ResourceBudget(
                mode = ResourceMode.ECO,
                reasons = listOf("test"),
                systemRamReserveGb = 2.0,
                recommendedWorkingSetMb = 256,
                maxParallelTasks = 1,
                heavyBackgroundWorkAllowed = false,
                preferRemoteCompute = true,
                maxTaskSliceSeconds = 20,
                evaluatedAt = 1L
            ),
            activeBrain = prototype.info,
            knownNodes = listOf(
                GenesisNode(
                    nodeId = "pixel",
                    name = "Pixel",
                    kind = NodeKind.PHONE,
                    status = NodeStatus.LOCAL
                ),
                GenesisNode(
                    nodeId = "home",
                    name = "home",
                    kind = NodeKind.PC,
                    status = NodeStatus.ONLINE,
                    routes = listOf(
                        NodeRouteSnapshot(
                            routeId = "home-ts",
                            kind = NodeRouteKind.TAILSCALE,
                            host = "100.64.0.10",
                            port = 8765,
                            status = NodeRouteStatus.ONLINE,
                            latencyMs = homeLatencyMs
                        )
                    ),
                    runtimeVersion = "0.1.9"
                ),
                GenesisNode(
                    nodeId = "vps",
                    name = "VPS-wowjade",
                    kind = NodeKind.VPS,
                    status = NodeStatus.ONLINE,
                    routes = listOf(
                        NodeRouteSnapshot(
                            routeId = "vps-ts",
                            kind = NodeRouteKind.TAILSCALE,
                            host = "100.64.0.20",
                            port = 8765,
                            status = NodeRouteStatus.ONLINE,
                            latencyMs = vpsLatencyMs
                        )
                    ),
                    runtimeVersion = "0.1.9"
                )
            ),
            preferredComputeNodeId = "home",
            capabilities = emptyList(),
            knownLimits = emptyList()
        )
    }

    private fun device(): DeviceProfile = DeviceProfile(
        manufacturer = "Google",
        model = "Pixel 10",
        device = "frankel",
        androidVersion = "17",
        sdkInt = 37,
        socManufacturer = "Google",
        socModel = "Tensor",
        abis = listOf("arm64-v8a"),
        cpuCores = 8,
        ramTotalGb = 8.0,
        ramAvailableGb = 6.0,
        ramLow = false,
        appMemoryClassMb = 512,
        processHeapUsedMb = 120.0,
        processHeapMaxMb = 512.0,
        storageTotalGb = 256.0,
        storageFreeGb = 180.0,
        batteryPercent = 88,
        charging = true,
        powerSaveMode = false,
        deviceIdleMode = false,
        thermalStatus = "NONE",
        capturedAt = 1L
    )
}
