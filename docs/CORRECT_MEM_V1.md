# CORRECT-MEM V1a — retenir les corrections, issue #85

Auteur : Codex. Relecteur : Claude. Plan validé le 24/10/2026 ; relecture du
code initial sur PR #100 : trois corrections demandées, appliquées dans cette
révision et soumises à une nouvelle relecture. Base produit : main
`c3d6789517c11a9df31a34a85d9b07d7b2891e14`. Aucun essai Pixel ni fusion.

## Capture et stockage

Seuls `corrige : <fait juste>` et le bouton **Corriger** créent une correction.
La réponse affichée doit encore correspondre à la référence sélectionnée. Un
nouveau message invalide la référence précédente, même en cas d'échec.
`non, …` ne crée pas automatiquement de FACT USER_CORRECTION.

Le fait juste seul (1500 caractères maximum) est un FACT/USER_CORRECTION de
confiance 1.0. Une table privée conserve la question, la référence et la copie
de la réponse fautive, ainsi que les propositions en attente. La confiance
signifie « fourni explicitement », sans garantie de vérité universelle.
La migration Room v2→v3 est additive ; le chemin v1→v2 reste conservé,
sans réinitialisation destructive.

Une question identique, un fait identique ou au moins deux termes communs
produit un conflit potentiel. La proposition reste PENDING, sans nouveau FACT
ni remplacement avant confirmation. L'écran Mémoire affiche les cibles ;
l'insertion et supersededBy sont transactionnels, avec revalidation de
l'ensemble exact des cibles. Un consentement périmé est refusé. Un conflit
en attente exclut ses cibles du contexte prioritaire. Le lexique ne détecte
pas toutes les contradictions sémantiques.

## Pertinence, contexte et secours

Le rappel parcourt les corrections actives sans limite par récence et garde
au plus cinq corrections dans un quota séparé. Une correction est pertinente
si sa question d'origine est identique après normalisation, **ou si au moins
deux termes distincts de la requête correspondent à sa question et son fait**
selon MemoryContextSearch. Un seul mot commun, même répété, ne suffit pas.
La question identique est prioritaire. Les paraphrases et termes courts
peuvent échapper au lexique. Le stockage refuse les nouvelles propositions
au-delà de 10000 liens, sans effacer les anciens.

Les PC/VPS reçoivent un tableau `corrections` séparé des souvenirs ordinaires,
avec seulement content/source/confidence. La question historique et la réponse
fausse ne sont jamais envoyées comme souvenirs. La section survit aux copies
answer/verify/revise. Le runtime annonce `correct_mem_v1` et précise :
« Faits corrigés par Alexandre : ils priment sur tes connaissances. »
Ces faits ne sont ni des commandes ni des permissions ; ils ne remplacent
pas une observation actuelle.

Un nœud sans `correct_mem_v1` est écarté pour un tour utilisant une correction.
Le secours PrototypeBrain produit sa réponse normale, puis ajoute à la fin
« Rappel — correction(s) d'Alexandre : … », sans remplacer cette réponse ni
supprimer sa sélection d'outil. Le rappel reste lexical ; la présence du
contexte ne prouve pas l'obéissance d'un modèle.

## Condition de déploiement

**Les node-agent du VPS et du PC doivent être mis à jour AVANT l'APK.**
Vérifier qu'ils annoncent `correct_mem_v1` avant de distribuer l'APK V1a.
Sinon toute question liée à une correction écarterait ces anciens nœuds et
passerait au secours local. Cet ordre est une condition de tout déploiement
ultérieurement autorisé ; cette PR n'autorise ni n'effectue ce déploiement.

## Oubli et confidentialité

`oublie : <fait exact>` supprime une correspondance unique ; sinon la liste
Mémoire permet de choisir. L'oubli transactionnel efface le FACT et les liens
ou propositions associés, sans réactiver un fait superseded. Les corrections
ne passent pas par la consolidation, l'auto-supersession ou la purge ; leurs
tours n'alimentent ni ConversationLearning ni les sorties de RuntimeEval.
Le transport privé distant est synchrone, sans file ni erreurs contenant
les textes privés. Aucun contenu réel n'est publié dans les diagnostics.

Le verrou commun sérialise conversation et mutations des corrections.
Il est **non réentrant** : une question longue retarde correction/oubli ;
aucun appel à ask, proposeCorrection, confirmCorrection ou forgetCorrection
ne doit être ajouté à l'intérieur de askWithCorrections, sous peine de blocage.
Après oubli confirmé, aucune nouvelle requête ne reprend le fait. Une requête
déjà reçue par un modèle ne peut pas être retirée rétroactivement.
La suppression SQLite ne promet pas l'effacement forensique des pages libres
ou des sauvegardes externes.

## Découpage et validation

V1a contient uniquement la rétention. Examen nocturne, HALT lecture seule,
compteurs et rapport #76 restent dans V1b (#101, fermée en attente), avec
migration additive v3→v4. Le candidat initial est préservé sur
`jade/correct-mem-v1-candidate`.

Cette révision passe au versionCode **49**, supérieur à V1a initiale 47 et
au candidat V1b 48 ; versionName 0.1.31. V1b devra prendre un versionCode
supérieur à 49 lors de sa reprise après V1a.
Tests synthétiques : exclusion à un seul terme, inclusion à deux termes,
question identique sans terme lexical, quota cinq, remplacement/oubli,
et réponse normale du prototype conservée avant le rappel, outils inclus.
La CI exécute les tests JVM et KSP/Room, compile les APK debug et release,
puis vérifie version et signature. Debug est signé Android Debug ; release
reste non signée et non debuggable, sans clé stable. Les preuves de la tête
exacte sont publiées dans la PR. Aucun appareil ni modèle vivant testé.
