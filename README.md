# Bruno Chat — Application Android

Enveloppe **WebView** autour du site https://site-gratuit-dynamique.vercel.app/
Le site reste hébergé sur Vercel (aucune logique web modifiée) ; l'application
l'affiche en plein écran et ajoute le confort natif Android.

## 📲 Fonctionnalités

- **Écran d'accueil (splash) dynamique** : petit logo + grand « BRUNO CHAT »
  (animations d'entrée), puis transition en fondu vers l'interface de chat.
- **Plein écran immersif permanent** : heure / batterie / % masqués, **y compris
  pendant la saisie** — la page est poussée au-dessus du clavier par
  `adjustResize` (les barres système ne réapparaissent pas).
- **Photos** : le sélecteur de fichiers Android s'ouvre pour les pièces
  jointes (images multiples).
- **Liens externes** ouverts dans le navigateur ; **bouton retour** =
  historique du chat.
- **Mode fluide auto** : si l'appareil est lent (moins de 35 images/s), le
  fond décoratif animé du site est figé (le chat reste identique).

## 📦 Versions

| Version | Code | Contenu |
|---|---|---|
| 1.1.1 | 5 | Correctif : plein écran conservé pendant la saisie (heure/batterie ne réapparaissent plus) |
| 1.1.0 | 4 | Splash screen dynamique (dernière) |
| 1.0.2 | 3 | Plein écran + correctif clavier + mode fluide |
| 1.0.1 | 2 | Plein écran immersif |
| 1.0.0 | 1 | Première version (WebView simple) |

Compatibilité : **Android 5.0+** (minSdk 21), Internet requis.

## 🔧 Reconstruire l'APK (sans Android Studio)

```bash
# 1) place la clé de signature dans ../brunochat.keystore (hors dépôt)
#    ou définis les variables BRUNO_KEYSTORE / BRUNO_KEY_PASS
# 2) lance le build (télécharge JDK + Android SDK automatiquement)
bash scripts/build-apk.sh
# → releases/Bruno-Chat-<version>.apk (signé)
```

Le build est manuel (sans Gradle) : `javac` → `d8` → `aapt2` → `zipalign`
→ `apksigner`. Les outils sont téléchargés dans `tools/` (ignoré par git).

## 🔑 Clé de signature (IMPORTANT)

⚠️ La clé n'est **PAS** dans ce dépôt (il est public) : elle est conservée
hors ligne (archive de sauvegarde Bruno-Chat-Android.zip, alias `brunochat`).
Toute mise à jour de l'app doit être signée avec CETTE clé, sinon Android
refuse l'installation par-dessus l'ancienne version. Ne la perds pas.

## 🗂 Structure

```
app/                source de l'application (manifest, Java, ressources)
  AndroidManifest.xml
  java/com/brunochat/app/MainActivity.java
  res/              icônes + thème sombre
releases/           APK final signé
scripts/build-apk.sh
```
