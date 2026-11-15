#!/bin/bash
# Ekran — dijagnoza Ambilighta. Ispis kopiraj i pošalji.
cd "$(dirname "$0")"
command -v adb >/dev/null 2>&1 && ADB=adb || ADB=./platform-tools/adb
IP="$(cat .ekran_ip 2>/dev/null)"; [ -z "$IP" ] && read -r -p "IP adresa TV Sticka: " IP
DEV="$IP:5555"; $ADB connect "$DEV" >/dev/null 2>&1; D="$ADB -s $DEV"
PKG=ba.ekran.launcher
echo "===== UREĐAJ ====="
echo "Android: $($D shell getprop ro.build.version.release | tr -d '\r')  model: $($D shell getprop ro.product.model | tr -d '\r')"
echo "Ekran verzija: $($D shell dumpsys package $PKG | tr -d '\r' | grep -m1 versionName)"
echo "Bluetooth: $($D shell settings get global bluetooth_on | tr -d '\r')   lokacija: $($D shell settings get secure location_mode | tr -d '\r')"
echo "Snimanje ekrana (PROJECT_MEDIA): $($D shell appops get $PKG PROJECT_MEDIA | tr -d '\r')"
echo "Dozvole:"; $D shell dumpsys package $PKG | tr -d '\r' | grep -E "LOCATION|BLUETOOTH" | grep granted | sed 's/^ */  /'
echo "Servis: $($D shell dumpsys activity services $PKG | tr -d '\r' | grep -c AmbiService) instanc(a)"
echo "===== ZAPIS (zadnjih 80 redova) ====="
$D logcat -d -v time -s Ekran:* AndroidRuntime:E | tail -80
