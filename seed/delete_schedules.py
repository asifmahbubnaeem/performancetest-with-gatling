#!/usr/bin/env python3
"""
Delete all scheduled probes (GetSchedules -> DeleteSchedule) for every tenant
in data/users.csv, logging in as needed via the same GraphQL Login mutation
SoakSimulation uses (see UserWorkflows.LOGIN / bodies/login.json).

Stdlib only — no extra pip installs required (matches generate_soak_reports.py).

Schedules are added once per TENANT (see UserWorkflows.SCHEDULE_PROBE), so by
default this logs in as only the first user of each distinct tenant_id in the
CSV — one login per tenant, not one per user row, to avoid tripping the
per-IP login rate limiter mentioned in SoakSimulation.java's warmup comments.
Pass --all-users to log in as every row instead.

Usage:
    python3 scripts/delete_schedules.py                          # dry run, all tenants
    python3 scripts/delete_schedules.py --yes                    # actually delete

    python3 scripts/delete_schedules.py \\
        --url https://40.176.175.38/service/graphql \\
        --users-csv src/test/resources/data/users.csv \\
        --yes

python3 delete_schedules.py --url https://$(hostname -I | awk '{print $1}')/service/graphql --users-csv users.csv --yes
"""

import argparse
import csv
import json
import ssl
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "https://40.176.175.38/service/graphql"
DEFAULT_USERS_CSV = "src/test/resources/data/users.csv"

AUTH_HEADER = "x-isara-authorization"

LOGIN_QUERY = (
    "mutation Login($userName: String!, $password: String!) { "
    "login(userName: $userName, password: $password) { token user { id userName } } }"
)

GET_SCHEDULES_QUERY = """query GetSchedules {
  getSchedules {
    id
    host
    port
    cron
    cronEnglish
    resultStatus
    probeType
    applications
    applicationsName
    __typename
  }
}
"""

DELETE_SCHEDULE_MUTATION = """mutation DeleteSchedule($host: String!, $port: Int!, $cron: String!) {
  deleteSchedule(host: $host, port: $port, cron: $cron)
}
"""


def graphql_request(url, headers, query, variables, ssl_context):
    payload = json.dumps(
        {"operationName": None, "query": query, "variables": variables}
    ).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            **headers,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30, context=ssl_context) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("errors"):
        raise RuntimeError(f"GraphQL errors: {body['errors']}")
    return body["data"]


def tenant_headers(row):
    # Same two headers UserWorkflows.HTTP_PROTOCOL sends on every request.
    return {
        "x-isara-customer-state": row["customer_alias"],
        "workspace-id": row["tenant_id"],
    }


def login(url, row, ssl_context):
    data = graphql_request(
        url,
        tenant_headers(row),
        LOGIN_QUERY,
        {"userName": row["username"], "password": row["password"]},
        ssl_context,
    )
    return data["login"]["token"]


def load_rows(users_csv, all_users):
    with open(users_csv, newline="") as f:
        rows = list(csv.DictReader(f))
    if all_users:
        return rows
    seen_tenants = set()
    picked = []
    for row in rows:
        if row["tenant_id"] not in seen_tenants:
            seen_tenants.add(row["tenant_id"])
            picked.append(row)
    return picked


def process_tenant(url, row, ssl_context, dry_run):
    label = f"tenant_id={row['tenant_id']} ({row['tenant_name']}) as {row['username']}"
    try:
        token = login(url, row, ssl_context)
    except (urllib.error.URLError, RuntimeError) as exc:
        print(f"  LOGIN FAILED for {label}: {exc}", file=sys.stderr)
        return 0, 1

    headers = {**tenant_headers(row), AUTH_HEADER: f"Bearer {token}"}

    try:
        data = graphql_request(url, headers, GET_SCHEDULES_QUERY, {}, ssl_context)
    except (urllib.error.URLError, RuntimeError) as exc:
        print(f"  GetSchedules FAILED for {label}: {exc}", file=sys.stderr)
        return 0, 1

    schedules = data["getSchedules"]
    if not schedules:
        print(f"  {label}: no schedules found")
        return 0, 0

    deleted, failed = 0, 0
    for s in schedules:
        sched_label = (
            f"id={s['id']} host={s['host']} port={s['port']} "
            f"cron={s['cron']} probeType={s['probeType']}"
        )
        if dry_run:
            print(f"  [dry-run] {label}: would delete {sched_label}")
            continue
        try:
            graphql_request(
                url,
                headers,
                DELETE_SCHEDULE_MUTATION,
                {"host": s["host"], "port": s["port"], "cron": s["cron"]},
                ssl_context,
            )
            print(f"  {label}: deleted {sched_label}")
            deleted += 1
        except (urllib.error.URLError, RuntimeError) as exc:
            print(f"  {label}: FAILED to delete {sched_label}: {exc}", file=sys.stderr)
            failed += 1

    return deleted, failed


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--url", default=DEFAULT_URL, help=f"GraphQL endpoint (default {DEFAULT_URL})")
    parser.add_argument(
        "--users-csv",
        default=DEFAULT_USERS_CSV,
        help=f"path to users.csv (default {DEFAULT_USERS_CSV})",
    )
    parser.add_argument(
        "--all-users",
        action="store_true",
        help="log in as every row in users.csv instead of one per distinct tenant_id",
    )
    parser.add_argument(
        "--verify-tls",
        action="store_true",
        help="verify TLS certs (default is insecure, matching this env's self-signed certs)",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="actually delete; without this, only lists what would be deleted",
    )
    args = parser.parse_args()

    dry_run = not args.yes
    ssl_context = ssl.create_default_context()
    if not args.verify_tls:
        ssl_context.check_hostname = False
        ssl_context.verify_mode = ssl.CERT_NONE

    rows = load_rows(args.users_csv, args.all_users)
    if not rows:
        sys.exit(f"no rows found in {args.users_csv}")

    if dry_run:
        print("DRY RUN — nothing will be deleted. Pass --yes to actually delete.\n")
    print(f"processing {len(rows)} {'user(s)' if args.all_users else 'tenant(s)'} from {args.users_csv}\n")

    total_deleted, total_failed = 0, 0
    for i, row in enumerate(rows, 1):
        print(f"[{i}/{len(rows)}] tenant_id={row['tenant_id']} ({row['tenant_name']})")
        deleted, failed = process_tenant(args.url, row, ssl_context, dry_run)
        total_deleted += deleted
        total_failed += failed

    if not dry_run:
        print(f"\ndone: {total_deleted} deleted, {total_failed} failed")


if __name__ == "__main__":
    main()
