# Issue 85 — étape 1, émulateur uniquement

Résultat : PASS, le 24 octobre 2026. AVD neuf isolé JadeStep1, Android 36.1 x86_64 Google Play, série emulator-5580, ro.kernel.qemu=1. Mode avion, Wi-Fi et données coupés avant installation, aucune route par défaut, aucun compte ni nœud configuré. Données exclusivement synthétiques.

1. Installation APK stable 0.1.28/46 : Success. Binaire de l’app installée relevé en lecture seule à l’étape 0, sans données personnelles. Signature stable vérifiée.
2. Via l’interface : `retiens que la balise synthetique ORION_851 vaut 42`, puis `retiens que le dossier synthetique VEGA_852 est bleu`. Deux faits USER visibles.
3. Mise à jour par `adb -s emulator-5580 install -r` avec l’APK stable-release 0.1.33/51 de main 964656e16c3439c33d40a66ef7ef62439a8707f9 : Success. Aucun effacement ni désinstallation.
4. Démarrage réussi, processus 5423, pas de FATAL EXCEPTION ni erreur Room de migration dans les traces recueillies. Les deux faits sont conservés. Traces SQLite de la base Room : création user_corrections, ajout lastExamAt, création correction_exam_nights, user_version=4.
5. Question `Combien de satellites pour LYRA_853 ?`, puis `corrige : LYRA_853 possede 7 satellites` : une correction active listée. Bouton Oublier : message Correction oubliée, zéro correction active, les deux faits initiaux toujours présents.

Les captures et XML correspondent aux états avant/après, correction active et après oubli. Les logs publiés sont limités à la base/application de cet émulateur synthétique. Les deux APK ont le même certificat SHA256 (voir apk-verification.json).

Portée : répétition locale hors réseau sur émulateur ; aucune preuve de migration sur le Pixel personnel ou de fonctionnement distant. Aucun appel ADB vers le Pixel pendant cette étape, aucune mise à jour VPS/PC, aucun déploiement. STOP après rapport. L’inaccessibilité VPS relevée à l’étape 0 n’est pas résolue par cet essai.
