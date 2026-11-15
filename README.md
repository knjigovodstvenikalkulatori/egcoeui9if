# Ekran — TV launcher za Xiaomi Mi TV Stick

Moderni početni ekran (Android TV launcher) u stilu Apple TV-a.

- Aplikacije (biraš koje su u redu: drži OK)
- Nastavi gledati (ugrađeni YouTube player pamti dokle si stigao)
- TV uživo iz M3U liste (ugrađeni player, gore/dolje = kanali)
- Popularni filmovi (TMDB) → otvaranje u Stremiu ili trailer
- Novo s tvojih YouTube kanala
- Vijesti iz BiH, vremenska prognoza, sat

## Kako radi
1. Svaki `push` na `main` → GitHub Actions napravi `ekran.apk` i objavi ga kao release **latest**.
2. Na Macu: `./install.sh` → poveže se na TV (adb preko Wi-Fi), instalira APK, pošalje postavke i postavi Ekran kao početni ekran.
3. `./vrati.sh` vraća originalni launcher.

Izgled je u `app/src/main/assets/index.html` (otvara se i u browseru kao demo).
