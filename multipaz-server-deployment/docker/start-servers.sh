#!/bin/sh

# Multipaz Server Bundle Startup Script
# Starts all servers with configurable base URLs

set -e

# Profile: "full" (default) starts every reference server; "validatopia" starts only the
# openid4vci (issuer/admin site) and backend (device-attestation) services behind a hardened
# nginx config (docs/validatopia/PLAN.md's Component G), serving the wallet API on port 6000 and
# the admin site on port 6001.
PROFILE="${PROFILE:-full}"

# Base URL for proxy mode (nginx routes by path). For the validatopia profile it's the wallet API's
# address (port 6000); the admin site is on the same host at port 6001.
if [ "$PROFILE" = "validatopia" ]; then
  BASE_URL="${BASE_URL:-http://localhost:6000}"
else
  BASE_URL="${BASE_URL:-http://localhost:8000}"
fi

no_protocol="${BASE_URL#*://}"      # strip protocol
host_port="${no_protocol%%/*}"     # strip path
host="${host_port%:*}"  # strip port

is_loopback_host() {
  [ "$1" = "localhost" ] || [ "$1" = "127.0.0.1" ] || [ "$1" = "::1" ] || [ -z "$1" ]
}

# Mode: "proxy" (default) routes through nginx; "direct" exposes ports directly
MODE="${MODE:-proxy}"

# Additional params that can be passed to all servers
EXTRA_PARAMS="${EXTRA_PARAMS:-}"

if [ "$PROFILE" = "validatopia" ]; then
  # Refuses to launch the JVM at all with an empty bootstrap password on a non-loopback
  # base_url (docs/validatopia/PLAN.md's Component E). AdminAuth.ensureBootstrapped() repeats
  # this check inside the JVM as defense in depth, in case something starts the JVM some other
  # way, but this is the primary, fail-fast gate.
  if [ -z "$ADMIN_BOOTSTRAP_PASS" ] && ! is_loopback_host "$host"; then
    echo "ERROR: ADMIN_BOOTSTRAP_PASS must be set when BASE_URL ('$BASE_URL') is not a loopback" \
         "address. Refusing to start."
    exit 1
  fi
else
  if [ -z "$ADMIN_PASS" ] ; then
    if is_loopback_host "$host" ; then
      ADMIN_PASS=multipaz
      echo "Admin password is set to 'multipaz'"
    else
      echo "ERROR: ADMIN_PASS must be set when BASE_URL ('$BASE_URL') is not a loopback address." \
           "Refusing to start."
      exit 1
    fi
  fi
fi

echo "=========================================="
echo "Multipaz Server Bundle"
echo "=========================================="
echo "Profile: ${PROFILE}"
echo "Mode: ${MODE}"
echo "Base URL: ${BASE_URL}"
echo "=========================================="
echo ""

pids=""

service () {
  local service="$1"
  shift
  local instance="$1"
  shift
  local mainclass="$1"
  shift
  local port="$1"
  shift
  local extra="$EXTRA_PARAMS"
  if [ "records" = "$instance" ] ; then
    extra="$extra -param ca_allow_enrollment=[\"$host\"]"
    extra="$extra -param issuer_url=${BASE_URL}/openid4vci"
  else
    if [ "openid4vci" = "$instance" ] ; then
      extra="$extra -param system_of_record_url=${BASE_URL}/records"
    fi
    # The validatopia profile doesn't run the records server, so its servers self-enroll their
    # identities; the issuer's under the fixed Validatopia TEST IACA from validatopia-keys.conf.
    if [ "$PROFILE" != "validatopia" ]; then
      extra="$extra -param enrollment_server_url=${BASE_URL}/records"
    fi
  fi
  echo "Starting $service service ($instance) at port $port..."
  java -cp "/app/jars/$service-server.jar:/app/jars/$service.jar:/app/libs/*" "$mainclass" \
    -param server_port=$port \
    -param base_url=${BASE_URL}/$instance \
    -param ca_trust_servers="[\"$host\"]" \
    -param server_trace_file="/app/logs/$instance-trace.log" \
    -param database_connection="jdbc:sqlite:/app/data/$instance.db" \
    -config "/etc/multipaz/$instance.conf" \
    $extra \
    $* \
    > "/app/logs/$instance.log" 2>&1 &
  pids="$pids $!"
  echo "  PID: $!"
}

setup_tls() {
  # Populates the include target nginx-validatopia.conf ends its http{} block with. Left as an
  # empty file (no TLS server block) unless both TLS_CERT and TLS_KEY point to readable files.
  tls_conf=/etc/nginx/conf.d/validatopia-tls-server.conf
  mkdir -p /etc/nginx/conf.d
  : > "$tls_conf"
  if [ -n "$TLS_CERT" ] && [ -n "$TLS_KEY" ]; then
    if [ -r "$TLS_CERT" ] && [ -r "$TLS_KEY" ]; then
      cat > "$tls_conf" <<EOF
server {
    listen 8443 ssl;
    server_name _;

    ssl_certificate     $TLS_CERT;
    ssl_certificate_key $TLS_KEY;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    include /etc/nginx/conf.d/validatopia-wallet.conf;
}

server {
    listen 8444 ssl;
    server_name _;

    ssl_certificate     $TLS_CERT;
    ssl_certificate_key $TLS_KEY;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    include /etc/nginx/conf.d/validatopia-admin.conf;
}
EOF
      echo "TLS enabled on ports 8443 (wallet API) and 8444 (admin site) (TLS_CERT=$TLS_CERT)"
    else
      echo "WARNING: TLS_CERT/TLS_KEY set but not both readable; serving HTTP only"
    fi
  fi
}

# Start nginx if in proxy mode, must be first, as services will want to connect to each other
if [ "$MODE" = "proxy" ]; then
    if [ "$PROFILE" = "validatopia" ]; then
        setup_tls
        cp /etc/nginx/nginx-validatopia.conf /etc/nginx/nginx.conf
    fi
    if [ "$PROFILE" = "validatopia" ]; then
        echo "Starting nginx reverse proxy: wallet API on port 6000, admin site on port 6001..."
    else
        echo "Starting nginx reverse proxy on port 8000..."
    fi
    nginx -g 'daemon off;' &
    NGINX_PID=$!
    echo "  PID: ${NGINX_PID}"
    pids="$pids ${NGINX_PID}"
fi

if [ "$PROFILE" = "validatopia" ]; then
  # Personas: an admin-uploaded personas.json (persisted in the database) always wins. On first
  # boot, if /app/data/personas doesn't exist yet, seed it from the placeholder personas baked
  # into the image, so the container starts demoable without requiring an upload first
  # (docs/validatopia/PLAN.md's Component G: "the image contains ... placeholder personas").
  if [ ! -d /app/data/personas ] && [ -d /app/seed/personas ]; then
    cp -r /app/seed/personas /app/data/personas
  fi

  # Fixed Validatopia TEST PKI (multipaz-server-deployment/validatopia-test-keys/); mount a file
  # holding your own keys and point VALIDATOPIA_KEYS_CONF at it for anything beyond a demo.
  service openid4vci openid4vci org.multipaz.openid4vci.server.MainValidatopia 8007 \
    -config "${VALIDATOPIA_KEYS_CONF:-/etc/multipaz/validatopia-keys.conf}" \
    -param admin_bootstrap_user="${ADMIN_BOOTSTRAP_USER:-admin}" \
    -param admin_bootstrap_pass="$ADMIN_BOOTSTRAP_PASS" \
    -param admin_allow_cidr="$ADMIN_ALLOW_CIDR" \
    -param idv_demo_mode="${IDV_DEMO_MODE:-false}" \
    -param personas_seed_dir=/app/data/personas \
    -param preauthorized_offer_secret="$(head -c 24 /dev/urandom | base64)"
  service backend backend org.multipaz.backend.server.Main 8008
else
  # Check if DB exists before launching services (DB may be mounted from outside)
  if [ -r /app/data/records.db ]
  then
     INIT=0
  else
     INIT=1
  fi

  # records server must be started first, as it processes enrollments
  service records records org.multipaz.records.server.Main 8004 -param admin_password=$ADMIN_PASS
  service openid4vci openid4vci org.multipaz.openid4vci.server.Main 8007 -param admin_password=$ADMIN_PASS
  service csa csa org.multipaz.csa.server.Main 8005
  service verifier verifier org.multipaz.verifier.server.Main 8006
  service backend backend org.multipaz.backend.server.Main 8008

  if [ "$INIT" = "0" ]
  then
  echo "System of Records database exists, not loading initial data"
  else
  echo "Loading initial data into the System of Records..."
  (
    echo '{'
    echo '"password": "'$ADMIN_PASS'",'
    echo '"identities":'
    cat /app/init/records.json
    echo '}'
  ) | curl --retry-connrefused --retry 5 -H "Content-Type: application/json" -d @- http://localhost:8004/identity/load
  fi
fi

echo ""
echo "All services started."

echo ""
echo "=========================================="
echo "Multipaz Server Bundle is running"
if [ "$MODE" = "proxy" ]; then
    echo "Access via: ${BASE_URL}"
else
    echo "Access services directly on ports 8004-8008"
fi
echo "=========================================="
echo ""

# Handle shutdown gracefully
cleanup() {
    echo "Shutting down, killing $pids..."
    kill $pids 2>/dev/null || true
    exit 0
}

trap cleanup TERM INT
wait

exit 1
