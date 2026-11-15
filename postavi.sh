#!/bin/bash
# =====================================================================
#  Ekran — slanje postavki s Maca na TV (bez ponovne instalacije)
#
#   bash postavi.sh m3u "https://link-do-liste.m3u"
#   bash postavi.sh tmdb "TMDB_API_KLJUC"
#   bash postavi.sh grad "Sarajevo"
#   bash postavi.sh kanal "@imekanala"        (dodaje YouTube kanal)
#   bash postavi.sh                            (samo ponovo pošalje config.json)
# =====================================================================
set -e
cd "$(dirname "$0")"
PKG="ba.ekran.launcher"
command -v adb >/dev/null 2>&1 && ADB=adb || ADB=./platform-tools/adb
if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
  echo "Preuzimam adb…"
  curl -fL# -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip && unzip -q -o pt.zip && rm pt.zip
  ADB=./platform-tools/adb
fi
[ -f config.json ] || echo '{}' > config.json

KEY="$1"; VAL="$2"
if [ -n "$KEY" ]; then
  case "$KEY" in
    m3u|tmdb|grad|kanal) ;;
    *) echo "Nepoznato: $KEY (koristi: m3u, tmdb, grad, kanal)"; exit 1;;
  esac
  [ -z "$VAL" ] && read -r -p "Unesi vrijednost za $KEY: " VAL
  K="$KEY" V="$VAL" perl -MJSON::PP -e '
    local $/; open my $f, "<", "config.json"; my $t = <$f>; close $f;
    my $j = eval { decode_json($t) } || {};
    my ($k, $v) = ($ENV{K}, $ENV{V});
    if ($k eq "kanal") { $j->{youtube} ||= []; push @{$j->{youtube}}, $v unless grep { $_ eq $v } @{$j->{youtube}}; }
    else { $j->{ $k eq "grad" ? "city" : $k } = $v; }
    open my $o, ">", "config.json"; print $o JSON::PP->new->canonical->encode($j); close $o;'
  echo "✓ Sačuvano u config.json"
fi

IP="$(cat .ekran_ip 2>/dev/null)"
[ -z "$IP" ] && read -r -p "IP adresa TV Sticka: " IP && echo "$IP" > .ekran_ip
DEV="$IP:5555"
$ADB connect "$DEV" >/dev/null 2>&1 || true
STATE=""
for i in $(seq 1 15); do
  STATE="$($ADB -s "$DEV" get-state 2>/dev/null || true)"; [ "$STATE" = "device" ] && break; sleep 2
done
[ "$STATE" != "device" ] && { echo "❌ TV nije dostupan ($DEV). Je li upaljen i na istoj mreži?"; exit 1; }

CFG="$(sed 's/^{/{"_ts":"'"$(date +%s)"'",/' config.json | base64 | tr -d '\n')"
$ADB -s "$DEV" shell am start -n "$PKG/.MainActivity" --es config "$CFG" >/dev/null
echo "✓ Poslano na TV — Ekran učitava nove postavke."
