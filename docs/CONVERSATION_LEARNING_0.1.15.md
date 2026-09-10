# Jade Genesis 0.1.15 — Outcome & Conversation Learning

## Objectif

0.1.15 ajoute une première couche d'apprentissage conversationnel réellement persistante côté Android. Elle ne modifie pas les poids d'un modèle. Elle fait évoluer l'état de Jade à partir de l'expérience accumulée avec l'utilisateur.

## Ce qui est appris

Jade conserve localement, dans un état borné avec copie de secours :

- les derniers tours de conversation utiles ;
- les sujets techniques récurrents extraits des messages ;
- les retours utilisateur explicites de réussite, d'échec ou de correction ;
- le profil cognitif, le backend et le modèle associés au tour quand ils sont connus.

Les sujets atteignent des jalons de récurrence à 3, 6, 12 puis 24 occurrences. Ces jalons sont visibles dans la trace cognitive mais ne deviennent pas des faits externes.

## Effet réel sur les réponses suivantes

Au début d'un nouveau cycle cognitif, Jade réinjecte un petit nombre d'expériences locales pertinentes :

- retours utilisateur récents concernant les mêmes sujets ;
- tours précédents liés aux mêmes sujets ;
- résumé du caractère récurrent d'un sujet et de ses retours positifs/négatifs/corrections.

Ainsi, une correction ou une solution validée peut modifier le contexte utilisé lors d'une conversation ultérieure. L'état survit au redémarrage de l'application.

## Interprétation des retours

0.1.15 ne considère que des formulations suffisamment explicites. Par exemple :

- « ça marche », « parfait », « c'est bon » → outcome positif ;
- « ça marche pas », « c'est faux », « tu te trompes » → outcome négatif ;
- « non c'est… », « en fait… », « correction : … » → correction utilisateur.

Un simple « ok » ou « d'accord » n'est volontairement pas traité comme une validation de qualité.

## Frontière de vérité

Un retour utilisateur est une expérience importante mais n'est pas automatiquement une preuve externe. Une correction utilisateur n'est donc pas promue silencieusement en connaissance universellement vérifiée.

0.1.15 apprend la continuité, les sujets récurrents et les outcomes explicites. Les futures étapes pourront relier ces outcomes à des évaluations indépendantes, tests vérifiables, succès réels et statistiques par modèle afin d'améliorer davantage le choix des cerveaux.

## Sécurité et bornes

Les plafonds sont compilés dans `SafetyPolicy` :

- 12 tours récents maximum ;
- 80 outcomes maximum ;
- 80 sujets suivis maximum ;
- 6 sujets par tour ;
- 2 tours conversationnels réinjectés ;
- 6 éléments de contexte conversationnel maximum ;
- 1 000 caractères maximum par extrait.

Aucun téléchargement automatique de modèle, aucune mutation de poids, aucune promotion automatique d'un candidat Evolution et aucune commande shell ne sont introduits par cette version.
