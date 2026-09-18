package com.jadegenesis.mobile.capability

/**
 * Candidate adapters Jade is allowed to discover for the free-first capability
 * fabric. Entries are intentionally unavailable by default: this catalog says
 * what Jade may use, not what is actually installed on a node.
 */
object LocalFreeCapabilityCatalog {

    fun eligibleProviders(): List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(
            id = "ollama-local",
            displayName = "Ollama",
            operations = setOf(
                "text_generation",
                "reasoning",
                "local_model_inference"
            ),
            providerType = CapabilityProviderType.LOCAL_MODEL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Local model runtime. Availability must be probed on the node."
        ),
        CapabilityDescriptor(
            id = "comfyui-local",
            displayName = "ComfyUI",
            operations = setOf(
                "image_generation",
                "image_workflow"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Local image generation workflow engine."
        ),
        CapabilityDescriptor(
            id = "whisper-cpp-local",
            displayName = "whisper.cpp",
            operations = setOf(
                "speech_to_text",
                "audio_transcription"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Offline speech recognition."
        ),
        CapabilityDescriptor(
            id = "piper-local",
            displayName = "Piper",
            operations = setOf(
                "text_to_speech",
                "voice_synthesis"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Offline text-to-speech adapter candidate."
        ),
        CapabilityDescriptor(
            id = "blender-local",
            displayName = "Blender",
            operations = setOf(
                "3d_modeling",
                "3d_rendering",
                "3d_animation"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Local 3D creation and bpy automation."
        ),
        CapabilityDescriptor(
            id = "ffmpeg-local",
            displayName = "FFmpeg",
            operations = setOf(
                "media_transcode",
                "media_assembly",
                "audio_video_mux"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Local media processing and assembly."
        ),
        CapabilityDescriptor(
            id = "krita-local",
            displayName = "Krita",
            operations = setOf(
                "image_editing",
                "digital_painting"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = false,
            details = "Local raster image editing and painting."
        ),
        CapabilityDescriptor(
            id = "playwright-local",
            displayName = "Playwright",
            operations = setOf(
                "browser_automation",
                "web_ui_interaction"
            ),
            providerType = CapabilityProviderType.LOCAL_TOOL,
            costClass = CapabilityCostClass.LOCAL_FREE,
            available = false,
            requiresNetwork = true,
            details = "Local browser automation; network use depends on the target."
        )
    )
}
