# Orbit Player — Android aplikacija

IPTV plejer za Android TV, TV boksove, telefone i tablete. „Orbit Player” je radno ime.

| Izdanje | Paket | Namena |
| --- | --- | --- |
| **Pro** | `com.orbitplayer.pro` | Preuzima se sa sopstvenog sajta: povezivanje kodom, admin panel, launcher i kiosk, samoažuriranje |
| **Lite** | `com.orbitplayer.lite` | Za Google Play: samo plejer, liste se unose ručno |

Server, sajt i admin panel nisu u ovom repozitorijumu.

## Build

Svaki push na `main` pokreće GitHub Actions i pravi `orbit-pro-<verzija>-<broj>.apk` i `orbit-lite-<verzija>-<broj>.apk`. Poslednji build je uvek u **Releases → latest-build**.

## Uputstva

- [docs/ANDROID.md](docs/ANDROID.md) — potpisni ključ, adresa servera, objava novih verzija, prečice na TV-u
- [docs/KIOSK.md](docs/KIOSK.md) — launcher i kiosk mod (nivoi 1–3), ADB komande
