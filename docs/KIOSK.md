# Launcher i kiosk mod

U admin panelu, u **Launcher i kiosk**, napravi profil i dodeli ga korisniku ili pojedinačnom uređaju. Aplikacija sama bira najjači nivo koji uređaj podržava i javlja ga u panelu („Nivo launchera” na stranici uređaja).

| Nivo | Priprema uređaja | Šta radi |
| --- | --- | --- |
| 1 | ništa — korisnik izabere aplikaciju kao početni ekran | Home dugme i paljenje otvaraju aplikaciju |
| 2 | jednom, preko ADB-a, isključi se sistemski launcher | sistem nema drugi početni ekran |
| 3 | fabrički reset + ADB komanda (Device Owner) | pravi kiosk: Lock Task, blokada podešavanja, instalacije, statusne trake, tihe nadogradnje, restart iz panela |

## Priprema za ADB

1. Na uređaju: **Podešavanja → O uređaju → Build** — pritisni 7 puta (uključuju se opcije za programere).
2. **Opcije za programere → USB/mrežno otklanjanje grešaka (ADB debugging)** — uključi.
3. Na računaru instaliraj [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) i poveži se:
   ```bash
   adb connect 192.168.1.50      # IP adresa uređaja
   ```

## Nivo 2 — isključivanje sistemskog launchera

```bash
# Google TV
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
# Android TV (stariji)
adb shell pm disable-user --user 0 com.google.android.tvlauncher
# Vraćanje
adb shell pm enable com.google.android.apps.tv.launcherx
```

Na Google TV-u se posle isključivanja launchera može pojaviti greška u čarobnjaku za podešavanje; tada isključi i `com.google.android.tungsten.setupwraith`.

## Nivo 3 — Device Owner (pravi kiosk)

Device Owner se može postaviti **samo na uređaj bez naloga** (posle fabričkog reseta, pre dodavanja Google naloga).

1. Fabrički resetuj uređaj i prođi kroz podešavanje **bez prijave na Google nalog**.
2. Instaliraj Pro APK.
3. Pokreni:
   ```bash
   adb shell dpm set-device-owner com.orbitplayer.pro/com.orbitplayer.kiosk.AdminReceiver
   ```
   Odgovor `Success: Device owner set to package ...` znači da je gotovo.
4. Otvori aplikaciju i poveži uređaj (kod sa TV-a ili kod za aktivaciju). Čim uređaj dobije launcher profil sa nivoom „Automatski” ili „Pravi kiosk”, zaključava se.

Ako komanda javlja `Not allowed to set the device owner because there are already some accounts`, na uređaju postoji nalog — ukloni ga ili resetuj uređaj.

## Servisni pristup na terenu

- U launcher profilu postavi **PIN za izlaz iz kiosk moda**.
- Na daljinskom pritisni **Meni 5 puta zaredom** (u roku od 3 sekunde) i unesi PIN.
- Kiosk se isključuje na 30 minuta; isto radi komanda „Privremeno izađi iz kiosk moda” iz panela.
- U istom meniju je „Ukloni Device Owner” — pre vraćanja ili prodaje uređaja.

## Šta blokira nivo 3

| Opcija u profilu | Efekat |
| --- | --- |
| Blokiraj sistemska podešavanja | aplikacija Podešavanja je sakrivena |
| Blokiraj instaliranje i brisanje | instalacija iz bilo kog izvora je zabranjena (osim naših nadogradnji) |
| Sakrij statusnu traku | nema obaveštenja ni brzih podešavanja |
| uvek | fabrički reset iz menija, novi korisnici i bezbedni režim su zabranjeni |

Dozvoljene aplikacije (npr. YouTube) mogu da se pokrenu iz početnog ekrana i rade u istom zaključanom režimu.
