# CORRECT-MEM V1 — candidat local, issue #85

Auteur : Codex. Relecteur prévu : Claude, indisponible (quota). Alexandre a
autorisé le 18/10/2026 la poursuite sans Claude et l'édition isolée de JadeCore
et du runtime ; aucune revue indépendante n'est revendiquée. Base :
`c140aff3fc9a313255ae4b93d5f605c66e3d2ee9`. Aucun APK ou déploiement.

## Contrat

Seuls `corrige : <fait juste>` et le bouton **Corriger** créent une correction.
La réponse affichée doit encore correspondre à la référence sélectionnée. Un
échec de nouveau message invalide la référence précédente. `non, …` ne crée
jamais de FACT USER_CORRECTION ; les autres systèmes conversationnels existants
ne sont pas remplacés par ce mécanisme.

Le fait (1500 caractères maximum, sans troncature silencieuse) est un
FACT/USER_CORRECTION de confiance 1.0. Une table privée conserve la question,
la référence/copie de la réponse fautive et les propositions en attente. Une
proposition en conflit n'est pas encore un FACT. La migration Room v2→v3 est
additive ; le chemin v1→v2 reste conservé, sans réinitialisation destructive.
La confiance signifie « fourni explicitement », pas vérité universelle.

Une question identique, un fait identique ou un recouvrement lexical d'au moins
deux termes produit un conflit potentiel. L'écran Mémoire affiche les cibles
et demande confirmation. L'insertion et supersededBy sont transactionnels,
avec revalidation de l'ensemble exact des cibles ; un consentement périmé est
refusé. Fermer/annuler ne remplace rien. Un conflit en attente est signalé et
ses cibles sont exclues du contexte prioritaire. Le lexique ne détecte pas
toutes les contradictions sémantiques.

Le rappel parcourt les corrections actives sans limite par récence, classe le
fait et la question historique par MemoryContextSearch et garde au plus cinq
résultats dans un quota distinct. Le stockage refuse de nouvelles propositions
au-delà de 10000 liens, sans effacer les anciens. Les faits ordinaires ne sont
pas plafonnés par cette limite. Une question exactement répétée est prioritaire ;
les paraphrases et termes courts peuvent échapper au lexique.

## Contexte et oubli

Les PC/VPS reçoivent un tableau `corrections` séparé des dix souvenirs ordinaires,
avec seulement content/source/confiance. Question historique et réponse fausse
ne sont jamais envoyées comme souvenirs. La même section survit aux copies
answer/verify/revise. Le runtime annonce `correct_mem_v1` et ajoute :
« Faits corrigés par Alexandre : ils priment sur tes connaissances. »
Les faits ne deviennent pas des commandes/permissions et ne remplacent pas une
observation runtime actuelle.

Un nœud ancien sans cette capacité n'est pas utilisé pour un tour corrigé ;
le secours Pixel restitue les faits pertinents comme rappel lexical identifié,
sans prétendre à une génération. La présence du contexte ne prouve pas
l'obéissance d'un modèle. Aucun déploiement des nœuds n'est inclus.

`oublie : <fait exact>` ne supprime qu'une correspondance unique ; sinon la
liste Mémoire permet de choisir. L'oubli efface le FACT et les liens/propositions
associés, sans réactiver un ancien fait superseded. Les tours utilisant des
corrections n'alimentent pas ConversationLearning ni les sorties de RuntimeEval ;
les corrections ne passent pas par la consolidation, l'auto-supersession ou la
rétention automatique. Un verrou commun sérialise conversation/rejeu et oubli.
Après l'oubli confirmé, aucune nouvelle requête ne reprend ce fait. Une requête
déjà reçue par un modèle distant ne peut pas être retirée rétroactivement.
La suppression est logique dans SQLite ; aucune promesse d'effacement forensique
des pages libres/sauvegardes externes.

## Examen nocturne et HALT

Le Pixel orchestre le rejeu depuis ses stores privés, dans son cycle nocturne.
Les faits ne sont pas durablement synchronisés individuellement. L'exécution
du modèle utilise uniquement un VPS en ligne annonçant `correct_mem_exam_v1`,
via un appel dédié authentifié ; le modèle `ollama_model` doit être configuré.
Sans ce protocole/modèle, aucun examen n'est soumis. Pas de cycle autonome
équivalent sur le VPS, de SkillSpec, d'outil, de collecte de dataset, de promotion
ou d'apprentissage des réponses d'examen.

Fenêtre Paris : 00:30–06:30. Au plus dix tentatives par date, une par correction
et par nuit, même après retry/forçage/redémarrage. Le quota et une tranche de
temps conservatrice sont persistés avant soumission. Budget global 15 minutes,
tranche au plus 90 secondes incluant la marge des contrôles ; appel du modèle
au plus 86 secondes. Les corrections les moins récemment examinées passent
d'abord. Une nuit sans corrections ne contacte aucun modèle.

`GET /correct-mem/status` projette jade-halt.json en lecture seule : absent →
RUNNING ; illisible, mauvais schéma ou champs manquants → HALTED. Il n'appelle
pas JadeHalt.status() et n'écrit pas de journal. Le Pixel exige un état RUNNING
frais et valide avant chaque appel et avant d'accepter le résultat ; indisponible,
périmé ou inconnu → arrêt. Le serveur contrôle également HALT avant/après appel.
Le bouton local d'arrêt d'examen est durable et coopératif, sans halt/rearm du
VPS ; il empêche les appels suivants et invalide le résultat en cours.

Le transport Android a un délai absolu et coupe sa connexion. Le serveur rend
la main à son échéance, même si le modèle ignore son timeout. Un seul appel
modèle d'examen peut rester en cours ; le créneau reste occupé jusqu'à sa fin,
sa réponse tardive est jetée. Ceci ne prouve pas l'arrêt immédiat du calcul
Ollama. Un échec/timeout n'est pas compté comme répétition d'erreur.

## Indicateur et confidentialité

Extraction déterministe de nombres/unités/dates et mots capitalisés du fait,
visible dans la liste. Correspondances exactes NFC, sensibles à la casse pour
les noms, avec frontières de tokens/nombres : 12 ne vaut ni 112, ni -12, ni
12.5. Aucun élément extractible → non évaluable. Les mots capitalisés peuvent
être des faux positifs ; les paraphrases sont limitées. **La présence textuelle
n'établit ni vérité, ni cohérence, ni absence de négation.** Ce n'est pas une
preuve de non-répétition.

Le snapshot partagé `correction_metrics` contient uniquement comptes et dates :
actives, conflits, tentatives, rejoués, évaluables, reprises, indisponibilités,
interruptions. Pas de texte, identifiant de correction ou empreinte de contenu.
Le cache coalesce ce signal. Le rapport #76 affiche le dernier snapshot, sa
nuit/date et sa péremption, sans additionner les synchronisations ni inventer
une couverture hebdomadaire. Taux = reprises/rejoués uniquement si tous les
rejoués sont évaluables ; sinon inconnu/partiel. Zéro rejeu reste inconnu.

Les endpoints d'examen n'utilisent pas la file de tâches et ne journalisent
aucun texte. Les tours privés distants utilisent l'endpoint synchrone sans
sorties de télémétrie Android et avec erreurs fixes expurgées côté serveur.

## Validation du candidat

Sur Windows Home, Python 3.12 : 327 tests node-agent passent, dont 12 tests
CORRECT-MEM (HALT, prompts, budget absolu, HTTP local authentifié, erreurs
privées, rapport sans contenu et sans écriture).

Kotlin 2.3.21 / Java 17 / JUnit 4.13.2 : 12 tests JVM passent sur les sources
réelles de politique, DAO par défaut, store, moteur d'examen, modèles, payload
et PrototypeBrain ; annotations Room réelles. Le DAO en mémoire du test ne
remplace pas une preuve de transaction Room générée. Deux tests SQLite
exécutent les SQL réels de migration v1→v2→v3 et de sélection : préservation des
faits/index, exclusion des corrections et conflits ; rollback synthétique.

Non vérifiés : génération KSP/validation Room sur Android, compilation complète
de l'application, UI réelle, réseau Pixel→nœuds, obéissance du modèle, reprise
après arrêt de processus réel. Aucun build APK, installation, déploiement,
commit, push ou fusion. Auto-revue Codex uniquement.
