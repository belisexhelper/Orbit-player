# Orbit Player

IPTV plejer za Android TV, telefone i tablete, sa sajtom za povezivanje i admin panelom.
„Orbit Player” je radno ime — menja se u `android/app/build.gradle.kts` (`APP_NAME`), `android/app/src/main/res/values/strings.xml`, `web/index.html` i `APP_NAME` u `.env`.

## Delovi

| Folder | Šta je | Tehnologija |
| --- | --- | --- |
| `android/` | Aplikacija: **Pro** (TV + telefon, uparivanje, launcher/kiosk, samoažuriranje) i **Lite** (samo plejer, za Google Play) | Kotlin, Jetpack Compose, Media3 ExoPlayer, Room |
| `backend/` | Server: uređaji, kodovi, liste (šifrovano), komande uživo, verzije aplikacije | Node.js, Fastify, PostgreSQL |
| `web/` | Sajt za klijente (`/`, `/link`) i admin panel (`/admin`) | React |
| `deploy/`, `Dockerfile`, `docker-compose.yml` | Postavljanje na server sa automatskim HTTPS-om | Docker, Caddy |

## Kako se povezuje uređaj

- **Smer A** — TV prikazuje kod od 8 cifara i QR kod. Klijent na telefonu otvori `tvojdomen.com/link`, unese kod i svoju listu. TV je učitava odmah.
- **Smer B** — admin u panelu napravi korisnika sa listom i dobije kod za aktivaciju, koji korisnik otkuca na TV-u („Imam kod za aktivaciju”).
- **Smer B, varijanta** — korisnik pročita adminu kod sa ekrana, a admin ga unese u panelu („Poveži uređaj kodom sa TV-a”).

## Uputstva

- [docs/SERVER.md](docs/SERVER.md) — postavljanje servera, sajta i panela
- [docs/ANDROID.md](docs/ANDROID.md) — pravljenje APK-a, potpisivanje, objava novih verzija
- [docs/KIOSK.md](docs/KIOSK.md) — launcher i kiosk mod (nivoi 1–3), ADB komande

## Razvoj lokalno

```bash
# backend sa ugrađenom bazom (bez Postgresa)
cd backend && npm install
ENCRYPTION_KEY=$(openssl rand -hex 32) APP_SECRET=$(openssl rand -hex 32) \
ADMIN_USERNAME=admin ADMIN_PASSWORD=promeni-me-123 ALLOW_PRIVATE_URLS=true npm run dev

# sajt i panel (u drugom terminalu) — otvara se na http://localhost:5173
cd web && npm install && npm run dev

# testovi backenda
cd backend && npm test
```
