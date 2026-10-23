# CORRECT-MEM V1 — retenir (V1a) et mesurer (V1b), issue #85

Auteur : Codex. Relecteur : Claude. V1a #100 a été validée à `694e686`
et fusionnée dans main `6e8a59f137b92d6a1ddab699b51dbf4ae8e02ab8`.
V1b #101 est rebasée sur cette base ; son code attend une relecture distincte.
Réouverture et CI autorisées le 24/10/2026. Aucun essai Pixel ni fusion V1b.

## Capture et stockage

Seuls `corrige : <fait juste>` et le bouton **Corriger** créent une correction.
La réponse affichée doit encore correspondre à la référence sélectionnée. Un
nouveau message invalide la référence précédente, même en cas d'échec.
`non, …` ne crée pas automatiquement de FACT USER_CORRECTION.

Le fait juste seul (1500 caractères maximum) est un FACT/USER_CORRECTION de
confiance 1.0. Une table privée conserve la question, la référence et la copie
de la réponse fautive, ainsi que les propositions en attente. La confiance
signifie « fourni explicitement », sans garantie de vérité universelle.
La migration Room v2→v3 de V1a et v3→v4 de V1b sont additives ; le chemin v1→v2 reste conservé,
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

Pour une installation future expressément autorisée, respecter l'ordre
**VPS sur 8766 (ne pas toucher 8765) → PC Home → Pixel**. Chaque étape
exige l'accord explicite d'Alexandre. Vérifier `correct_mem_v1` annoncé
par chaque nœud avant l'étape suivante ; V1b requiert également
`correct_mem_exam_v1` sur le VPS pour soumettre un examen.

**L'APK du Pixel doit venir du workflow `stable-release`, signé avec la clé
stable. AVANT toute installation, vérifier que son certificat est identique
à celui de l'application déjà installée.** En cas de différence, arrêter.
**Jamais de désinstallation**, afin de conserver `jade_memory.db`.
Les APK debug et release non signée de la CI ne sont pas des APK à installer
sur Jade personnelle. Aucune lecture du certificat installé, installation,
désinstallation ou exécution de stable-release n'est autorisée ici.

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

## Examen nocturne V1b et HALT

Le Pixel orchestre le rejeu des questions d'origine depuis son stockage privé,
dans le cycle nocturne. Aucun fait n'est synchronisé durablement individuellement.
L'inférence utilise uniquement un VPS en ligne annonçant `correct_mem_exam_v1`,
via un appel dédié authentifié ; `ollama_model` doit être configuré. Sans ce
protocole ou modèle, aucun examen n'est soumis. Pas de SkillSpec, outil,
collecte de dataset, promotion ou apprentissage des réponses d'examen.

Fenêtre Europe/Paris : 00:30–06:30. Au plus dix tentatives par date, une par
correction et par nuit, même après retry ou redémarrage. Quota et tranche de
temps conservatrice persistés avant soumission. Budget global 15 minutes,
tranche maximale 90 secondes, appel modèle au plus 86 secondes avec marge
pour les contrôles. Les corrections les moins récemment examinées passent
d'abord. Une nuit sans corrections ne contacte aucun modèle.

`GET /correct-mem/status` projette `jade-halt.json` en lecture seule : absent
→ RUNNING ; illisible, mauvais schéma ou champs manquants → HALTED. Aucun
appel à JadeHalt.status(), aucune écriture de journal. Le Pixel exige RUNNING
frais et valide avant chaque appel et avant d'accepter le résultat. Le serveur
contrôle également HALT avant/après appel. État indisponible, périmé ou inconnu
→ arrêt. Le bouton local d'arrêt d'examen est durable et coopératif, sans
halt/rearm du VPS ; il empêche les appels suivants et invalide le résultat en cours.

Le transport Android coupe sa connexion à l'échéance absolue. Le serveur
rend la main à son échéance même si le modèle ignore le timeout. Un seul
appel modèle d'examen peut rester en vol ; son créneau reste occupé jusqu'à
sa fin, sa réponse tardive est jetée. Cela ne prouve pas l'arrêt immédiat
du calcul Ollama. Un échec ou timeout n'est pas compté comme répétition.

## Indicateur lexical et rapport #76

Extraction déterministe de nombres, unités, dates et mots capitalisés du fait,
visible dans la liste. Correspondances exactes NFC, sensibles à la casse pour
les noms et avec frontières numériques : 12 ne vaut ni 112, ni -12, ni 12.5.
Aucun élément extractible → non évaluable. Les mots capitalisés peuvent être
des faux positifs et les paraphrases sont limitées. **La présence textuelle
ne prouve ni vérité, ni cohérence, ni absence de négation.**

Le snapshot partagé `correction_metrics` contient uniquement comptes et dates :
actives, conflits, tentatives, rejoués, évaluables, reprises, indisponibilités,
interruptions. Aucun texte, identifiant de correction ou empreinte de contenu.
Le cache coalesce le signal ; le rapport #76 affiche le dernier snapshot,
sa nuit, sa date et sa péremption. Pas d'addition des synchronisations ni de
couverture hebdomadaire inventée. Taux = reprises/rejoués seulement si tous
les rejoués sont évaluables ; sinon inconnu/partiel. Zéro rejeu reste inconnu.
Les endpoints d'examen n'utilisent ni file de tâches ni journal de texte.

## Migrations et validation

V1a apporte la migration v2→v3. V1b ajoute v3→v4 : colonne lastExamAt
initialisée à zéro et table des nuits, sans effacer faits ni corrections.
Test SQLite synthétique de conservation d'une correction V1a durant v3→v4.
Les corrections V1a restent présentes : pertinence à deux termes distincts
ou question identique, et réponse normale du prototype avant son rappel.

V1b : **versionCode 50 > main 49**, versionName 0.1.32. Candidat initial
préservé sur `jade/correct-mem-v1-candidate` ; version V1b avant rebase
conservée localement pour la résolution et la traçabilité.
La CI doit exécuter les tests JVM, KSP/Room et compiler les APK debug et
release, puis vérifier version et signature. Debug signé Android Debug ;
release non signée et non debuggable, sans clé stable. Les preuves de la
tête exacte sont publiées dans #101. Ces builds ne sont pas ceux d'un
déploiement Pixel. Aucun appareil, modèle vivant ou nœud réel testé ici.
