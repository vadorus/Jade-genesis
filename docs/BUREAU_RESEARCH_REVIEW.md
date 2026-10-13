# Reprise bureau — 13 octobre 2026

Base : main f486452a10d97aeb4b24dc6f5b91a40b09ad694b, clone neuf sur DESKTOP-S47NP0P.

`node-agent/research_review.py` lit un rapport JSON agrégé de NightLearningLab et produit une revue texte hors ligne : questions, sources et extraits rapportés, statut de source primaire déclaré, hypothèses, propositions, mesure, bornes et empreinte SHA-256. Il ne vérifie pas le contenu des sources et ne contacte aucun service. Ne pas lui transmettre de mémoire personnelle : les extraits sélectionnés restent visibles dans le résultat.

Les propositions NightLearningLab déclarent maintenant explicitement que leur exécution requiert une autorisation, distincte de la promotion.

`execution_authorized` refuse par défaut. Il exige une autorisation utilisateur pour l'action execute_experiment, la cible bureau-sandbox, l'identifiant et l'empreinte exacte de la proposition, une date de début et une échéance de 24 heures au maximum. Toute modification du protocole invalide l'accord.

Cette fonction ne constitue pas une authentification de l'utilisateur : son appelant doit fournir les accords exclusivement depuis une interface utilisateur de confiance. Un JSON généré par un modèle ou trouvé dans un rapport n'est jamais une autorisation. Aucun stockage d'accord, consommation unique, exécuteur ou raccordement Android n'est implémenté. Un futur exécuteur devra également vérifier les ressources, les budgets, les données utilisées et la révocation, puis consommer l'accord pour éviter un rejeu. Une autorisation d'expérience n'accorde ni promotion, ni commit, ni push, ni déploiement, ni installation.

Tests : unittest test_research_review test_night_learning_lab test_vps_night_cycle test_adaptive_vps_night_cycle test_proof_store_hardening. Utiliser JADE_GENESIS_CONFIG_DIR dans un répertoire temporaire isolé.

Suite : récupérer les changements non publiés home en lecture seule lorsqu'ils sont accessibles, comparer avant intégration ; raccorder cette revue à l'interface réellement disponible ; ajouter émission/révocation/consommation des accords avant tout exécuteur. Les tests locaux ne démontrent pas la santé du VPS ou du Pixel.
