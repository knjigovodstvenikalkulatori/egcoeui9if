#!/bin/bash
# Vraća sve na staro: originalni launcher i sve što je install.sh isključio.
cd "$(dirname "$0")"
command -v adb >/dev/null 2>&1 && ADB=adb || ADB=./platform-tools/adb
if [ ! -x "$(command -v $ADB)" ] && [ ! -x "$ADB" ]; then
  curl -fL# -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip && unzip -q -o pt.zip && rm pt.zip
  ADB=./platform-tools/adb
fi
IP="${1:-$(cat .ekran_ip 2>/dev/null)}"
[ -z "$IP" ] && read -r -p "IP adresa TV Sticka: " IP
DEV="$IP:5555"
$ADB connect "$DEV" >/dev/null 2>&1
D="$ADB -s $DEV"
for L in $(cat .ekran_disabled 2>/dev/null) com.google.android.tvlauncher com.google.android.leanbacklauncher com.google.android.tvrecommendations com.google.android.backdrop com.google.android.katniss; do
  $D shell pm enable "$L" >/dev/null 2>&1 && echo "uključeno: $L"
done
read -r -p "Obrisati i Ekran launcher? (d/n) " A
[ "$A" = "d" ] && $D uninstall ba.ekran.launcher
rm -f .ekran_disabled
$D shell input keyevent KEYCODE_HOME
echo "Sve je vraćeno na staro."
