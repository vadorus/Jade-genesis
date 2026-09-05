package com.jadegenesis.mobile.selfmodel

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.Capability
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.SelfModel

class SelfModelBuilder {

    fun build(
        identity: JadeIdentity,
        nodeId: String,
        device: DeviceProfile,
        resourceBudget: ResourceBudget,
        activeBrain: BrainInfo,
        knownNodes: List<GenesisNode>,
        preferredComputeNodeId: String?,
        toolNames: List<String>
    ): SelfModel {
        val remoteNodes = knownNodes.filter { it.kind != NodeKind.PHONE }
        val onlineRemote = remoteNodes.filter { it.status == NodeStatus.ONLINE }
        val pcNodes = knownNodes.filter { it.kind == NodeKind.PC }
        val vpsNodes = knownNodes.filter { it.kind == NodeKind.VPS }
        val onlinePc = pcNodes.any { it.status == NodeStatus.ONLINE }
        val onlineVps = vpsNodes.any { it.status == NodeStatus.ONLINE }
        val onlineTaskNode = onlineRemote.any {
            "task_execution_v3" in it.capabilities
        }
        val onlineGenericTaskNode = onlineRemote.any {
            "task_execution_v3" in it.capabilities &&
                "text_analysis" in it.capabilities &&
                "genesis_probe" in it.capabilities
        }
        val onlineConsolidationNode = onlineRemote.any {
            "task_execution_v3" in it.capabilities &&
                "memory_consolidation" in it.capabilities
        }
        val onlineLocalBrainNode = onlineRemote.any {
            "task_execution_v3" in it.capabilities &&
                "local_brain" in it.capabilities &&
                "brain_chat" in it.capabilities
        }
        val onlineAsyncNode = onlineRemote.any {
            "async_tasks_v1" in it.capabilities
        }
        val multiRouteNode = remoteNodes.any { it.routes.size > 1 }
        val runtimeManagedNode = onlineRemote.any {
            "runtime_manager_v1" in it.capabilities
        }

        val onlineScreenNode = onlineRemote.any {
            "screen_analyze" in it.capabilities
        }
        val onlineVisionNode = onlineRemote.any {
            "vision_analyze" in it.capabilities
        }

        val capabilities = listOf(
            Capability("persistent_identity", true, "DataStore"),
            Capability("local_memory", true, "Room 3"),
            Capability(
                "memory_lifecycle",
                true,
                "MemoryLifecycleManager 0.0.7",
                "Empreinte les sources, bloque les consolidations identiques et conserve les contradictions comme signaux à vérifier."
            ),
            Capability(
                "device_inspection",
                "inspect_device" in toolNames,
                "ToolRegistry"
            ),
            Capability(
                "resource_governor",
                true,
                "ResourceGovernor",
                "Adapte le budget aux ressources réelles du Pixel."
            ),
            Capability(
                "device_registry",
                true,
                "DeviceRegistry v2",
                "Les nœuds et leurs secrets d'appairage restent enregistrés ; l'ancien registre 0.0.x est migré automatiquement."
            ),
            Capability(
                "multi_route_nodes",
                true,
                if (multiRouteNode) "MultiRoute_active" else "MultiRoute_ready",
                "Un même nœud peut avoir plusieurs chemins, notamment LAN et Tailscale, avec sélection par disponibilité et latence."
            ),
            Capability(
                "on_demand_connectivity",
                true,
                "NodeManager probe-on-demand",
                "Jade sonde les routes quand une tâche en a besoin plutôt que maintenir des sockets permanentes."
            ),
            Capability(
                "compute_mesh",
                onlineTaskNode,
                if (onlineTaskNode) "ComputeMesh fan-out v1" else "ComputeMesh_waiting_for_nodes",
                "Les tâches parallélisables peuvent être distribuées simultanément à plusieurs nœuds compatibles."
            ),
            Capability(
                "brain_backend_router",
                true,
                "BrainRouter 0.1.1",
                "Les modèles sont des ressources interchangeables ; l'identité Jade reste dans le Core."
            ),
            Capability(
                "cognitive_core",
                true,
                "CognitiveCore 0.1.2",
                "Boucle exécutive observable : observer, planifier, exécuter, vérifier si nécessaire, réviser, enregistrer l'expérience."
            ),
            Capability(
                "reflection_engine",
                activeBrain.backendType != BrainBackendType.PROTOTYPE,
                if (activeBrain.backendType != BrainBackendType.PROTOTYPE) {
                    "Cognitive verifier"
                } else {
                    "waiting_for_generative_backend"
                },
                "Les requêtes complexes peuvent recevoir une seconde passe de contrôle structurée sans stocker de chaîne de pensée privée."
            ),
            Capability(
                "learning_candidates",
                true,
                "LearningEngine v1",
                "Les échecs et latences répétées produisent des candidats d'amélioration mesurables ; ils ne sont pas appliqués automatiquement."
            ),
            Capability(
                "task_router",
                true,
                "AdaptiveTaskRouter + DeviceRegistry v2",
                "Classe les nœuds par ressources, capacité et historique, puis utilise les routes enregistrées."
            ),
            Capability(
                "task_ledger",
                true,
                "TaskLedger",
                "Conserve succès, durées, échecs et fallbacks."
            ),
            Capability(
                "task_queue",
                true,
                "PersistentTaskQueue",
                "Suit PENDING/RUNNING/COMPLETED/FAILED côté Android."
            ),
            Capability(
                "async_remote_tasks",
                onlineAsyncNode,
                if (onlineAsyncNode) "NodeRuntime async_tasks_v1" else "legacy_sync_runtime",
                "brain_chat peut continuer sur le nœud sans maintenir une socket HTTP ouverte pendant toute la génération."
            ),
            Capability(
                "memory_consolidation",
                true,
                if (onlineConsolidationNode) {
                    "DistributedMemoryConsolidation"
                } else {
                    "LocalMemoryConsolidation"
                }
            ),
            Capability(
                "distributed_execution",
                onlineTaskNode,
                if (onlineTaskNode) "TaskRouter_remote_ready_v3" else "local_fallback_only"
            ),
            Capability(
                "generic_task_runtime",
                onlineGenericTaskNode,
                if (onlineGenericTaskNode) "NodeRuntime_ready" else "runtime_upgrade_or_node_required"
            ),
            Capability(
                "distributed_local_brain",
                onlineLocalBrainNode,
                if (onlineLocalBrainNode) "DistributedLocalBrain + Ollama" else "PrototypeBrain_fallback",
                "Tout PC/VPS qui annonce local_brain + brain_chat peut devenir une ressource générative."
            ),
            Capability(
                "generative_ai",
                activeBrain.backendType != BrainBackendType.PROTOTYPE,
                activeBrain.displayName,
                if (activeBrain.backendType != BrainBackendType.PROTOTYPE) {
                    "Backend génératif actif."
                } else {
                    "Cerveau de secours à règles actif."
                }
            ),
            Capability(
                "diagnostics",
                true,
                "DiagnosticLogger 0.1.2",
                "Journal local rotatif, secrets masqués et bundle de diagnostic générable depuis le mode Admin."
            ),
            Capability(
                "admin_mode",
                true,
                "Local PIN gate",
                "Les détails de diagnostic sont séparés de l'interface normale."
            ),
            Capability(
                "runtime_manager",
                runtimeManagedNode,
                if (runtimeManagedNode) "RuntimeManager protocol ready" else "legacy_runtime_detected",
                "La version et le canal des runtimes sont suivis. L'exécution automatique des mises à jour reste volontairement désactivée dans cette première V0.1."
            ),
            Capability(
                "screen_observer",
                true,
                if (onlineScreenNode || onlineVisionNode) "ScreenObserver_v1_1_grounded" else "ScreenObserver_capture_ready_waiting_for_vision_runtime",
                "Le Pixel attend la disparition de l'overlay MediaProjection avant capture et demande une analyse factuelle en français. Le runtime PC 0.1.1 reste compatible."
            ),
            Capability(
                "vision_analysis",
                onlineVisionNode,
                if (onlineVisionNode) "Ollama_vision_node" else "waiting_for_vision_model",
                "Les images ne sont analysées que par un nœud qui annonce explicitement vision_analyze."
            ),
            Capability(
                "tool_lab",
                true,
                "ToolLab v1 candidate-only",
                "Jade peut concevoir et versionner des outils candidats avec manifeste de permissions et revue statique ; l'activation/exécution automatique reste bloquée."
            ),
            Capability("microphone", false, "not_requested_yet"),
            Capability("camera", false, "not_requested_yet"),
            Capability(
                "pc_node",
                onlinePc,
                if (onlinePc) "DeviceRegistry_online" else if (pcNodes.isNotEmpty()) {
                    "DeviceRegistry_registered_offline"
                } else {
                    "DeviceRegistry_no_pc"
                }
            ),
            Capability(
                "vps_node",
                onlineVps,
                if (onlineVps) "DeviceRegistry_online" else if (vpsNodes.isNotEmpty()) {
                    "DeviceRegistry_registered_offline"
                } else {
                    "DeviceRegistry_no_vps"
                }
            )
        )

        val limits = mutableListOf(
            "Le Cognitive Core 0.1.1 orchestre et vérifie les modèles, mais ce n'est pas encore une auto-évolution complète de son logiciel ou de ses poids.",
            "LearningEngine v1 produit des candidats à partir de mesures ; une amélioration importante doit encore être testée et validée avant promotion.",
            "Compute Mesh v1 sait fan-out des tâches indépendantes ; il ne fusionne pas physiquement plusieurs machines en une seule mémoire GPU.",
            "Runtime Manager v1 expose version/canal/état et prépare stable/candidate, mais n'installe pas encore seul un nouveau binaire distant.",
            "La protection Admin utilise un PIN local dans cette V0.1 ; l'intégration biométrique pourra la remplacer.",
            "Le runtime conserve une liste blanche stricte. Screen Observer utilise seulement des tâches dédiées ; aucune commande shell arbitraire n'est exposée à distance.",
            "Tool Lab v1 ne peut pas encore activer ou exécuter seul un outil candidat : une étape de sandbox et promotion contrôlée reste nécessaire.",
            "Memory Lifecycle 0.0.7 ne supprime jamais automatiquement une mémoire contradictoire ou obsolète.",
            "Tailscale fournit le transport privé quand il est actif ; Jade ne contourne pas un réseau absent."
        )

        if (!onlineLocalBrainNode) {
            limits.add(
                0,
                "Aucun nœud génératif n'est actuellement disponible : Jade utilise son cerveau de secours jusqu'au retour d'un PC/VPS avec Ollama."
            )
        }
        if (onlineRemote.isEmpty()) {
            limits.add("Aucun nœud distant n'est actuellement en ligne.")
        }

        return SelfModel(
            identity = identity,
            nodeId = nodeId,
            device = device,
            resourceBudget = resourceBudget,
            activeBrain = activeBrain,
            knownNodes = knownNodes,
            preferredComputeNodeId = preferredComputeNodeId,
            capabilities = capabilities,
            knownLimits = limits
        )
    }
}
