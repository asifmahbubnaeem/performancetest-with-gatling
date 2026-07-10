#!/usr/bin/env python3
"""
Seed tenants + admin users and emit the Gatling feeder file.

Idempotent-ish: tenant slugs are deterministic (perf-tenant-001 ...), so
re-running against a clean environment reproduces the same dataset.

Replace the two API payloads/paths with your app's real endpoints.

Usage:
  python seed/seed_users.py \
      --base-url http://my-ec2:8080 \
      --tenants 5 --users-per-tenant 10 \
      --out src/test/resources/data/users.csv
"""
import argparse
import csv
import sys

import requests

DEFAULT_PASSWORD = "PerfTest#123"  # test-only credential


def create_tenant(base_url: str, slug: str) -> str:
    """Create a tenant (triggers schema creation in Postgres). Returns tenant id."""
    r = requests.post(
        f"{base_url}/api/tenants",
        json={"name": slug, "slug": slug},
        timeout=30,
    )
    if r.status_code == 409:  # already exists — fetch it instead
        r = requests.get(f"{base_url}/api/tenants/{slug}", timeout=30)
    r.raise_for_status()
    return str(r.json()["id"])


def create_admin_user(base_url: str, tenant_id: str, username: str) -> None:
    r = requests.post(
        f"{base_url}/api/tenants/{tenant_id}/users",
        json={"username": username, "password": DEFAULT_PASSWORD, "role": "ADMIN"},
        timeout=30,
    )
    if r.status_code not in (200, 201, 409):
        r.raise_for_status()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--tenants", type=int, default=5)
    ap.add_argument("--users-per-tenant", type=int, default=10)
    ap.add_argument("--out", default="src/test/resources/data/users.csv")
    args = ap.parse_args()

    rows = []
    for t in range(1, args.tenants + 1):
        slug = f"perf-tenant-{t:03d}"
        tenant_id = create_tenant(args.base_url, slug)
        print(f"[seed] tenant {slug} -> id={tenant_id}")
        for u in range(1, args.users_per_tenant + 1):
            username = f"admin-{t:03d}-{u:03d}"
            create_admin_user(args.base_url, tenant_id, username)
            rows.append(
                {"tenantId": tenant_id, "tenantSlug": slug,
                 "username": username, "password": DEFAULT_PASSWORD}
            )
        print(f"[seed]   created {args.users_per_tenant} admin users")

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(
            f, fieldnames=["tenantId", "tenantSlug", "username", "password"]
        )
        writer.writeheader()
        writer.writerows(rows)

    print(f"[seed] wrote {len(rows)} rows -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
