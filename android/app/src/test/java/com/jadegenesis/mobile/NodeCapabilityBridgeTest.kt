package com.jadegenesis.mobile

import com.jadegenesis.mobile.capability.CapabilityRegistry
import com.jadegenesis.mobile.capability.NodeCapabilityBridge
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCapabilityBridgeTest {

    @Test
    fun onlineNodeMarkersBecomeTypedFreeCapabilities() {
        val node = node(
            id = "pc-home",
            status = NodeStatus.ONLINE,
            capabilities = listOf(
                "node_runtime",
                "local_free:ffmpeg-local",
                "local_free:blender-local"
            )
        )

        val discovered = NodeCapabilityBridge.discovered(listOf(node))
        val byId = discovered.associateBy { it.id }

        assertEquals(2, discovered.size)
        assertTrue(byId.getValue("ffmpeg-local").available)
        assertEquals("pc-home", byId.getValue("ffmpeg-local").nodeId)
        assertTrue(byId.getValue("blender-local").available)
    }

    @Test
    fun unknownMarkerIsIgnoredFailClosed() {
        val discovered = NodeCapabilityBridge.discovered(
            listOf(
                node(
                    id = "pc-home",
                    status = NodeStatus.ONLINE,
                    capabilities = listOf("local_free:unknown-paid-magic")
                )
            )
        )

        assertTrue(discovered.isEmpty())
    }

    @Test
    fun offlineNodeDoesNotAdvertiseUsableCapability() {
        val discovered = NodeCapabilityBridge.discovered(
            listOf(
                node(
                    id = "pc-home",
                    status = NodeStatus.OFFLINE,
                    capabilities = listOf("local_free:ffmpeg-local")
                )
            )
        )

        assertTrue(discovered.isEmpty())
    }

    @Test
    fun sameToolCanExistOnSeveralNodes() {
        val registry = CapabilityRegistry()
        NodeCapabilityBridge.populate(
            registry,
            listOf(
                node(
                    id = "pc-a",
                    status = NodeStatus.ONLINE,
                    capabilities = listOf("local_free:ffmpeg-local")
                ),
                node(
                    id = "pc-b",
                    status = NodeStatus.ONLINE,
                    capabilities = listOf("local_free:ffmpeg-local")
                )
            )
        )

        val options = registry.availableFor("media_transcode")
        assertEquals(2, options.size)
        assertEquals(setOf("pc-a", "pc-b"), options.mapNotNull { it.nodeId }.toSet())
    }

    @Test
    fun paidOrInventedCapabilityCannotEnterThroughMarker() {
        val registry = CapabilityRegistry()
        NodeCapabilityBridge.populate(
            registry,
            listOf(
                node(
                    id = "pc-home",
                    status = NodeStatus.ONLINE,
                    capabilities = listOf(
                        "local_free:imaginary-paid-provider"
                    )
                )
            )
        )

        assertNull(registry.select("image_generation"))
    }

    private fun node(
        id: String,
        status: NodeStatus,
        capabilities: List<String>
    ): GenesisNode = GenesisNode(
        nodeId = id,
        name = id,
        kind = NodeKind.PC,
        status = status,
        capabilities = capabilities
    )
}
