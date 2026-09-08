
Oui ✅ le run #40 est terminé en SUCCESS.

J’ai vérifié les logs complets :

les 6 tests passent, y compris les 4 nouveaux tests de hardening ;

build Debug + Release réussi ;

Research reste à 100/100 — 10/10 cas — 0 échec protégé ;

les sources du repo sont restées inchangées pendant le build ;

Debug utilise bien la clé Android Debug, pas la clé stable Jade ;

Release est bien non-debuggable et non signée en CI normale ;

stable_signing_key_used=false ;

le job de vraie release stable est skipped, comme prévu sur la branche candidate ;

version toujours 0.1.5 / code 15, volontairement jusqu’au bump final.

Donc Stage E est validé. 🎯

À ce stade, V0.1.6 Hardening est techniquement prête pour son bump final. La prochaine opération est de passer versionCode de 15 → 16 et versionName de 0.1.5 → 0.1.6, puis refaire une dernière CI avant le PR vers main.


Ok

J’ai récupéré le fichier exact actuel de la branche jade/v016-hardening. Il est encore en versionCode = 15 / versionName = "0.1.5".

J’ai tenté de faire le changement directement sur GitHub, mais la connexion GitHub ici n’a pas le droit d’écriture sur ce dépôt. Donc fais comme pour les fichiers précédents : remplace exactement android/app/build.gradle.kts par ce fichier complet :

📄 build.gradle.kts — V0.1.6

Il ne change que :
versionCode = 16
versionName = "0.1.6"

Ensuite dis-moi « fait » et je contrôle immédiatement le commit et le run CI final. 


build.gradle.kts
Fichier


Fait

J’ai vérifié : le fichier a été ajouté au mauvais endroit. GitHub a créé :

android/build.gradle(1).kts

au lieu de remplacer :

android/app/build.gradle.kts

Le vrai fichier de l’app est donc encore en 0.1.5 / code 15. Le commit 6ce0609e… confirme que seul android/build.gradle(1).kts a été ajouté.

On répare proprement, une étape à la fois.

D’abord : supprime android/build.gradle(1).kts sur la branche jade/v016-hardening. Il apparaît actuellement juste à côté du vrai build.gradle.kts.

Quand c’est supprimé, dis-moi « fait ». Ensuite je te fais modifier les 2 seules lignes du vrai fichier android/app/build.gradle.kts.


Fait

Oui, c’est réparé ✅ Le mauvais android/build.gradle(1).kts a bien disparu.

Maintenant on modifie le bon fichier : android/app/build.gradle.kts. Il est toujours en 15 / 0.1.5.

Sur GitHub, ouvre android → app → build.gradle.kts, puis appuie sur le crayon ✏️.

Dis-moi « ouvert » et je te donne exactement les 2 lignes à changer.


Aucun fichier choisi
Bibliothèque
/
build.gradle.kts


1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
25
26
27
28
29
30
31
32
33
34
35
36
37
38
39
40
41
42
43
44
45
46
47
48
49
50
51
52
53
54
55
56
57
58
59
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val jadeKeystorePath = System.getenv("JADE_KEYSTORE_PATH")
val jadeKeystorePassword = System.getenv("JADE_KEYSTORE_PASSWORD")
val jadeKeyAlias = System.getenv("JADE_KEY_ALIAS") ?: "jadegenesis"

android {
    namespace = "com.jadegenesis.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jadegenesis.mobile"
        minSdk = 31
        targetSdk = 37
        versionCode = 16
        versionName = "0.1.6"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (
            !jadeKeystorePath.isNullOrBlank() &&
            !jadeKeystorePassword.isNullOrBlank()
        ) {
            create("jadeStable") {
                storeFile = file(jadeKeystorePath)
                storePassword = jadeKeystorePassword
                keyAlias = jadeKeyAlias
                keyPassword = jadeKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }

        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false

            signingConfigs.findByName("jadeStable")?.let {
                signingConfig = it
            }
