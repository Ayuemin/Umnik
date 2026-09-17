#!/usr/bin/env bash
set -euo pipefail

# Umnik Personal Server installer (beta).
# Target: a fresh Debian/Ubuntu VPS with a public IPv4 address and ports 22/80/443 reachable.
# Required environment: OPENROUTER_API_KEY
# Optional: UMNIK_SERVER_TOKEN, UMNIK_PUBLIC_IP, UMNIK_GIT_REF, LETSENCRYPT_EMAIL

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root (or via sudo)." >&2
  exit 1
fi

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY is required." >&2
  exit 1
fi

case "$(. /etc/os-release 2>/dev/null; echo "${ID:-}")" in
  ubuntu|debian) ;;
  *)
    echo "This beta installer currently supports Debian/Ubuntu only." >&2
    exit 1
    ;;
esac

export DEBIAN_FRONTEND=noninteractive
INSTALL_ROOT=/opt/umnik-server
REPO_DIR="$INSTALL_ROOT/repo"
CERTBOT_DIR=/opt/umnik-certbot
WEBROOT=/var/www/umnik-certbot
GIT_REF="${UMNIK_GIT_REF:-test/server-mode-v1.18}"
REPO_URL="https://github.com/Ayuemin/Umnik.git"

apt-get update
apt-get install -y ca-certificates curl git nginx python3 python3-venv openssl

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
  sh /tmp/get-docker.sh
  rm -f /tmp/get-docker.sh
fi
systemctl enable --now docker

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose plugin is required but was not installed." >&2
  exit 1
fi

mkdir -p "$INSTALL_ROOT" "$WEBROOT/.well-known/acme-challenge"
if [ -d "$REPO_DIR/.git" ]; then
  git -C "$REPO_DIR" fetch origin "$GIT_REF" --depth=1
  git -C "$REPO_DIR" checkout -f FETCH_HEAD
else
  rm -rf "$REPO_DIR"
  git clone --depth=1 --branch "$GIT_REF" "$REPO_URL" "$REPO_DIR"
fi

SERVER_TOKEN="${UMNIK_SERVER_TOKEN:-}"
if [ -z "$SERVER_TOKEN" ]; then
  SERVER_TOKEN="$(openssl rand -hex 32)"
fi

PUBLIC_IP="${UMNIK_PUBLIC_IP:-}"
if [ -z "$PUBLIC_IP" ]; then
  PUBLIC_IP="$(curl -4fsS --max-time 10 https://api.ipify.org || true)"
fi
if ! printf '%s' "$PUBLIC_IP" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
  echo "Could not determine a public IPv4 address. Set UMNIK_PUBLIC_IP explicitly." >&2
  exit 1
fi

cat > "$REPO_DIR/server/.env" <<EOF
OPENROUTER_API_KEY=$OPENROUTER_API_KEY
UMNIK_SERVER_TOKEN=$SERVER_TOKEN
EOF
chmod 600 "$REPO_DIR/server/.env"

(
  cd "$REPO_DIR/server"
  docker compose up -d --build
)

# Serve HTTP-01 challenges before requesting the IP certificate.
cat > /etc/nginx/sites-available/umnik-server <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name $PUBLIC_IP;

    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
    }

    location / {
        return 404;
    }
}
EOF
rm -f /etc/nginx/sites-enabled/default
ln -sfn /etc/nginx/sites-available/umnik-server /etc/nginx/sites-enabled/umnik-server
nginx -t
systemctl enable --now nginx
systemctl reload nginx

if [ ! -x "$CERTBOT_DIR/bin/certbot" ]; then
  python3 -m venv "$CERTBOT_DIR"
  "$CERTBOT_DIR/bin/pip" install --upgrade pip
  "$CERTBOT_DIR/bin/pip" install 'certbot>=5.4,<6'
fi

CERTBOT_ARGS=(
  certonly
  --non-interactive
  --agree-tos
  --preferred-profile shortlived
  --webroot
  --webroot-path "$WEBROOT"
  --ip-address "$PUBLIC_IP"
)
if [ -n "${LETSENCRYPT_EMAIL:-}" ]; then
  CERTBOT_ARGS+=(--email "$LETSENCRYPT_EMAIL")
else
  CERTBOT_ARGS+=(--register-unsafely-without-email)
fi

"$CERTBOT_DIR/bin/certbot" "${CERTBOT_ARGS[@]}"

CERT_DIR="/etc/letsencrypt/live/$PUBLIC_IP"
if [ ! -s "$CERT_DIR/fullchain.pem" ] || [ ! -s "$CERT_DIR/privkey.pem" ]; then
  echo "Certificate files were not created at $CERT_DIR." >&2
  exit 1
fi

cat > /etc/nginx/sites-available/umnik-server <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name $PUBLIC_IP;

    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name $PUBLIC_IP;

    ssl_certificate $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 55m;
    proxy_connect_timeout 30s;
    proxy_read_timeout 90s;
    proxy_send_timeout 90s;

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
EOF
nginx -t
systemctl reload nginx

cat > /usr/local/sbin/umnik-renew-cert <<EOF
#!/usr/bin/env bash
set -euo pipefail
"$CERTBOT_DIR/bin/certbot" renew --quiet --deploy-hook 'systemctl reload nginx'
EOF
chmod 700 /usr/local/sbin/umnik-renew-cert

cat > /etc/systemd/system/umnik-cert-renew.service <<'EOF'
[Unit]
Description=Renew Umnik HTTPS certificate
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/umnik-renew-cert
EOF

cat > /etc/systemd/system/umnik-cert-renew.timer <<'EOF'
[Unit]
Description=Regularly renew Umnik HTTPS certificate

[Timer]
OnBootSec=15min
OnUnitActiveSec=12h
RandomizedDelaySec=30min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now umnik-cert-renew.timer

if ! curl -fsS --max-time 15 "https://$PUBLIC_IP/health" | grep -q '"ok"'; then
  echo "Installation completed, but HTTPS health check failed." >&2
  echo "Check that inbound TCP ports 80 and 443 are open in the VPS firewall/provider panel." >&2
  exit 1
fi

cat <<EOF

Umnik Personal Server is ready.

Server address: https://$PUBLIC_IP
Server token:   $SERVER_TOKEN

Save the token now. In Umnik beta select:
Settings -> OpenRouter -> Through server
and enter the address and token above.
EOF
