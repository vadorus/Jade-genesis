# Jade Genesis 0.1.16 — Outcome → Runtime Eval + Memory Health

## Objectif

0.1.16 ferme la boucle entre l'expérience conversationnelle et le routage cognitif mesuré. Un retour explicite de l'utilisateur sur une réponse peut désormais devenir un signal **Outcome Quality** de Runtime Eval, à condition qu'il soit rattachable à l'observation exacte de la réponse qui l'a provoqué.

Cette version ajoute aussi **Memory Health**, un observateur de la taille physique des mémoires et états persistants de Jade sur le Pixel.

## Outcome Quality : ce qui change réellement

Lorsqu'un `brain_chat` distant aboutit, `LocalPCBrain` conserve dans le `BrainResult` :

- le nœud réellement exécuté ;
- le profil cognitif réellement demandé ;
- le modèle réellement annoncé par le runtime ;
- l'identifiant exact de l'observation Runtime Eval créée pour cette exécution.

`ConversationLearningStore` attache cet identifiant au tour conversationnel. Si un message suivant est reconnu de façon conservatrice comme `POSITIVE`, `NEGATIVE` ou `CORRECTION`, le signal peut être envoyé à Runtime Eval avec l'identifiant de l'observation ciblée.

Runtime Eval refuse une attribution si l'observation exacte n'existe plus, n'est pas un `brain_chat` réussi ou n'est pas identifiable. Le nœud, le modèle et le profil sont recopiés depuis l'observation cible ; ils ne sont pas acceptés depuis le texte utilisateur.

## Une réponse = un outcome actif

Une observation de réponse ne peut contribuer qu'un seul outcome actif à la qualité. Si l'utilisateur corrige ensuite son propre retour, le nouveau signal remplace l'ancien pour cette réponse au lieu de compter deux votes.

L'agrégateur applique la même règle défensivement et ignore les outcomes dont l'observation cible n'est plus dans la fenêtre Runtime Eval analysée.

## Influence bornée sur le routage

La performance opérationnelle reste séparée de la qualité perçue :

- opérationnel : succès d'exécution, latence, débit, fallback ;
- outcome : validation, rejet ou correction explicite par l'utilisateur.

L'Outcome Quality n'influence pas le routage avant **3 réponses évaluées** pour un groupe nœud / profil / modèle. Sa confiance monte progressivement et devient forte à **8 réponses évaluées**.

La contribution sémantique est bornée à **±12 points**. L'ajustement final de l'Adaptive Brain Routing reste lui-même plafonné par le garde-fou global existant de **±40 points**. Une poignée de retours ne peut donc pas renverser sans limite le prior matériel et opérationnel.

Une correction est traitée comme un signal négatif de qualité pour la stratégie qui a produit la réponse corrigée. Elle ne devient pas pour autant un fait externe vérifié.

## Vie privée et frontière de vérité

Runtime Eval ne copie **aucun texte de feedback utilisateur**. Il conserve uniquement : identifiant du signal, observation ciblée, nœud, profil, modèle, type d'outcome, confiance et date.

Le texte de correction éventuellement conservé par Conversation Learning reste dans son magasin borné séparé et continue d'être présenté comme extrait utilisateur non vérifié.

Les anciens tours 0.1.15 qui ne possèdent pas `runtime_eval_observation_id` restent lisibles. Ils ne sont simplement pas rétroactivement transformés en preuves Runtime Eval.

0.1.16 ne modifie pas les poids d'un modèle, ne télécharge pas automatiquement de modèle, ne promeut pas automatiquement une configuration Evolution et ne réécrit pas le code de production.

## Feedback isolé

Un message classifiable comme feedback mais sans tour conversationnel récent ne devient plus un faux tour ou un faux sujet. Conversation Learning le laisse sans expérience persistante associée.

## Memory Health

Memory Health mesure des **octets réellement présents sur le stockage interne**, sans lire leur contenu :

- `jade_memory.db` ;
- journaux SQLite `jade_memory.db-wal` et `jade_memory.db-shm` ;
- état Conversation Learning ;
- état Runtime Eval ;
- Shared Genesis State ;
- autres fichiers SharedPreferences Jade.

Le propre fichier d'historique de Memory Health est exclu de la mesure afin d'éviter qu'il se compte lui-même.

L'écran Mémoire affiche le total et la répartition. Un échantillon de taille est conservé au maximum toutes les **12 heures**, avec **75 échantillons** maximum, ce qui permet d'obtenir des tendances sur 7 et 30 jours après suffisamment d'historique.

Les états visuels `NORMAL`, `À SURVEILLER` et `IMPORTANT` sont des indicateurs. **Aucun dépassement de taille ne déclenche une suppression automatique.** La rétention mémoire reste régie par ses propres règles conservatrices et ses protections de provenance, vérification et rappel.

Le snapshot `memory_cursor` de Shared Genesis State publie aussi les tailles et tendances afin que le VPS puisse ultérieurement analyser la croissance sans recevoir le contenu des mémoires.

## Shared Genesis State

`runtime_eval_snapshot` expose désormais, en plus des métriques opérationnelles :

- nombre total d'outcomes retenus ;
- qualité outcome globale ;
- par groupe : nombre d'outcomes, positifs, négatifs, corrections, score de qualité et confiance.

Les consommateurs Python existants peuvent ignorer ces champs additionnels. Aucun nouveau type de commande distante n'est introduit.

## Limites connues

- Le classifieur conversationnel reste volontairement conservateur : il préfère perdre certains signaux plutôt qu'inventer une validation ou un échec.
- Les outcomes actuellement attribués proviennent du Conversation Learning local Android. Les agrégats peuvent être publiés au VPS, mais le texte brut n'est pas répliqué dans Runtime Eval.
- Le routage Android choisit aujourd'hui un nœud et un profil ; le runtime du nœud garde sa propre sélection de modèle. L'Outcome Quality est donc mesuré par modèle réellement observé, mais le Pixel ne force pas encore directement un modèle précis à partir de cette statistique.
- Memory Health mesure le stockage du Pixel. Une future vue distribuée devra agréger séparément les volumes du VPS et du PC au lieu de les confondre avec le stockage local.
