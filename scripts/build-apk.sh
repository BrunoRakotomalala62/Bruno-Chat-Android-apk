#!/usr/bin/env bash
# ============================================================
# Bruno-Chat-Android — build de l'APK sans Android Studio
# Pipeline : javac -> d8 -> aapt2 (compile+link) -> zip dex
#            -> zipalign -> apksigner (signature)
# Résultat : releases/Bruno-Chat-<VERSION>.apk
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION_NAME="1.1.0"
VERSION_CODE=4
PACKAGE="com.brunochat.app"
# Clé de signature : NON incluse dans le dépôt (public).
# Définis BRUNO_KEYSTORE + BRUNO_KEY_PASS (et optionnel BRUNO_KEY_ALIAS),
# ou place la clé juste à côté du dépôt : ../brunochat.keystore
KEYSTORE="${BRUNO_KEYSTORE:-$(dirname "$PWD")/brunochat.keystore}"
KEY_ALIAS="${BRUNO_KEY_ALIAS:-brunochat}"
KEY_PASS="${BRUNO_KEY_PASS:-}"

TOOLS_DIR="tools"
JDK_DIR="$TOOLS_DIR/jdk"
SDK_DIR="$TOOLS_DIR/sdk"
BT="$SDK_DIR/build-tools"
PLATFORM_JAR="$SDK_DIR/android-13/android.jar"   # android.jar API 33

# ---------- 1) Téléchargement des outils (une seule fois) ----------
mkdir -p "$TOOLS_DIR"
if [ ! -x "$JDK_DIR/bin/javac" ]; then
  echo "[1/7] Téléchargement du JDK 11…"
  curl -sL --max-time 900 -o /tmp/jdk.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/11/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$JDK_DIR" && tar xzf /tmp/jdk.tar.gz -C "$JDK_DIR" --strip-components=1
fi
if [ ! -f "$BT/aapt2" ]; then
  echo "[1/7] Téléchargement des build-tools 34…"
  curl -sL --max-time 600 -o /tmp/bt.zip "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
  unzip -q /tmp/bt.zip -d "$SDK_DIR" && mv "$SDK_DIR/android-14" "$BT"
fi
if [ ! -f "$PLATFORM_JAR" ]; then
  echo "[1/7] Téléchargement de la platform android-33…"
  curl -sL --max-time 600 -o /tmp/platform.zip "https://dl.google.com/android/repository/platform-33_r02.zip"
  unzip -q /tmp/platform.zip -d "$SDK_DIR"   # contient android-13/ (API 33)
fi
export PATH="$PWD/$JDK_DIR/bin:$PATH"

# ---------- 2) Compilation Java ----------
echo "[2/7] javac…"
rm -rf build/classes build/dex build/res.zip build/app-unsigned.apk build/app-aligned.apk
mkdir -p build/classes build/dex
javac -source 8 -target 8 -classpath "$PLATFORM_JAR" \
  -d build/classes $(find app/java -name '*.java')

# ---------- 3) DEX ----------
echo "[3/7] d8…"
"$BT/d8" --release --lib "$PLATFORM_JAR" \
  --output build/dex $(find build/classes -name '*.class')

# ---------- 4) Ressources + manifest ----------
echo "[4/7] aapt2 (compile + link)…"
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/app-unsigned.apk \
  -I "$PLATFORM_JAR" \
  --manifest app/AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 33 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  build/res.zip

# ---------- 5) Injection du DEX ----------
echo "[5/7] ajout classes.dex…"
cd build && zip -qj app-unsigned.apk dex/classes.dex && cd ..
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

# ---------- 6) Signature ----------
echo "[6/7] signature…"
if [ ! -f "$KEYSTORE" ]; then
  echo "❌ Clé introuvable : $KEYSTORE"
  echo "   Définis BRUNO_KEYSTORE (chemin) + BRUNO_KEY_PASS (mot de passe)."
  exit 1
fi
[ -n "$KEY_PASS" ] || read -r -s -p "Mot de passe de la clé : " KEY_PASS
mkdir -p releases
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out "releases/Bruno-Chat-$VERSION_NAME.apk" build/app-aligned.apk

# ---------- 7) Vérification ----------
echo "[7/7] vérification…"
"$BT/apksigner" verify "releases/Bruno-Chat-$VERSION_NAME.apk"
echo "✅ OK → releases/Bruno-Chat-$VERSION_NAME.apk"
