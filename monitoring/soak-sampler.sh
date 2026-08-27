#!/usr/bin/env bash
# =============================================================================
# Supplementary soak-run sampler — installs itself as a cron job that samples
# signals NOT already covered by the Prometheus stack (monitoring/docker-
# compose.yml), every minute, for the duration of one soak run:
#
#   - backend container fd count + ulimit -n           (fd exhaustion)
#   - backend container TCP socket state (ss -s)        (port/TIME-WAIT exhaustion)
#   - host conntrack table occupancy                    (conntrack exhaustion)
#   - NEW "timeout exceeded when trying to connect" lines in the backend log
#     since the last sample                              (DB pool exhaustion evidence)
#
# CPU/memory/DB-pool-size trends are already captured at 5s resolution by the
# Prometheus stack (node-exporter/cadvisor/postgres-exporter) — this script
# does not duplicate those. generate_soak_reports.py reads both sources.
#
# Usage (run ON the EC2 box, from this monitoring/ directory):
#   ./soak-sampler.sh start <run-id>     # installs the cron entry, writes CSV header
#   ./soak-sampler.sh sample <run-id>    # what cron actually invokes each minute
#   ./soak-sampler.sh stop <run-id>      # removes the cron entry
#
# Config (env vars, same pattern as monitoring/.env):
#   BACKEND_CONTAINER   default: isaraadvance-backend-1
#   BACKEND_LOG_PATH    path to the backend log INSIDE the container — required,
#                        no safe default (verify with: docker exec <container> find / -maxdepth 3 -iname '*.log')
#   SAMPLE_DIR           default: ./soak-samples  (relative to this script)
# =============================================================================
set -euo pipefail

info()  { echo -e "\033[1;34m[sampler]\033[0m $*"; }
fail()  { echo -e "\033[1;31m[fail]\033[0m $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${SCRIPT_DIR}/.env" ] && { set -a; source "${SCRIPT_DIR}/.env"; set +a; }

BACKEND_CONTAINER="${BACKEND_CONTAINER:-isaraadvance-backend-1}"
BACKEND_LOG_PATH="${BACKEND_LOG_PATH:-/var/log/isara/advance.backend.log}"
SAMPLE_DIR="${SAMPLE_DIR:-${SCRIPT_DIR}/soak-samples}"

CMD="${1:-}"
RUN_ID="${2:-}"
[ -n "$CMD" ] || fail "usage: $0 {start|sample|stop} <run-id>"
[ -n "$RUN_ID" ] || fail "usage: $0 {start|sample|stop} <run-id> — pick a run-id, e.g. soak-2026-08-27"

RUN_DIR="${SAMPLE_DIR}/${RUN_ID}"
CSV="${RUN_DIR}/sampler.csv"
LOG_OFFSET_FILE="${RUN_DIR}/log-line-offset"
CRON_TAG="# soak-sampler:${RUN_ID}"

csv_header() {
  echo "epoch,iso_time,fd_count,ulimit_n,tcp_estab,tcp_timewait,conntrack_count,conntrack_max,new_pgpool_timeout_errors"
}

case "$CMD" in
  start)
    [ -n "$BACKEND_LOG_PATH" ] || fail "BACKEND_LOG_PATH not set — export it or set it in monitoring/.env first"
    docker ps --format '{{.Names}}' | grep -qx "$BACKEND_CONTAINER" \
      || fail "backend container '$BACKEND_CONTAINER' not running — set BACKEND_CONTAINER"
    docker exec "$BACKEND_CONTAINER" test -r "$BACKEND_LOG_PATH" \
      || fail "cannot read '$BACKEND_LOG_PATH' inside $BACKEND_CONTAINER — check BACKEND_LOG_PATH"

    mkdir -p "$RUN_DIR"
    [ -f "$CSV" ] || csv_header > "$CSV"
    # baseline the log offset so the first sample() only counts NEW lines
    # written after start, not the container's entire history
    docker exec "$BACKEND_CONTAINER" sh -c "wc -l < '$BACKEND_LOG_PATH'" | tr -d ' ' > "$LOG_OFFSET_FILE"

    SELF="$(readlink -f "${BASH_SOURCE[0]}")"
    CRON_LINE="* * * * * BACKEND_CONTAINER='${BACKEND_CONTAINER}' BACKEND_LOG_PATH='${BACKEND_LOG_PATH}' SAMPLE_DIR='${SAMPLE_DIR}' '${SELF}' sample '${RUN_ID}' >> '${RUN_DIR}/cron.log' 2>&1 ${CRON_TAG}"
    ( crontab -l 2>/dev/null | grep -vF "$CRON_TAG" ; echo "$CRON_LINE" ) | crontab -
    info "started — sampling every 60s into ${CSV}"
    info "stop with: $0 stop ${RUN_ID}"
    ;;

  sample)
    mkdir -p "$RUN_DIR"
    [ -f "$CSV" ] || csv_header > "$CSV"
    [ -f "$LOG_OFFSET_FILE" ] || echo 0 > "$LOG_OFFSET_FILE"

    EPOCH=$(date +%s)
    ISO=$(date -u -d "@${EPOCH}" +%Y-%m-%dT%H:%M:%SZ)

    FD_COUNT=$(docker exec "$BACKEND_CONTAINER" sh -c 'ls /proc/1/fd 2>/dev/null | wc -l' 2>/dev/null || echo NA)
    ULIMIT_N=$(docker exec "$BACKEND_CONTAINER" sh -c 'ulimit -n' 2>/dev/null || echo NA)

    # TCP state is per network-namespace — sample inside the container's own
    # namespace, not the host's, since the backend container is not on
    # network_mode: host (see docker-compose-simple.yml).
    SS_OUT=$(docker exec "$BACKEND_CONTAINER" sh -c 'ss -s 2>/dev/null' 2>/dev/null || true)
    TCP_ESTAB=$(echo "$SS_OUT" | grep -oP 'estab \K[0-9]+' || echo NA)
    TCP_TIMEWAIT=$(echo "$SS_OUT" | grep -oP 'timewait \K[0-9]+' || echo NA)

    # conntrack is a host-level netfilter table (shared across containers via
    # the bridge/NAT path) — read from the host, not docker exec.
    CONNTRACK_COUNT=$(sysctl -n net.netfilter.nf_conntrack_count 2>/dev/null || echo NA)
    CONNTRACK_MAX=$(sysctl -n net.netfilter.nf_conntrack_max 2>/dev/null || echo NA)

    PREV_LINES=$(cat "$LOG_OFFSET_FILE" 2>/dev/null || echo 0)
    TOTAL_LINES=$(docker exec "$BACKEND_CONTAINER" sh -c "wc -l < '$BACKEND_LOG_PATH'" 2>/dev/null | tr -d ' ' || echo "$PREV_LINES")
    if [ "$TOTAL_LINES" -ge "$PREV_LINES" ] 2>/dev/null; then
      # grep -c exits 1 on zero matches (while still printing "0") — the
      # trailing `|| true` keeps that from tripping the outer `|| echo 0`
      # fallback, which would otherwise double up and print "0" twice.
      NEW_TIMEOUT_ERRORS=$(docker exec "$BACKEND_CONTAINER" sh -c "tail -n +$((PREV_LINES + 1)) '$BACKEND_LOG_PATH' 2>/dev/null | grep -c 'timeout exceeded when trying to connect' || true" 2>/dev/null || echo 0)
      echo "$TOTAL_LINES" > "$LOG_OFFSET_FILE"
    else
      # log rotated/truncated since last sample — can't compute a delta, skip this tick's count
      NEW_TIMEOUT_ERRORS=NA
      echo "$TOTAL_LINES" > "$LOG_OFFSET_FILE"
    fi

    echo "${EPOCH},${ISO},${FD_COUNT},${ULIMIT_N},${TCP_ESTAB},${TCP_TIMEWAIT},${CONNTRACK_COUNT},${CONNTRACK_MAX},${NEW_TIMEOUT_ERRORS}" >> "$CSV"
    ;;

  stop)
    ( crontab -l 2>/dev/null | grep -vF "$CRON_TAG" ) | crontab - 2>/dev/null || true
    info "stopped — sample count: $(($(wc -l < "$CSV" 2>/dev/null || echo 1) - 1))"
    info "CSV: ${CSV}"
    ;;

  *)
    fail "unknown command '$CMD' — expected start|sample|stop"
    ;;
esac
