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

Au début d'un nouveau cycle cognitif, Jade peut réinjecter un petit nombre d'expériences locales pertinentes :

- retours utilisateur récents concernant les mêmes sujets ;
- tours précédents liés aux mêmes sujets ;
- résumé du caractère récurrent d'un sujet et de ses retours positifs/négatifs/corrections.

L'absence de sujet commun produit désormais zéro injection conversationnelle. Le contexte conversationnel est ajouté après la mémoire principale et ne peut plus l'évincer. Ainsi, une correction ou une solution validée peut modifier le contexte utilisé lors d'une conversation ultérieure sans remplacer les mémoires préparées par JadeCore.

## Interprétation des retours

0.1.15 utilise une politique volontairement conservatrice : les marqueurs doivent être ancrés comme un retour explicite et les questions ne sont pas classées comme feedback.

Exemples acceptés :

- « ça marche », « ça marche. », « ça a marché », « c'est résolu », « parfait » → outcome positif ;
- « ça marche pas », « c'est faux », « tu te trompes », « toujours pas » → outcome négatif ;
- « non c'est… », « correction : … », « la bonne réponse… » → correction utilisateur.

Un simple « ok », « d'accord », une question contenant « ça marche pas », ou une tournure ambiguë comme « en fait, j'ai une autre question » n'est volontairement pas traité comme une validation/correction.

Quand un message est reconnu comme feedback, il s'attache au dernier tour récent compatible, ne crée pas lui-même un nouveau tour et ses mots ne sont pas comptés comme nouveaux sujets récurrents. Les répétitions identiques sur le même tour sont dédupliquées sur une petite fenêtre.

## Persistance et lecture fail-closed

L'état survit au redémarrage de l'application. Un état JSON corrompu est récupéré depuis la copie de secours lorsque celle-ci est lisible ; l'ancien primaire est conservé en quarantaine. Un schéma incompatible n'est jamais converti silencieusement en état vide : Conversation Learning est ignoré pour ce tour et le reste du Cognitive Core continue de fonctionner.

## Frontière de vérité

Un retour utilisateur est une expérience importante mais n'est pas automatiquement une preuve externe. Une correction utilisateur n'est donc pas promue silencieusement en connaissance universellement vérifiée.

0.1.15 apprend la continuité, les sujets récurrents et les outcomes explicites. Le lien entre ces outcomes et Runtime Eval / Night Learning / Evolution reste volontairement une étape ultérieure : tant qu'il n'existe pas, l'outcome modifie le contexte mais ne change pas encore un score mesuré de cerveau.

## Sécurité et bornes

Les plafonds sont compilés dans `SafetyPolicy` :

- 12 tours récents maximum ;
- 80 outcomes maximum ;
- 80 sujets suivis maximum ;
- 6 sujets par tour ;
- 2 tours conversationnels réinjectés ;
- 3 éléments de contexte conversationnel maximum ;
- 1 000 caractères maximum par extrait stocké ;
- extrait de correction réinjecté réduit à 200 caractères.

La fenêtre automatique d'attribution d'un feedback au dernier tour est réduite à 30 minutes. Aucun téléchargement automatique de modèle, aucune mutation de poids, aucune promotion automatique d'un candidat Evolution et aucune commande shell ne sont introduits par cette version.
