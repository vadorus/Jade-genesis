# Démarrage partagé — Jade Genesis

> Fiche initiale vérifiée le 14 octobre 2026 ; n'est pas synchronisée automatiquement.

**Branche produit : `main`**. Android Kotlin et nœuds PC/VPS Python : préserver identités, secrets, préférences et appairage Tailscale.

**Travaux repérés le 14/10, statuts à reverifier :**
- PR #51 : nœud Windows et superviseur — https://github.com/vadorus/Jade-genesis/pull/51
- PR #52 : identité des nœuds, file de tâches et écoute réseau — https://github.com/vadorus/Jade-genesis/pull/52
- PR #36 : planner expérimental — https://github.com/vadorus/Jade-genesis/pull/36
- Issue #27 : protocole de preuve d'acquisition/apprentissage — https://github.com/vadorus/Jade-genesis/issues/27

**Validation :** tests Python et Android du module touché, contrôle des logs horodatés, vérification non-régression réseau, test APK uniquement avec accord et sauvegarde. Ne jamais toucher aux nœuds Tailscale PC/VPS vivants ou aux données Pixel personnelles sur la seule base de ce guide. Aucun token dans les PR.

## Instruction de départ commune à chaque IA
1. Lis ce document, `docs/AI_COORDINATION.md` et `AGENTS.md` quand il existe.
2. Vérifie **maintenant** issues, PR (brouillons inclus), branches et derniers commits.
3. Fournis l'inventaire des travaux déjà engagés et les risques de doublon.
4. **Sans attribution par l'utilisateur, ne modifie rien.** Une tâche active doit avoir un seul agent auteur et un autre relecteur.
5. Termine avec le modèle `docs/AI_TASK_TEMPLATE.md` et un compte rendu de tests vérifiables.

Pour les trois dépôts, GitHub est le registre partagé mais il **ne déclenche aucun agent automatiquement**.
