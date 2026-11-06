# Postavljanje servera

Server pokreće sajt za klijente, admin panel i API za aplikaciju. Video ne prolazi kroz server, pa je dovoljan mali VPS.

## Šta ti treba

- VPS sa Ubuntu 22.04 ili 24.04, 1–2 GB RAM-a (npr. Hetzner CX22, DigitalOcean 2 GB)
- Domen, sa DNS **A** zapisom koji pokazuje na IP adresu servera (npr. `tvojdomen.com`)
- Otvoreni portovi 80 i 443

## Korak po korak

1. **Instaliraj Docker** (jednom):
   ```bash
   curl -fsSL https://get.docker.com | sh
   ```

2. **Preuzmi kod**:
   ```bash
   git clone https://github.com/TVOJ_NALOG/Orbit-player.git
   cd Orbit-player
   ```

3. **Napravi podešavanja** (tajni ključevi se generišu automatski):
   ```bash
   cp .env.example .env
   sed -i "s/^ENCRYPTION_KEY=.*/ENCRYPTION_KEY=$(openssl rand -hex 32)/" .env
   sed -i "s/^APP_SECRET=.*/APP_SECRET=$(openssl rand -hex 32)/" .env
   sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 24)/" .env
   nano .env
   ```
   U editoru popuni `DOMAIN`, `PUBLIC_URL` (sa `https://`) i `ADMIN_PASSWORD` (bar 10 znakova), pa sačuvaj sa Ctrl+O, Enter, Ctrl+X.

4. **Pokreni**:
   ```bash
   docker compose up -d --build
   ```
   Posle 1–2 minuta sajt radi na `https://tvojdomen.com`, a panel na `https://tvojdomen.com/admin`. HTTPS sertifikat se pravi automatski.

5. **Prijavi se u panel** sa `ADMIN_USERNAME` / `ADMIN_PASSWORD`, uključi dvofaktorsku prijavu (Moj nalog), pa obriši `ADMIN_PASSWORD` iz `.env`.

## Važno

- **Sačuvaj `.env` na sigurnom mestu.** Bez `ENCRYPTION_KEY` sve sačuvane liste su nečitljive i moraju se uneti ponovo.
- `APP_SECRET` potpisuje sesije; promena odjavljuje sve admine i poništava aktivne kodove.

## Svakodnevne komande

```bash
docker compose logs -f app          # logovi servera
docker compose pull && docker compose up -d --build   # posle git pull: nova verzija
docker compose restart app          # restart

# zaboravljena lozinka admina
docker compose exec app node dist/cli/createAdmin.js admin NovaLozinka123 super

# rezervna kopija baze (pokreni npr. svake noći preko cron-a)
docker compose exec -T db pg_dump -U orbit orbit | gzip > backup-$(date +%F).sql.gz
```

Objavljeni APK fajlovi su u Docker volumenu `data` (`/data/releases` u kontejneru) — i njih uključi u rezervnu kopiju.

## Ograničenja i bezbednost

- Unos lista proverava adresu servera i odbija lokalne i privatne adrese (zaštita servera).
- Unos koda je ograničen na 8 pokušaja u minuti po IP adresi; kodovi uređaja važe 15 minuta.
- Prijava u panel se zaključava na 15 minuta posle 10 pogrešnih lozinki.
