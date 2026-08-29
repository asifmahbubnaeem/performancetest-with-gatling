#!/usr/bin/env bash
# =============================================================================
# One-shot monitoring stack bootstrap for a (new) EC2 box.
#
# Usage:
#   cp .env.example .env      # edit values first
#   chmod +x setup.sh
#   ./setup.sh                          # cAdvisor included (default)
#   ENABLE_CADVISOR=false ./setup.sh    # cAdvisor left out entirely
#
# cAdvisor measured at ~0.75 of a CPU core continuously on a 4-vCPU soak-test
# box (2026-08-29) — a real, quantified contributor to host CPU pressure
# during a soak run, distinct from the rest of the stack (prometheus/grafana/
# node-exporter/postgres-exporter were all <2% CPU in the same sample).
# ENABLE_CADVISOR lets you run the soak test with and without it to isolate
# how much of any given sys-load reading is monitoring overhead vs. the app/
# DB/search stack's own demand. To toggle it on an already-running stack
# without re-running this whole script, see "Toggling cAdvisor" printed at
# the end.
#
# What it does:
#   1. Sanity checks (docker, compose, .env)
#   2. Detects the EC2 private IP and generates prometheus.yml
#   3. Verifies chosen host ports are free
#   4. Confirms the app docker network + postgres container exist
#   5. docker compose up -d
#   6. Verifies every exporter + Prometheus target, prints next steps
# =============================================================================
set -euo pipefail

info()  { echo -e "\033[1;34m[setup]\033[0m $*"; }
ok()    { echo -e "\033[1;32m[ ok ]\033[0m $*"; }
fail()  { echo -e "\033[1;31m[fail]\033[0m $*"; exit 1; }

# --- 1. Prerequisites --------------------------------------------------------
command -v docker >/dev/null || fail "docker not installed"
docker compose version >/dev/null 2>&1 || fail "docker compose v2 not available"
[ -f .env ] || fail "no .env file — run: cp .env.example .env  (then edit it)"
set -a; source .env; set +a
: "${PG_MONITOR_USER:?set in .env}"; : "${PG_MONITOR_PASSWORD:?set in .env}"
: "${APP_NETWORK:?set in .env}";     : "${PG_CONTAINER_NAME:?set in .env}"

# audit cluster is a separate Postgres instance (AUDIT_DATABASE_POOL_MAX pool,
# distinct from the main advance pool) — falls back to the main monitor
# creds if you provisioned the same role/password on both clusters
AUDIT_PG_CONTAINER_NAME="${AUDIT_PG_CONTAINER_NAME:-isaraadvance-audit-postgres-1}"
AUDIT_PG_DATABASE="${AUDIT_PG_DATABASE:-audit_logs}"
AUDIT_PG_MONITOR_USER="${AUDIT_PG_MONITOR_USER:-$PG_MONITOR_USER}"
AUDIT_PG_MONITOR_PASSWORD="${AUDIT_PG_MONITOR_PASSWORD:-$PG_MONITOR_PASSWORD}"
# The official postgres image makes its superuser role AS whatever
# POSTGRES_USER (DATABASE_ADMIN_USER / AUDIT_DATABASE_USER) was set to at
# initdb on THIS deployment — there is no fixed default. Guessing it wrong
# fails loudly ("role ... does not exist"), so require it explicitly rather
# than defaulting. Verify with:
#   docker exec <container> env | grep -i POSTGRES_USER
: "${PG_SUPERUSER:?set in .env — see docker exec ... env | grep POSTGRES_USER}"
: "${AUDIT_PG_SUPERUSER:?set in .env — see docker exec ... env | grep POSTGRES_USER}"

# Env var (not .env-only) so a one-off `ENABLE_CADVISOR=false ./setup.sh`
# works without editing .env. Accepts true/false; anything else is rejected
# rather than silently falling back, since a typo here would otherwise
# silently include or exclude a real CPU-cost container.
ENABLE_CADVISOR="${ENABLE_CADVISOR:-true}"
case "${ENABLE_CADVISOR}" in
  true|false) ;;
  *) fail "ENABLE_CADVISOR must be 'true' or 'false', got '${ENABLE_CADVISOR}'" ;;
esac
ok "prerequisites"

# --- 2. Private IP + prometheus.yml -----------------------------------------
PRIVATE_IP=$(hostname -I | awk '{print $1}')
info "EC2 private IP: ${PRIVATE_IP}"

cat > prometheus.yml <<EOF
global:
  scrape_interval: 5s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/rules.yml

scrape_configs:
  - job_name: node
    static_configs:
      - targets: ["${PRIVATE_IP}:9100"]

  - job_name: postgres
    static_configs:
      - targets: ["${PRIVATE_IP}:${PGEXPORTER_PORT:-9187}"]
        labels:
          pool: advance-app

  - job_name: postgres-audit
    static_configs:
      - targets: ["${PRIVATE_IP}:${AUDIT_PGEXPORTER_PORT:-9188}"]
        labels:
          pool: audit
EOF
# cadvisor job appended separately (not unconditionally above) so that with
# ENABLE_CADVISOR=false, prometheus never scrapes a target that was
# deliberately never started — leaving it in would show as a permanently
# "down" target and would trip the target-health check in step 6 for a
# reason that has nothing to do with a real problem.
if [ "${ENABLE_CADVISOR}" = "true" ]; then
  cat >> prometheus.yml <<EOF

  - job_name: cadvisor
    static_configs:
      - targets: ["${PRIVATE_IP}:${CADVISOR_PORT:-8081}"]
EOF
fi
ok "generated prometheus.yml (with rules.yml + audit exporter wired in; cadvisor ${ENABLE_CADVISOR})"

# --- 3. Port availability ----------------------------------------------------
PORTS_TO_CHECK=("${PROM_PORT:-9000}" "${GRAFANA_PORT:-8443}" 9100 \
                "${PGEXPORTER_PORT:-9187}" "${AUDIT_PGEXPORTER_PORT:-9188}")
[ "${ENABLE_CADVISOR}" = "true" ] && PORTS_TO_CHECK+=("${CADVISOR_PORT:-8081}")
for p in "${PORTS_TO_CHECK[@]}"; do
  if ss -tln "( sport = :$p )" | grep -q ":$p"; then
    # tolerate ports already held by OUR containers (re-run scenario)
    if docker ps --format '{{.Names}} {{.Ports}}' | grep -q ":$p->"; then
      info "port $p already used by an existing stack container (ok, will recreate)"
    else
      fail "port $p is in use by something else — change it in .env"
    fi
  fi
done
ok "ports available"

# --- 4. App network + postgres reachable -------------------------------------
docker network inspect "${APP_NETWORK}" >/dev/null 2>&1 \
  || fail "docker network '${APP_NETWORK}' not found — check APP_NETWORK in .env"
docker ps --format '{{.Names}}' | grep -qx "${PG_CONTAINER_NAME}" \
  || fail "postgres container '${PG_CONTAINER_NAME}' not running — check PG_CONTAINER_NAME"
docker ps --format '{{.Names}}' | grep -qx "${AUDIT_PG_CONTAINER_NAME}" \
  || fail "audit-postgres container '${AUDIT_PG_CONTAINER_NAME}' not running — check AUDIT_PG_CONTAINER_NAME"
ok "app network + both postgres containers found"

# --- 4b. Postgres prep: monitor role + pg_stat_statements (idempotent) --------
PSQL="docker exec -i ${PG_CONTAINER_NAME} psql -U ${PG_SUPERUSER} -d postgres -v ON_ERROR_STOP=1"

info "ensuring '${PG_MONITOR_USER}' role exists (password synced from .env)..."
$PSQL >/dev/null <<SQL || fail "could not create/update ${PG_MONITOR_USER} role"
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${PG_MONITOR_USER}') THEN
    CREATE ROLE ${PG_MONITOR_USER} LOGIN PASSWORD '${PG_MONITOR_PASSWORD}';
  ELSE
    ALTER ROLE ${PG_MONITOR_USER} WITH LOGIN PASSWORD '${PG_MONITOR_PASSWORD}';
  END IF;
  GRANT pg_monitor TO ${PG_MONITOR_USER};
END
\$\$;
SQL
ok "monitor role ready"

AUDIT_PSQL="docker exec -i ${AUDIT_PG_CONTAINER_NAME} psql -U ${AUDIT_PG_SUPERUSER} -d postgres -v ON_ERROR_STOP=1"
info "ensuring '${AUDIT_PG_MONITOR_USER}' role exists on the audit cluster..."
$AUDIT_PSQL >/dev/null <<SQL || fail "could not create/update ${AUDIT_PG_MONITOR_USER} role on audit cluster"
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${AUDIT_PG_MONITOR_USER}') THEN
    CREATE ROLE ${AUDIT_PG_MONITOR_USER} LOGIN PASSWORD '${AUDIT_PG_MONITOR_PASSWORD}';
  ELSE
    ALTER ROLE ${AUDIT_PG_MONITOR_USER} WITH LOGIN PASSWORD '${AUDIT_PG_MONITOR_PASSWORD}';
  END IF;
  GRANT pg_monitor TO ${AUDIT_PG_MONITOR_USER};
END
\$\$;
SQL
ok "audit monitor role ready"

info "checking pg_stat_statements..."
PRELOAD=$($PSQL -tA -c "SHOW shared_preload_libraries;" 2>/dev/null | tr -d ' ')
if ! echo "${PRELOAD}" | grep -q "pg_stat_statements"; then
  if [ -n "${PRELOAD}" ]; then NEWVAL="${PRELOAD},pg_stat_statements"; else NEWVAL="pg_stat_statements"; fi
  info "preloading pg_stat_statements (requires a postgres RESTART — app will briefly lose DB connections)"
  $PSQL -c "ALTER SYSTEM SET shared_preload_libraries = '${NEWVAL}';" >/dev/null
  docker restart "${PG_CONTAINER_NAME}" >/dev/null
  # wait for postgres to come back
  for i in $(seq 1 30); do
    docker exec "${PG_CONTAINER_NAME}" pg_isready -U "${PG_SUPERUSER}" >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "${PG_CONTAINER_NAME}" pg_isready -U "${PG_SUPERUSER}" >/dev/null 2>&1 \
    || fail "postgres did not come back after restart"
  ok "pg_stat_statements preloaded (postgres restarted)"
else
  ok "pg_stat_statements already preloaded"
fi

# create the extension explicitly in public (avoids landing in an app schema
# via search_path), or relocate it if a previous run put it elsewhere
EXT_SCHEMA=$($PSQL -d "${PG_DATABASE}" -tA -c \
  "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON e.extnamespace=n.oid WHERE e.extname='pg_stat_statements';" | tr -d ' ')
if [ -z "${EXT_SCHEMA}" ]; then
  $PSQL -d "${PG_DATABASE}" -c "CREATE EXTENSION pg_stat_statements SCHEMA public;" >/dev/null \
    || fail "could not create pg_stat_statements extension"
  ok "pg_stat_statements extension created in public"
elif [ "${EXT_SCHEMA}" != "public" ]; then
  $PSQL -d "${PG_DATABASE}" -c "ALTER EXTENSION pg_stat_statements SET SCHEMA public;" >/dev/null \
    || fail "could not move pg_stat_statements to public schema"
  ok "pg_stat_statements moved from '${EXT_SCHEMA}' to public"
else
  ok "pg_stat_statements extension present in public"
fi

# --- 5. Bring it up -----------------------------------------------------------
info "starting stack (cadvisor ${ENABLE_CADVISOR})..."
COMPOSE_PROFILE_ARGS=()
[ "${ENABLE_CADVISOR}" = "true" ] && COMPOSE_PROFILE_ARGS=(--profile cadvisor)
docker compose "${COMPOSE_PROFILE_ARGS[@]}" up -d
sleep 8

# --- 6. Verify ----------------------------------------------------------------
curl -sf "localhost:9100/metrics" >/dev/null            && ok "node_exporter (:9100)"        || fail "node_exporter not responding"
if [ "${ENABLE_CADVISOR}" = "true" ]; then
  curl -sf "localhost:${CADVISOR_PORT:-8081}/metrics" >/dev/null \
                                                          && ok "cadvisor (:${CADVISOR_PORT:-8081})" || fail "cadvisor not responding"
else
  info "cadvisor skipped (ENABLE_CADVISOR=false)"
fi
PGUP=$(curl -sf "localhost:${PGEXPORTER_PORT:-9187}/metrics" | grep -E '^pg_up ' | awk '{print $2}')
[ "${PGUP}" = "1" ] && ok "postgres_exporter connected (pg_up 1)" \
  || fail "postgres_exporter up but pg_up=${PGUP:-none} — check DSN/network/role"
AUDIT_PGUP=$(curl -sf "localhost:${AUDIT_PGEXPORTER_PORT:-9188}/metrics" | grep -E '^pg_up ' | awk '{print $2}')
[ "${AUDIT_PGUP}" = "1" ] && ok "postgres_exporter-audit connected (pg_up 1)" \
  || fail "postgres_exporter-audit up but pg_up=${AUDIT_PGUP:-none} — check AUDIT_PG_* DSN/network/role"
curl -sf "localhost:${PROM_PORT:-9000}/-/healthy" >/dev/null && ok "prometheus (:${PROM_PORT:-9000})" || fail "prometheus not healthy"

sleep 7   # give prometheus one scrape + rule-evaluation cycle
DOWN=$(curl -s "localhost:${PROM_PORT:-9000}/api/v1/targets" \
       | grep -o '"health":"[a-z]*"' | grep -cv '"health":"up"' || true)
[ "${DOWN}" = "0" ] && ok "all prometheus targets UP" \
  || info "warning: ${DOWN} target(s) not up yet — check :${PROM_PORT:-9000}/targets"

# Confirms rules.yml actually parsed and loaded — a bad rule_files path fails
# silently otherwise (prometheus stays "healthy", the alerts just never
# exist), which would quietly defeat the connection-exhaustion pass/fail
# signal for the whole run.
RULE_COUNT=$(curl -s "localhost:${PROM_PORT:-9000}/api/v1/rules" \
             | grep -o '"name":"[A-Za-z]*Exhaust[A-Za-z]*"' | sort -u | wc -l)
[ "${RULE_COUNT}" -ge 2 ] && ok "connection-exhaustion alert rules loaded (${RULE_COUNT} found)" \
  || fail "connection-exhaustion rules did not load — check rules.yml mount and prometheus.yml rule_files"

# --- Done ----------------------------------------------------------------------
echo
ok "monitoring stack ready"
cat <<EOF

Next steps:
  1. Grafana:     http://<ec2-public-ip>:${GRAFANA_PORT:-8443}   (admin / your .env password)
  2. Data source: Prometheus -> URL: http://prometheus:9000  (same compose network)
  3. Import dashboards: 1860 (node), 14282 (cadvisor), 9628 (postgres)
  4. Set each dashboard to 'Last 30 minutes' + 5s refresh and save as default
  5. Security group: open ${GRAFANA_PORT:-8443} and ${PROM_PORT:-9000} to YOUR IP only
  6. Connection-exhaustion check post-run:
       curl -s localhost:${PROM_PORT:-9000}/api/v1/alerts | jq '.data.alerts[] | select(.state=="firing")'
     Non-empty = FAIL on GA criterion #10295 "no connection exhaustion"

 1. Check Prometheus targets are all healthy: http://<ec2-ip>:${PROM_PORT:-9000}/targets — every job (node, cadvisor, postgres, postgres-audit) should show UP. If node is down, that's
     the host.docker.internal issue we already guarded with extra_hosts, but worth confirming.
 2. Confirm the alert rules actually loaded (this was the whole point of the rules.yml work): curl -s http://<ec2-ip>:${PROM_PORT:-9000}/api/v1/rules | jq 
     '.data.groups[].rules[].name' — should list the 4 exhaustion alerts.
 3. Grafana: log in at http://<ec2-ip>:${GRAFANA_PORT:-8443} (admin / your GRAFANA_ADMIN_PASSWORD), add Prometheus as a data source pointing at http://prometheus:9090
     (container-to-container, same monitoring network), then Dashboards → Import and paste these IDs:
     - 1860 — Node Exporter Full
     - 9628 — PostgreSQL Database (works for both postgres and postgres-audit jobs via the pool label)
     - cAdvisor dashboard once #4 below is resolved (14282 or 19792 are the common ones for v0.49.x)


Toggling cAdvisor on an already-running stack (no need to re-run this script):
  Stop it (rest of the stack keeps running):
    docker compose stop cadvisor
  Start it again:
    docker compose --profile cadvisor up -d cadvisor
  Re-run this script with the other mode instead (recreates prometheus.yml too):
    ENABLE_CADVISOR=false ./setup.sh    # or true

Teardown:  docker compose --profile cadvisor down          (keep data)
           docker compose --profile cadvisor down -v       (wipe metrics + dashboards)
           (add --profile cadvisor so a currently-running cadvisor container is
           actually included in the teardown, matching whatever it was up with)
EOF