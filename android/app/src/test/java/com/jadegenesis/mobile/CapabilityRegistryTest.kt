package com.jadegenesis.mobile

import com.jadegenesis.mobile.capability.CapabilityCostClass
import com.jadegenesis.mobile.capability.CapabilityDescriptor
import com.jadegenesis.mobile.capability.CapabilityProviderType
import com.jadegenesis.mobile.capability.CapabilityRegistry
import com.jadegenesis.mobile.capability.CapabilitySelectionPolicy
import com.jadegenesis.mobile.capability.LocalFreeCapabilityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {

    @Test
    fun defaultPolicyPrefersLocalFreeOverCloudFree() {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                id = "cloud",
                cost = CapabilityCostClass.CLOUD_FREE,
                available = true,
                requiresNetwork = true
            )
        )
        registry.register(
            descriptor(
                id = "local",
                cost = CapabilityCostClass.LOCAL_FREE,
                available = true
            )
        )

        assertEquals("local", registry.select("image_generation")?.id)
    }

    @Test
    fun defaultPolicyUsesCloudFreeOnlyAsFallback() {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                id = "cloud",
                cost = CapabilityCostClass.CLOUD_FREE,
                available = true,
                requiresNetwork = true
            )
        )

        assertEquals("cloud", registry.select("image_generation")?.id)
    }

    @Test
    fun defaultPolicyNeverSelectsPaidProvider() {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                id = "paid",
                cost = CapabilityCostClass.PAID,
                available = true,
                requiresNetwork = true
            )
        )

        assertNull(registry.select("image_generation"))
        assertTrue(registry.availableFor("image_generation").isEmpty())
    }

    @Test
    fun cloudFreeFallbackCanBeDisabled() {
        val registry = CapabilityRegistry {
            CapabilitySelectionPolicy(
                preferLocalFree = true,
                allowCloudFreeFallback = false,
                allowPaidProviders = false
            )
        }
        registry.register(
            descriptor(
                id = "cloud",
                cost = CapabilityCostClass.CLOUD_FREE,
                available = true,
                requiresNetwork = true
            )
        )

        assertNull(registry.select("image_generation"))
    }

    @Test
    fun unavailableLocalProviderIsNeverSelected() {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                id = "local-offline",
                cost = CapabilityCostClass.LOCAL_FREE,
                available = false
            )
        )

        assertNull(registry.select("image_generation"))
    }

    @Test
    fun localCatalogIsFreeAndUnavailableUntilRealProbe() {
        val catalog = LocalFreeCapabilityCatalog.eligibleProviders()

        assertTrue(catalog.isNotEmpty())
        assertTrue(
            catalog.all {
                it.costClass == CapabilityCostClass.LOCAL_FREE
            }
        )
        assertFalse(catalog.any { it.available })
        assertTrue(catalog.any { it.id == "ollama-local" })
        assertTrue(catalog.any { it.id == "comfyui-local" })
        assertTrue(catalog.any { it.id == "blender-local" })
        assertTrue(catalog.any { it.id == "ffmpeg-local" })
    }

    private fun descriptor(
        id: String,
        cost: CapabilityCostClass,
        available: Boolean,
        requiresNetwork: Boolean = false
    ): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        displayName = id,
        operations = setOf("image_generation"),
        providerType = CapabilityProviderType.LOCAL_TOOL,
        costClass = cost,
        available = available,
        requiresNetwork = requiresNetwork
    )
}
