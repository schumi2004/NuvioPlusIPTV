# Self-hosted sync (account + Live TV)

Deze branch (`feature/selfhost-sync`) synct alles via je eigen
[NuvioMedia/self-host](https://github.com/NuvioMedia/self-host)-backend in plaats van via
`api.nuvio.tv` en Google Drive:

- **Nuvio-account, profielen, library, watch-progress**: dit was al ingebouwd in de app.
  Hij gebruikt gewoon de server waarmee je verbonden bent.
- **Live TV / IPTV** (playlists inclusief Xtream-logins, EPG, favorieten, verborgen zenders,
  zendervolgorde, sports follows, Live TV-instellingen): nieuw, via
  `migrations/90000000000001_iptv_sync.sql`. Er is één kopie per account en de app voegt die
  zelf per instelling en per bron samen.

Je server wordt bij het bouwen ingebakken (`SELFHOST_BACKEND_URL`, zie stap 4) als de enige
server. De app kent dan geen officiële Nuvio-server en kan er ook niet naar overschakelen.
Live TV synct uitsluitend met zo'n self-hosted server en nooit met een server uit
`NUVIO_SUPABASE_URL`.

## 1. Backend draaien (docker-prod)

Vereist: 64-bit Linux, Docker met Compose v2, git, openssl en curl. Alles staat in `deploy/`:

| Bestand | Wat |
|---|---|
| `nuvio.env.example` | Kopieer naar `nuvio.env` (staat niet in git), met je instellingen: hostnaam, Traefik-netwerk/entrypoint/certresolver, LAN-ranges |
| `install.sh` | Installeren en bijwerken (self-host klonen/pullen, IPTV-migratie, hardening, `./nuvio up`) |
| `compose.traefik.yml` | Traefik-labels op de Nuvio-gateway (LAN-only router) |
| `traefik-dynamic.yml` | Alternatief voor je file provider |

Je hoeft niet de hele fork te clonen. Met een sparse clone haal je alleen `selfhost/` op
(minder dan 1 MB):

```sh
# op docker-prod
git clone --depth 1 --filter=blob:none --sparse -b feature/selfhost-sync     https://github.com/schumi2004/NuvioPlusIPTV ~/nuvio-deploy
git -C ~/nuvio-deploy sparse-checkout set selfhost
cd ~/nuvio-deploy/selfhost/deploy
cp nuvio.env.example nuvio.env
vi nuvio.env                 # CHANGE_ME invullen: hostnaam, certresolver, LAN-ranges
sudo mkdir -p /opt/nuvio-self-host && sudo chown "$USER" /opt/nuvio-self-host
./install.sh                 # eerste keer: genereert secrets in /opt/nuvio-self-host/.env
```

Wat `install.sh` doet:
- Kloont `NuvioMedia/self-host` naar `/opt/nuvio-self-host`, of doet daar `git pull`.
- Kopieert `migrations/*.sql` erbij. Die bestanden zijn untracked, dus een pull laat ze staan.
- Draait `./nuvio setup --domain … --proxy external --no-import-account` (alleen de eerste keer).
- Hardening in `.env`:
  - `ENABLE_ANONYMOUS_USERS=false` (de TV-app gebruikt geen anonieme accounts; de QR-login
    draait op de anon-API-rol);
  - `NUVIO_PUBLIC_SITE_URL` naar je eigen domein in plaats van nuvio.tv;
  - geen import uit Nuvio Cloud.
- Draait `./nuvio up` (migraties en health checks op `127.0.0.1:8080`) en daarna
  `./nuvio credentials`.

Bijwerken doe je met `./install.sh update`. Dat haalt eerst de nieuwste deploy-kit op met
`git pull` in de sparse clone (je `nuvio.env` blijft staan) en daarna self-host. Daarna
past het migraties toe en herstart het de stack. Maak eerst een backup (zie onder).

## 2. Traefik en DNS (alleen thuis)

**Labels (standaard, `TRAEFIK_MODE=labels`).** De Caddy-gateway van Nuvio komt op je
`proxy`-netwerk met een router voor `nuvio.example.com` en een
`ipAllowList`-middleware met `NUVIO_LAN_RANGES`. Je hoeft niets aan Traefik te veranderen.

**File provider (`TRAEFIK_MODE=file`).** Zet `traefik-dynamic.yml` in je dynamic-config-map
en hang de Traefik-container aan het externe netwerk `nuvio-proxy`.

Zet in geen van beide varianten **`mtls-required@file`**: de TV-app heeft geen
clientcertificaat. De afscherming loopt via de IP-allowlist, DNS en het account.

**Certificaat.** De host is niet vanaf internet bereikbaar, dus Let's Encrypt kan hem niet via
HTTP controleren. De certresolver moet een **DNS-challenge** gebruiken, of je gebruikt een
wildcard voor `*.example.com`. Neem dezelfde resolver als je andere interne diensten.

**DNS.** Maak in AdGuard een rewrite `nuvio.example.com` naar het IP van docker-prod.
Een publiek DNS-record is niet nodig.

**Als je een 403 krijgt:** draait Traefik met de userland-proxy (of in een netwerk waar het
client-IP verloren gaat), dan ziet Traefik `172.x` als bron-IP. Kijk in de Traefik-accesslog
welk bron-IP er binnenkomt en neem die range op in `NUVIO_LAN_RANGES`. Zet die range liever
niet blind open.

## 3. Controleren

```sh
curl https://nuvio.example.com/health
curl https://nuvio.example.com/.well-known/nuvio     # self_hosted: true
./nuvio doctor

# IPTV-RPC bestaat en weigert anonieme toegang (verwacht: 401/permission denied)
curl -s -X POST https://nuvio.example.com/rest/v1/rpc/iptv_sync_pull \
  -H "apikey: <publishable key>" -H "Content-Type: application/json" -d '{}'
```

Maak je account(s) aan via het dashboard op `https://nuvio.example.com`. Zet
daarna nieuwe aanmeldingen dicht:

```sh
./install.sh lock-signup      # DISABLE_SIGNUP=true; terug met unlock-signup
```

Nieuwe accounts krijgen standaard de catalogus-addon `catalog.nuvio.tv`; dat is Stremio-materiaal.
Wil je ook daar geen verkeer heen, verwijder dan die addon in de app of in het dashboard.

## Backups

```sh
cd /opt/nuvio-self-host && ./nuvio backup     # Postgres + Storage naar backups/
```

Draai dit dagelijks via cron en kopieer `backups/` naar de NAS. De backup bevat je
IPTV-logins, dus bewaar hem versleuteld.

## 4. App bouwen met je server ingebakken

Je server wordt de **enige** server van de app. Er is geen officiële server, geen ⋮-menu om te
switchen en geen verbinding met `api.nuvio.tv`, ook niet bij de eerste start.

Lokaal in `local.properties`:

```properties
SELFHOST_BACKEND_URL=https://nuvio.example.com
SELFHOST_PUBLISHABLE_KEY=<publishable key uit ./nuvio credentials>
```

Of in GitHub (Settings → Secrets → Actions) als `SELFHOST_BACKEND_URL` en
`SELFHOST_PUBLISHABLE_KEY`. De workflows geven ze door. Zet `NUVIO_BACKEND_URL` en
`NUVIO_BACKEND_KEY` **niet**: de build weigert elke backend-URL met `nuvio.tv` erin.

De publishable key is geen geheim (het is de anon key en elke client krijgt hem via
`/.well-known/nuvio`). De echte bescherming zit in de login en RLS.

Inloggen op de TV gaat met e-mail en wachtwoord, of met de QR-code (`/tv-login` op je server).
Accounts maak je aan op het dashboard van je server.

Live TV-instellingen → **Backup & Sync** laat daarna je server en account zien. De eerste TV
zet zijn setup op de server en elke volgende TV neemt die over.

## Privacy

- De IPTV-logins staan als JSON in Postgres (`public.iptv_sync_blobs`). Ze zijn per account
  afgeschermd met RLS en de RPC's werken alleen met een geldige sessie. Ze staan dus ook
  leesbaar in backups van het Postgres-volume, dus versleutel die.
- Verwijder je een account, dan gaat de IPTV-kopie mee (`ON DELETE CASCADE`).
- De account-export van self-host (`sync_export_account_backup`) neemt de IPTV-kopie **niet** mee.
