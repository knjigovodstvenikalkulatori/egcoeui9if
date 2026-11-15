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
REPO="${EKRAN_REPO:-}"
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

# ---- 7. Postavi kao početni ekran --------------------------------------
naslov "Postavljam Ekran kao početni ekran"
$D shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null 2>&1 || true
for L in com.google.android.tvlauncher com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.mitv.tvhome.atv com.mitv.tvhome.michannel; do
  if $D shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$L"; then
    $D shell pm disable-user --user 0 "$L" >/dev/null 2>&1 && echo "  isključen stari launcher: $L" || true
  fi
done
$D shell am start -n "$PKG/.MainActivity" --es config "$CFG" >/dev/null
zeleno "Gotovo! Ekran je pokrenut na TV-u. 🎉"
echo "Za vraćanje starog launchera: ./vrati.sh"
