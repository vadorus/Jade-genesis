# Jade Genesis 0.1.18 — Persistent Strategy Registry

## Objectif

La 0.1.18 introduit le premier substrat adaptatif persistant appartenant à Jade : le **Strategy Registry**.

La 0.1.17 savait déjà transformer des Outcome Quality agrégés en hypothèses, expériences bornées et `STRATEGY_HINT`. Ces candidats restaient toutefois contenus dans le snapshot du Night Learning. La 0.1.18 leur donne une mémoire durable, versionnée et liée à l'identité logique de Jade.

Ce registre n'est pas un nouveau LLM et ne transforme pas un modèle externe en identité de Jade. Les modèles génératifs restent des ressources ou des backends que Jade peut comparer et utiliser. Le registre conserve ce que Jade apprend sur la manière de choisir et d'utiliser ces ressources à partir de son expérience mesurée.

## Chaîne d'apprentissage

La chaîne devient :

`expérience explicite → Outcome Quality → Night Learning → STRATEGY_HINT → Strategy Registry CANDIDATE → évaluation sandbox → VALIDATED → approbation explicite → ACTIVE dans le registre`

La 0.1.18 s'arrête volontairement avant l'application automatique au runtime.

Une entrée `ACTIVE` signifie qu'une stratégie a été explicitement approuvée dans le registre. Elle ne signifie pas que le routeur Android ou Node l'applique automatiquement. Le champ `runtime_application_enabled` reste `false` en 0.1.18.

## Portée initiale

Le premier type de stratégie persistant est limité à :

- `kind = ROUTING_STRATEGY`
- `target = routing.profile_model_preference`
- `task_kind = brain_chat`
- portée par `brain_profile`
- mode `prefer` ou `deprioritize`

Cela permet par exemple à Jade de retenir qu'un backend a donné de meilleurs résultats utilisateur pour le profil `code`, sans généraliser cette préférence à tous les profils ni en faire une vérité universelle.

## Provenance

Chaque entrée conserve la chaîne de provenance qui a conduit à sa création :

- révision et run Night Learning source ;
- signal Outcome ;
- hypothèse ;
- expérience champion/challenger ;
- candidat `STRATEGY_HINT` ;
- nombre d'échantillons, score et confiance ;
- identifiants de preuves lorsqu'ils existent.

Le feedback utilisateur reste une **preuve opérationnelle personnelle**. Il n'est jamais promu automatiquement comme fait externe vérifié.

Le Strategy Registry n'ingère pas le texte brut des conversations.

## Persistance et identité

Le registre VPS est stocké de façon atomique avec copie de secours. Il est lié à l'`identity_id` de Jade : un registre déjà lié à une identité refuse une autre identité.

Les candidats identiques sont idempotents. Une nouvelle observation du même candidat n'ajoute pas de doublon.

Une nouvelle stratégie ayant la même clé sémantique reçoit une nouvelle version. Les anciennes versions non actives peuvent devenir `SUPERSEDED`, tandis qu'une stratégie déjà `ACTIVE` n'est remplacée que lors d'une promotion explicite d'une nouvelle version.

## États

Les états supportés sont :

- `CANDIDATE` : stratégie durable mais non validée ;
- `VALIDATED` : sandbox réussie avec assez de preuves ;
- `ACTIVE` : stratégie validée et explicitement approuvée dans le registre ;
- `SUPERSEDED` : ancienne version remplacée ;
- `ROLLED_BACK` : stratégie active annulée ;
- `REJECTED` : évaluation sandbox échouée.

## Évaluation sandbox

Une stratégie ne peut devenir `VALIDATED` que si son évaluation :

- est exécutée en sandbox ;
- utilise des scénarios appariés ;
- conserve un champion gelé ;
- réussit ;
- atteint au moins 5 échantillons ;
- atteint une confiance d'au moins 0,75.

Ces minimums sont également compilés côté Android dans `SafetyPolicy`.

## Promotion

La promotion nécessite toujours une approbation explicite. Sans cette approbation, l'opération échoue.

Même après promotion :

- `automatic_activation = false` ;
- `automatic_promotion = false` ;
- `runtime_application_enabled = false` ;
- aucune réécriture du code de production ;
- aucune commande shell ;
- aucune mutation automatique des poids d'un modèle.

## Rollback

Lorsqu'une nouvelle stratégie remplace une stratégie active de la même portée, le registre conserve le lien `replaces_strategy_id`.

Un rollback explicitement approuvé peut alors :

1. passer la stratégie courante en `ROLLED_BACK` ;
2. restaurer l'ancienne stratégie en `ACTIVE` ;
3. conserver l'historique complet ;
4. laisser l'application runtime désactivée.

## Shared Genesis State

Le Night Cycle VPS publie un événement borné :

`vps_strategy_registry_snapshot`

Le snapshot partagé contient au maximum 8 entrées récentes et les compteurs principaux. Il est synchronisé vers le Pixel par le mécanisme Shared Genesis State existant.

Android le valide en fail-closed. Un snapshot est rejeté s'il demande ou affirme :

- une promotion automatique ;
- une application runtime automatique ;
- une application runtime déjà effectuée ;
- une réécriture de code de production ;
- une commande shell ;
- l'utilisation du texte brut de conversation ;
- la promotion d'un feedback utilisateur en fait externe.

Chaque entrée est également rejetée si elle retire la sandbox, les scénarios appariés, le champion gelé, l'approbation explicite, ou si elle tente d'activer elle-même son application runtime.

## Ce que 0.1.18 apporte réellement

La 0.1.18 ne donne pas encore à Jade une intelligence générale autonome et ne lui apprend pas des poids de réseau neuronal.

Elle apporte néanmoins une différence structurelle importante : Jade possède maintenant un état adaptatif durable qui représente **des stratégies acquises à partir de son expérience**, avec provenance, versions, évaluation, approbation et rollback.

Cela prépare la suite : connecter progressivement des stratégies approuvées au runtime, puis étendre ce même modèle à des compétences plus riches, tout en conservant des frontières vérifiables entre apprentissage, expérimentation et application réelle.
