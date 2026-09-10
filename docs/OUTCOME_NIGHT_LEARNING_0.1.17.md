# Jade Genesis 0.1.17 — Outcome → Night Learning

## Objectif

0.1.17 ferme une nouvelle partie de la boucle d'apprentissage de Jade : les retours explicites déjà reliés en 0.1.16 à l'observation Runtime Eval exacte deviennent maintenant des signaux utilisables par le Night Learning Lab du VPS.

La chaîne visée est :

`réponse réelle → outcome explicite → Runtime Eval → Shared Genesis State → consolidation nocturne → hypothèse → expérience champion/challenger → candidat de stratégie`

Cette version ne fait toujours aucune promotion automatique et ne modifie ni un modèle génératif ni le code de production pendant la nuit.

## Données utilisées

Le VPS reçoit uniquement les agrégats Runtime Eval déjà synchronisés :

- profil cognitif (`brain_profile`) ;
- nœud et modèle réellement utilisés ;
- nombre d'outcomes ;
- positifs, négatifs et corrections ;
- `outcome_quality_score` ;
- `outcome_confidence` ;
- métriques opérationnelles déjà existantes : succès, latence, fallback et débit.

Le texte brut de la conversation n'est pas nécessaire à cette consolidation et n'est pas envoyé au Night Learning Lab pour cette décision.

## Nouveaux signaux nocturnes

### `outcome_evidence_low`

Présent lorsqu'il existe des retours utilisateur mais moins de trois outcomes actifs. La seule action proposée est de continuer à collecter des preuves.

### `outcome_quality_risk`

Présent lorsqu'un groupe `brain_chat` possède au moins trois outcomes et une qualité mesurée suffisamment négative. Le signal peut préparer un challenger, jamais une pénalisation de production immédiate.

### `outcome_correction_pressure`

Présent lorsqu'un groupe accumule plusieurs corrections et qu'elles représentent une part importante des outcomes. Une correction reste une expérience personnelle, pas une vérité externe.

### `outcome_quality_advantage`

Compare uniquement des groupes appartenant au même profil cognitif. Les deux côtés doivent disposer d'au moins cinq outcomes et l'écart de qualité doit être suffisamment grand. Le résultat prépare une expérience appariée ; il ne crée pas une préférence durable à lui seul.

## Candidats de stratégie

Les signaux Outcome Quality suffisamment mesurés produisent un `STRATEGY_HINT` ciblant `routing.profile_model_preference`.

Ce type de candidat représente une première couche de stratégie apprenable propre à Jade : par exemple, constater qu'un backend convient mieux qu'un autre pour un profil `CODE` donné. Ce n'est pas encore un apprentissage de poids de réseau neuronal ; c'est une compétence de sélection et de stratégie acquise à partir de l'expérience réelle.

Avant toute préférence durable :

- scénario champion/challenger apparié ;
- champion gelé ;
- sandbox requise ;
- minimum de preuves ;
- fiabilité opérationnelle protégée ;
- approbation explicite toujours requise.

## Séparation entre expérience personnelle et recherche publique

Les signaux issus des outcomes ne déclenchent pas de recherche publique. Le retour utilisateur répond à la question « est-ce que cette réponse a été utile/correcte pour Alexandre dans ce contexte ? », pas à la question « est-ce un fait universel ? ».

La recherche publique reste disponible pour les signaux techniques généraux, par exemple fiabilité d'un backend, latence ou stratégie de routage. Les deux sources de preuve restent séparées.

## Bornes

Les limites principales restent conservatrices :

- 3 outcomes minimum avant interprétation d'un groupe ;
- 5 outcomes par côté avant comparaison de deux stratégies ;
- 8 outcomes = niveau de preuve fort repris du Runtime Eval ;
- écart minimal de qualité pour préparer un challenger ;
- au maximum 4 signaux, 4 hypothèses, 4 expériences et 4 candidats par cycle ;
- aucune exécution automatique d'expérience ;
- aucune activation automatique ;
- aucune promotion automatique ;
- aucune réécriture de code de production ;
- aucune commande shell arbitraire ;
- aucun texte brut de conversation utilisé pour cette consolidation.

## Validation

Les tests couvrent :

- outcomes trop rares → collecte de preuves seulement ;
- qualité négative → candidat de stratégie borné ;
- pression de corrections → candidat sandbox seulement ;
- avantage Outcome Quality entre deux backends du même profil ;
- absence de comparaison entre profils cognitifs différents ;
- absence de recherche publique déclenchée par un signal personnel ;
- conservation des tests historiques du Night Learning Lab ;
- respect strict des limites de volume et des garde-fous d'auto-promotion.

## Étape suivante

0.1.17 apprend à **consolider et proposer une stratégie** à partir de l'expérience. Une étape ultérieure pourra introduire un registre persistant de stratégies/compétences Jade, avec provenance, score, version, sandbox, rollback et promotion contrôlée. Cela constituera un substrat adaptatif propre à Jade, distinct des modèles génératifs externes qu'elle peut utiliser comme outils.
