# CORRECT-MEM V1 â€” candidat local, issue #85

Auteur : Codex. Relecteur prÃ©vu : Claude, indisponible (quota). Alexandre a
autorisÃ© le 18/10/2026 la poursuite sans Claude et l'Ã©dition isolÃ©e de JadeCore
et du runtime ; aucune revue indÃ©pendante n'est revendiquÃ©e. Base :
`c140aff3fc9a313255ae4b93d5f605c66e3d2ee9`. Aucun APK ou dÃ©ploiement.

## Contrat

Seuls `corrige : <fait juste>` et le bouton **Corriger** crÃ©ent une correction.
La rÃ©ponse affichÃ©e doit encore correspondre Ã  la rÃ©fÃ©rence sÃ©lectionnÃ©e. Un
Ã©chec de nouveau message invalide la rÃ©fÃ©rence prÃ©cÃ©dente. `non, â€¦` ne crÃ©e
jamais de FACT USER_CORRECTION ; les autres systÃ¨mes conversationnels existants
ne sont pas remplacÃ©s par ce mÃ©canisme.

Le fait (1500 caractÃ¨res maximum, sans troncature silencieuse) est un
FACT/USER_CORRECTION de confiance 1.0. Une table privÃ©e conserve la question,
la rÃ©fÃ©rence/copie de la rÃ©ponse fautive et les propositions en attente. Une
proposition en conflit n'est pas encore un FACT. La migration Room v2â†’v3 est
additive ; le chemin v1â†’v2 reste conservÃ©, sans rÃ©initialisation destructive.
La confiance signifie Â« fourni explicitement Â», pas vÃ©ritÃ© universelle.

Une question identique, un fait identique ou un recouvrement lexical d'au moins
deux termes produit un conflit potentiel. L'Ã©cran MÃ©moire affiche les cibles
et demande confirmation. L'insertion et supersededBy sont transactionnels,
avec revalidation de l'ensemble exact des cibles ; un consentement pÃ©rimÃ© est
refusÃ©. Fermer/annuler ne remplace rien. Un conflit en attente est signalÃ© et
ses cibles sont exclues du contexte prioritaire. Le lexique ne dÃ©tecte pas
toutes les contradictions sÃ©mantiques.

Le rappel parcourt les corrections actives sans limite par rÃ©cence, classe le
fait et la question historique par MemoryContextSearch et garde au plus cinq
rÃ©sultats dans un quota distinct. Le stockage refuse de nouvelles propositions
au-delÃ  de 10000 liens, sans effacer les anciens. Les faits ordinaires ne sont
pas plafonnÃ©s par cette limite. Une question exactement rÃ©pÃ©tÃ©e est prioritaire ;
les paraphrases et termes courts peuvent Ã©chapper au lexique.

## Contexte et oubli

Les PC/VPS reÃ§oivent un tableau `corrections` sÃ©parÃ© des dix souvenirs ordinaires,
avec seulement content/source/confiance. Question historique et rÃ©ponse fausse
ne sont jamais envoyÃ©es comme souvenirs. La mÃªme section survit aux copies
answer/verify/revise. Le runtime annonce `correct_mem_v1` et ajoute :
Â« Faits corrigÃ©s par Alexandre : ils priment sur tes connaissances. Â»
Les faits ne deviennent pas des commandes/permissions et ne remplacent pas une
observation runtime actuelle.

Un nÅ“ud ancien sans cette capacitÃ© n'est pas utilisÃ© pour un tour corrigÃ© ;
le secours Pixel restitue les faits pertinents comme rappel lexical identifiÃ©,
sans prÃ©tendre Ã  une gÃ©nÃ©ration. La prÃ©sence du contexte ne prouve pas
l'obÃ©issance d'un modÃ¨le. Aucun dÃ©ploiement des nÅ“uds n'est inclus.

`oublie : <fait exact>` ne supprime qu'une correspondance unique ; sinon la
liste MÃ©moire permet de choisir. L'oubli efface le FACT et les liens/propositions
associÃ©s, sans rÃ©activer un ancien fait superseded. Les tours utilisant des
corrections n'alimentent pas ConversationLearning ni les sorties de RuntimeEval ;
les corrections ne passent pas par la consolidation, l'auto-supersession ou la
rÃ©tention automatique. Un verrou commun sÃ©rialise conversation/rejeu et oubli.
AprÃ¨s l'oubli confirmÃ©, aucune nouvelle requÃªte ne reprend ce fait. Une requÃªte
dÃ©jÃ  reÃ§ue par un modÃ¨le distant ne peut pas Ãªtre retirÃ©e rÃ©troactivement.
La suppression est logique dans SQLite ; aucune promesse d'effacement forensique
des pages libres/sauvegardes externes.


## Découpage et validation

V1a contient uniquement la rétention. Aucun examen, endpoint HALT, compteur ou
rapport n’est activé par V1a ; V1b les ajoute dans une PR séparée et une migration
v3→v4 additive. Le candidat complet initial est conservé sur
`jade/correct-mem-v1-candidate`. CI : tests JVM, KSP/Room, APK debug signé debug
et release non signé ; signature stable non utilisée. Les résultats réels sont
consignés dans la PR. Aucun appareil ni modèle vivant testé.
