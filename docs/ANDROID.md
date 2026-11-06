# Android aplikacija

## Dva izdanja

| | Pro | Lite |
| --- | --- | --- |
| Paket | `com.orbitplayer.pro` | `com.orbitplayer.lite` |
| Gde se objavljuje | Tvoj sajt (`/api/public/download/pro`) | Google Play |
| Uparivanje kodom, admin panel, poruke, komande | da | ne |
| Launcher i kiosk | da | ne |
| Samoažuriranje | da | preko Play-a |
| Liste | sa sajta, iz panela ili ručno | samo ručno |

Oba izdanja imaju TV interfejs (daljinski) i interfejs za telefon/tablet; aplikacija sama prepoznaje uređaj.

## Kako nastaje APK

Svaki `git push` na `main` pokreće GitHub Actions (`.github/workflows/build.yml`), koji pravi `orbit-pro-0.1.0-<broj>.apk` i `orbit-lite-0.1.0-<broj>.apk`. Poslednji build je u **Releases → latest-build**. `<broj>` je `versionCode` koji se unosi u panelu pri objavi.

### Adresa servera

Podrazumevana adresa je u `android/app/build.gradle.kts` (`SERVER_URL`). Može se promeniti i bez izmene koda: GitHub → **Settings → Secrets and variables → Actions → Variables**, promenljiva `ORBIT_SERVER_URL` = `https://tvojdomen.com`.

### Potpisni ključ (obavezno pre prve prave objave)

Android dozvoljava ažuriranje samo ako je nova verzija potpisana **istim ključem**. Ključ napravi jednom i čuvaj ga — ako ga izgubiš, korisnici moraju da obrišu aplikaciju i instaliraju je ponovo.

```bash
keytool -genkeypair -v -keystore orbit-release.jks -alias orbit -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 orbit-release.jks > orbit-release.b64
```

Na GitHub-u, u **Settings → Secrets and variables → Actions → Secrets**, dodaj:

| Secret | Vrednost |
| --- | --- |
| `ORBIT_KEYSTORE_BASE64` | sadržaj fajla `orbit-release.b64` |
| `ORBIT_KEYSTORE_PASSWORD` | lozinka keystore-a |
| `ORBIT_KEY_ALIAS` | `orbit` |
| `ORBIT_KEY_PASSWORD` | lozinka ključa |

Dok ključ nije podešen, APK je potpisan test ključem iz repozitorijuma — to je samo za testiranje.

## Objava nove verzije

1. Promeni `appVersionName` u `android/app/build.gradle.kts` (npr. `0.2.0`) i pošalji izmenu na GitHub.
2. Preuzmi `orbit-pro-0.2.0-<broj>.apk` iz Releases → latest-build.
3. Admin panel → **Verzije aplikacije → Objavi novu verziju**: izdanje Pro, naziv `0.2.0`, kod `<broj>`.
4. Po želji prvo pusti na 10–20% uređaja („Postepeno puštanje”), pa povećaj na 100%.

Uređaji proveravaju nove verzije pri pokretanju i na 6 sati. Na uređajima u kiosk nivou 3 instalacija je tiha; ostali dobijaju ponudu u „Podešavanja → O aplikaciji”. „Obavezno ažuriranje” instalira bez pitanja kad god je moguće.

## Prečice na TV-u

| Taster | Na kanalima uživo |
| --- | --- |
| Gore / dole, CH+ / CH− | sledeći / prethodni kanal |
| OK | lista kanala |
| Levo / desno | informacije o emisiji |
| Brojevi | izbor kanala po broju |
| Meni / Info | zvuk, prevod, odnos slike |
| Meni 5 puta (u kiosk modu) | servisni PIN |

U listi kanala dug pritisak na kanal dodaje ga u omiljene; dug pritisak na grupu je sakriva.

## Poznata ograničenja ove verzije

- Ugrađeni VLC plejer još nije uključen; izbor „VLC” otvara snimak u VLC aplikaciji ako je instalirana.
- Kašnjenje zvuka i prevoda ne može da se podesi (ExoPlayer to ne podržava direktno).
- Podsetnici za emisije, snimanje, više slika istovremeno i Chromecast su planirani za verziju 2.
