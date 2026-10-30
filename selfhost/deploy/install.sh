#!/bin/sh
# Installs or updates the self-hosted Nuvio backend (NuvioMedia/self-host) on docker-prod,
# with this fork's Live TV migration, hardened settings and a LAN-only Traefik route.
#
#   ./install.sh                 first install, or update (git pull, migrations, restart)
#   ./install.sh lock-signup     no new accounts (run after creating yours)
#   ./install.sh unlock-signup   allow new accounts again
#   ./install.sh credentials     show Backend URL + publishable key
#
# Settings: nuvio.env next to this script (copy nuvio.env.example; it isn't committed).
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
die() { echo "error: $*" >&2; exit 1; }

[ -f "$HERE/nuvio.env" ] || die "copy nuvio.env.example to nuvio.env and fill it in"
# shellcheck source=nuvio.env.example
. "$HERE/nuvio.env"
grep -q "=CHANGE_ME" "$HERE/nuvio.env" && die "nuvio.env still has CHANGE_ME values"

# KEY=VALUE in .env, replacing an existing line (same format ./nuvio uses).
set_env() {
    if grep -q "^$1=" .env; then
        tmp=$(mktemp ./.env.tmp.XXXXXX)
        awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; next } { print }' .env > "$tmp"
        mv "$tmp" .env
    else
        printf '%s=%s\n' "$1" "$2" >> .env
    fi
}

configure() {
    # Links in the account dashboard point to your server, not nuvio.tv.
    set_env NUVIO_PUBLIC_SITE_URL "https://$NUVIO_DOMAIN"
    # The TV app never uses anonymous accounts (TV sign-in runs on the anon API role).
    set_env ENABLE_ANONYMOUS_USERS false
    # Never import anything from Nuvio Cloud.
    set_env IMPORT_ACCOUNT_AFTER_UP false

    compose_files="compose.yml:compose.external-proxy.yml"
    if [ "$TRAEFIK_MODE" = "labels" ]; then
        cp "$HERE/compose.traefik.yml" ./compose.traefik.yml
        compose_files="$compose_files:compose.traefik.yml"
        set_env TRAEFIK_NETWORK "$TRAEFIK_NETWORK"
        set_env TRAEFIK_ENTRYPOINT "$TRAEFIK_ENTRYPOINT"
        set_env TRAEFIK_CERTRESOLVER "$TRAEFIK_CERTRESOLVER"
        set_env TRAEFIK_IPALLOW_KEY "$TRAEFIK_IPALLOW_KEY"
        set_env NUVIO_LAN_RANGES "$NUVIO_LAN_RANGES"
    fi
    set_env COMPOSE_FILE "$compose_files"
    chmod 600 .env
}

install_or_update() {
    command -v docker >/dev/null 2>&1 || die "docker is required"
    if [ -d "$NUVIO_DIR/.git" ]; then
        git -C "$NUVIO_DIR" pull --ff-only
    else
        git clone https://github.com/NuvioMedia/self-host "$NUVIO_DIR"
    fi
    # This fork's migrations (untracked in the checkout, so git pull leaves them alone).
    cp "$HERE"/../migrations/*.sql "$NUVIO_DIR/database/migrations/"

    cd "$NUVIO_DIR"
    if [ ! -f .env ]; then
        ./nuvio setup --domain "$NUVIO_DOMAIN" --proxy external --no-import-account
    fi
    configure
    ./nuvio up
    ./nuvio credentials
}

signup() {
    cd "$NUVIO_DIR"
    [ -f .env ] || die "not installed yet"
    set_env DISABLE_SIGNUP "$1"
    ./nuvio up
}

case "${1:-install}" in
    install|update) install_or_update ;;
    lock-signup) signup true ;;
    unlock-signup) signup false ;;
    credentials) cd "$NUVIO_DIR" && ./nuvio credentials ;;
    *) die "unknown command: $1" ;;
esac
