#!/bin/bash
# Vraća originalni Android TV launcher (i po želji briše Ekran).
cd "$(dirname "$0")"
command -v adb >/dev/null 2>&1 && ADB=adb || ADB=./platform-tools/adb
IP="${1:-$(cat .ekran_ip 2>/dev/null)}"
[ -z "$IP" ] && read -r -p "IP adresa TV Sticka: " IP
DEV="$IP:5555"
$ADB connect "$DEV" >/dev/null 2>&1
D="$ADB -s $DEV"
for L in com.google.android.tvlauncher com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.mitv.tvhome.atv com.mitv.tvhome.michannel; do
  $D shell pm enable "$L" >/dev/null 2>&1 && echo "uključen: $L"
done
read -r -p "Obrisati i Ekran launcher? (d/n) " A
[ "$A" = "d" ] && $D uninstall ba.ekran.launcher
$D shell input keyevent KEYCODE_HOME
echo "Stari launcher je vraćen."
