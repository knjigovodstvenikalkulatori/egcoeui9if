#!/bin/bash
# =====================================================================
#  Ekran — instalacija launchera na Xiaomi Mi TV Stick (s Maca)
#  Pokretanje:  ./install.sh            (pita IP adresu)
#               ./install.sh 192.168.1.50
#  Ponovno pokretanje je sigurno: samo ažurira launcher i postavke.
# =====================================================================
set -e
cd "$(dirname "$0")"
PKG="ba.ekran.launcher"

zeleno(){ printf "\033[32m%s\033[0m\n" "$1"; }
naslov(){ printf "\n\033[1m▸ %s\033[0m\n" "$1"; }

# ---- 1. adb -----------------------------------------------------------
naslov "Provjeravam adb"
if command -v adb >/dev/null 2>&1; then
  ADB="adb"
else
  if [ ! -x ./platform-tools/adb ]; then
    echo "Preuzimam Android platform-tools (adb)…"
    curl -fL# -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
    unzip -q -o pt.zip && rm pt.zip
    xattr -dr com.apple.quarantine platform-tools 2>/dev/null || true
  fi
  ADB="./platform-tools/adb"
fi
zeleno "adb spreman"

# ---- 2. GitHub repo (odakle se preuzima APK) --------------------------
REPO="${EKRAN_REPO:-knjigovodstvenikalkulatori/egcoeui9if}"
[ -z "$REPO" ] && [ -f .ekran_repo ] && REPO="$(cat .ekran_repo)"
if [ -z "$REPO" ]; then
  read -r -p "GitHub repo (npr. korisnik/ekran-launcher): " REPO
  echo "$REPO" > .ekran_repo
fi

# ---- 3. IP adresa TV Sticka --------------------------------------------
IP="${1:-}"
[ -z "$IP" ] && [ -f .ekran_ip ] && IP="$(cat .ekran_ip)"
if [ -z "$IP" ]; then
  read -r -p "IP adresa TV Sticka (Postavke → Mreža): " IP
fi
echo "$IP" > .ekran_ip
DEV="$IP:5555"

# ---- 4. Povezivanje ----------------------------------------------------
naslov "Povezujem se na $DEV"
$ADB connect "$DEV" >/dev/null 2>&1 || true
echo "Ako se na TV-u pojavi „Allow USB debugging?“ — označi „Always allow“ i pritisni OK."
STATE=""
for i in $(seq 1 40); do
  STATE="$($ADB -s "$DEV" get-state 2>/dev/null || true)"
  [ "$STATE" = "device" ] && break
  [ $((i % 5)) -eq 0 ] && $ADB connect "$DEV" >/dev/null 2>&1 || true
  sleep 2
done
if [ "$STATE" != "device" ]; then
  echo "❌ Ne mogu se povezati. Provjeri: isti Wi-Fi, uključen USB/ADB debugging, tačna IP adresa."
  exit 1
fi
zeleno "Povezano"
D="$ADB -s $DEV"

# ---- 5. Preuzimanje i instalacija --------------------------------------
naslov "Preuzimam najnoviju verziju launchera"
if curl -fL# -o ekran.apk "https://github.com/$REPO/releases/download/latest/ekran.apk"; then
  zeleno "Preuzeto"
elif [ -f ekran.apk ]; then
  echo "Preuzimanje nije uspjelo — koristim postojeći ekran.apk"
else
  echo "❌ APK nije pronađen na https://github.com/$REPO/releases (je li build završen i repo javni?)"
  exit 1
fi
naslov "Instaliram na TV"
$D install -r ekran.apk
zeleno "Instalirano"

# ---- 6. Postavke (TMDB, M3U, YouTube kanali, grad) ---------------------
if [ ! -f config.json ]; then
  naslov "Postavke (Enter = preskoči, možeš i kasnije na TV-u)"
  read -r -p "TMDB API ključ: " TMDB
  read -r -p "M3U link za TV uživo: " M3U
  read -r -p "YouTube kanali (odvojeni zarezom, npr. @kanal1, @kanal2): " YT
  read -r -p "Grad za prognozu (prazno = automatski): " CITY
  q(){ printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
  YTJSON=""
  IFS=',' read -r -a ARR <<< "$YT"
  for c in "${ARR[@]}"; do
    c="$(echo "$c" | xargs)"; [ -z "$c" ] && continue
    [ -n "$YTJSON" ] && YTJSON="$YTJSON,"
    YTJSON="$YTJSON\"$(q "$c")\""
  done
  cat > config.json <<JSON
{"tmdb":"$(q "$TMDB")","m3u":"$(q "$M3U")","city":"$(q "$CITY")","youtube":[${YTJSON}]}
JSON
  zeleno "Sačuvano u config.json (možeš ga urediti i ponovo pokrenuti skriptu)"
fi
# _ts govori launcheru da je konfiguracija nova
CFG="$(sed 's/^{/{"_ts":"'"$(date +%s)"'",/' config.json | base64 | tr -d '\n')"

# ---- 7. Postavi kao početni ekran (i kod paljenja TV-a) ------------------
naslov "Postavljam Ekran kao početni ekran"
touch .ekran_disabled
off(){ # isključi paket ako postoji i zapamti ga za vrati.sh
  if echo "$PKGS" | grep -qx "package:$1"; then
    if $D shell pm disable-user --user 0 "$1" >/dev/null 2>&1; then
      grep -qx "$1" .ekran_disabled || echo "$1" >> .ekran_disabled
      echo "  isključeno: $1 ${2:+— $2}"
    fi
  fi
}
PKGS="$($D shell pm list packages 2>/dev/null | tr -d '\r')"
# svi drugi launcheri (sve što se prijavljuje kao Home), osim sistemskih postavki
HOMES="$($D shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | grep '/' | cut -d/ -f1 | sed 's/^ *//' | sort -u)"
for L in $HOMES com.google.android.tvlauncher com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.mitv.tvhome.atv com.mitv.tvhome.michannel; do
  case "$L" in "$PKG"|com.android.settings|com.android.tv.settings|com.android.provision|com.google.android.tungsten.setupwraith|"") continue;; esac
  off "$L" "stari launcher"
done
$D shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null 2>&1 || true
# dozvola da se Ekran sam otvori kad se TV upali
$D shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
$D shell cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
zeleno "Ekran je početni ekran i pokreće se pri paljenju TV-a"

# ---- Ambilight: dozvole za Bluetooth i snimanje ekrana (bez pitanja na TV-u) ----
for P in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_SCAN BLUETOOTH_CONNECT; do
  $D shell pm grant "$PKG" "android.permission.$P" >/dev/null 2>&1 || true
done
$D shell appops set "$PKG" PROJECT_MEDIA allow >/dev/null 2>&1 || true
# Android do verzije 11 traži uključenu lokaciju da bi našao Bluetooth trake
$D shell settings put secure location_mode 3 >/dev/null 2>&1 || true
$D shell cmd location set-location-enabled true >/dev/null 2>&1 || true
$D shell svc bluetooth enable >/dev/null 2>&1 || true

# ---- 8. Ubrzanje: isključi reklame i nepotrebne pozadinske procese -------
naslov "Ubrzavam TV (reklame i pozadinski procesi)"
off com.google.android.tvrecommendations "preporuke i reklame"
off com.google.android.leanbacklauncher.recommendations "preporuke"
off com.google.android.backdrop "Ambient pozadine (troše internet i memoriju)"
off com.google.android.apps.tv.dreamx "Google screensaver"
off com.google.android.feedback "slanje povratnih informacija"
off com.google.android.tv.bugreportsender "slanje izvještaja o greškama"
off com.google.android.play.games "Play Igre"
off com.google.android.videos "Play Filmovi"
off com.google.android.tv "Live Channels (Ekran ima svoj TV uživo)"
off com.mitv.tvhome.michannel "Xiaomi kanali"
off com.xiaomi.mitv.calendar "Xiaomi kalendar"
read -r -p "Isključiti i Google Assistant (glasovna pretraga na mikrofonu daljinskog)? Najviše ubrzava. (d/n) " GA
[ "$GA" = "d" ] && off com.google.android.katniss "Google Assistant"
$D shell pm trim-caches 999G >/dev/null 2>&1 || true
$D shell settings put global package_verifier_user_consent -1 >/dev/null 2>&1 || true
$D shell am kill-all >/dev/null 2>&1 || true
zeleno "Gotovo (sve se vraća sa ./vrati.sh)"

WV="$($D shell dumpsys package com.google.android.webview 2>/dev/null | tr -d '\r' | grep -m1 versionName | cut -d= -f2)"
[ -n "$WV" ] && echo "  Android System WebView: $WV (ako je ispod 100, ažuriraj ga u Play trgovini — Ekran će raditi brže)"

$D shell am start -n "$PKG/.MainActivity" --es config "$CFG" >/dev/null
zeleno "Gotovo! Ekran je pokrenut na TV-u. 🎉"
echo "Za vraćanje svega na staro: ./vrati.sh"
