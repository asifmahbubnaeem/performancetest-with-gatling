#!/usr/bin/env python3
"""
provision_tenants.py

Given a LIST of customer names, creates each customer, then under each
customer creates a fixed number of tenants, then under each tenant creates
a fixed number of users. Naming scheme:

    tenant_name = f"{customer_name}_tenant{i}"   for i in 1..num_tenants
    user_login  = f"{tenant_name}_user{i}"        for i in 1..num_users

Designed for seeding load-test data (Gatling/JMeter etc) against a GraphQL API.

AUTHENTICATION:
The script logs in itself (username/password) rather than needing a
pre-fetched token, and automatically re-logs-in and retries whenever the
server reports the session/token has expired -- this matters because tokens
from this API expire quickly (~15 min) and a large provisioning run can
easily outlast that.

Environment/server settings (host, port, login credentials, customer state)
are set in the SETTINGS block below -- edit those values directly in this
file. Everything that changes per-run (which customers, how many tenants/
users, output path) stays on the command line.

Usage:
    python3 provision_tenants.py \
        --customer-names acme,globex,initech \
        --tenants 4 \
        --users 10 \
        --output users.csv

    # Or via a config file (still overrides the SETTINGS block below if set)
    python3 provision_tenants.py --config config.json

Output CSV columns:
    customer_alias, customer_id, tenant_name, tenant_id, login, password, user_id
"""

import argparse
import csv
import json
import logging
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Optional

import requests
import urllib3


# ==========================================================================
# SETTINGS -- edit these values for your environment
# ==========================================================================

HOST = "40.177.170.139"                    # RunTime server IP/hostname
PORT = 443                          # RunTime server port
USERNAME = "red"                     # login username (leave "" to use TOKEN instead)
PASSWORD = "pSw@27#Fr"               # login password
LOGIN_CUSTOMER_STATE = "advance"     # x-isara-customer-state sent on the login call itself
CUSTOMER_STATE = "advance"           # x-isara-customer-state sent on all provisioning requests
TOKEN = ""                           # optional: pre-fetched bearer token; if set, skips login

# ==========================================================================


# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------

@dataclass
class Config:
    host: str = HOST
    port: int = PORT
    customer_state: str = CUSTOMER_STATE
    token: str = TOKEN
    username: str = USERNAME
    password: str = PASSWORD
    login_customer_state: str = LOGIN_CUSTOMER_STATE
    customer_names: list = field(default_factory=list)
    tenants: int = 1
    users: int = 1
    user_password: str = "pSw@27#Fr"
    role: str = "ADMIN"
    verify_ssl: bool = False
    output: str = "provisioned_users.csv"
    max_retries: int = 3
    retry_backoff: float = 2.0
    request_timeout: int = 30

    @property
    def endpoint(self) -> str:
        return f"https://{self.host}:{self.port}/service/graphql"


def load_config() -> Config:
    parser = argparse.ArgumentParser(description="Provision customers/tenants/users via GraphQL API")
    parser.add_argument("--config", help="Path to JSON config file (overrides SETTINGS block and individual flags where set)")
    parser.add_argument("--customer-names", help="Comma-separated list of customer names, e.g. acme,globex,initech")
    parser.add_argument("--tenants", type=int, help="Number of tenants to create per customer")
    parser.add_argument("--users", type=int, help="Number of users to create per tenant")
    parser.add_argument("--user-password", default=None)
    parser.add_argument("--role", default=None)
    parser.add_argument("--output", default=None, help="Output CSV path")
    parser.add_argument("--verify-ssl", action="store_true", default=None)
    parser.add_argument("--dry-run", action="store_true", help="Print planned mutations without sending them")
    args = parser.parse_args()

    data: dict[str, Any] = {}
    if args.config:
        with open(args.config, "r") as f:
            data = json.load(f)

    # CLI flags override config file / SETTINGS block values when explicitly provided
    overrides = {
        "tenants": args.tenants,
        "users": args.users,
        "user_password": args.user_password,
        "role": args.role,
        "output": args.output,
        "verify_ssl": args.verify_ssl,
    }
    for k, v in overrides.items():
        if v is not None:
            data[k] = v

    if args.customer_names:
        data["customer_names"] = [n.strip() for n in args.customer_names.split(",") if n.strip()]

    if "customer_names" not in data or not data["customer_names"]:
        parser.error("Missing required setting: customer_names (pass --customer-names or set it in --config)")

    has_token = bool(data.get("token", TOKEN))
    has_creds = bool(data.get("username", USERNAME)) and bool(data.get("password", PASSWORD))
    if not has_token and not has_creds:
        parser.error("Provide TOKEN, or USERNAME and PASSWORD, in the SETTINGS block at the top of the script")
    if has_creds and not data.get("login_customer_state", LOGIN_CUSTOMER_STATE):
        parser.error("LOGIN_CUSTOMER_STATE is required in the SETTINGS block when logging in with USERNAME/PASSWORD")

    cfg_fields = {f.name for f in Config.__dataclass_fields__.values()}  # type: ignore[attr-defined]
    filtered = {k: v for k, v in data.items() if k in cfg_fields}
    cfg = Config(**filtered)
    cfg._dry_run = args.dry_run  # type: ignore[attr-defined]
    return cfg


# --------------------------------------------------------------------------
# GraphQL client
# --------------------------------------------------------------------------

class GraphQLError(Exception):
    pass


LOGIN = """
mutation Login($userName: String!, $password: String!) {
  login(userName: $userName, password: $password) {
    token
    refreshToken
    lastTenantId
    tenants {
      id
      name
      __typename
    }
    user {
      id
      humanName
      userName
      __typename
    }
    __typename
  }
}
"""

# Substrings we look for (case-insensitive) in a GraphQL error message to
# recognize an expired/invalid session so we can transparently re-login and
# retry, instead of burning all retries on a doomed request.
SESSION_EXPIRED_MARKERS = ("session expired", "sign in again", "unauthorized", "unauthenticated", "invalid token")


class GraphQLClient:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})
        if not cfg.verify_ssl:
            urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        if cfg.token:
            self.session.headers["x-isara-authorization"] = f"Bearer {cfg.token}"

    def login(self) -> None:
        """Log in with cfg.username/cfg.password and set the bearer token."""
        resp = self.session.post(
            self.cfg.endpoint,
            json={
                "operationName": "Login",
                "variables": {"userName": self.cfg.username, "password": self.cfg.password},
                "query": LOGIN,
            },
            headers={"x-isara-customer-state": self.cfg.login_customer_state},
            timeout=self.cfg.request_timeout,
            verify=self.cfg.verify_ssl,
        )
        resp.raise_for_status()
        body = resp.json()
        if "errors" in body and body["errors"]:
            raise GraphQLError(f"Login failed: {json.dumps(body['errors'])}")

        token = body.get("data", {}).get("login", {}).get("token")
        if not token:
            raise GraphQLError(f"Login response missing token: {json.dumps(body)}")

        self.cfg.token = token
        self.session.headers["x-isara-authorization"] = f"Bearer {token}"
        logging.info("Logged in as %s, obtained new token", self.cfg.username)

    def execute(self, query: str, variables: Optional[dict] = None,
                customer_alias: str = "") -> dict:
        """Execute a GraphQL request with retries. Returns the 'data' dict.

        x-isara-customer-state is a required header on every request.
        Automatically re-logs-in (once per attempt) and retries if the
        server reports the session/token has expired.
        """
        headers = {"x-isara-customer-state": customer_alias}

        payload = {"query": query}
        if variables:
            payload["variables"] = variables

        last_exc: Optional[Exception] = None
        for attempt in range(1, self.cfg.max_retries + 1):
            try:
                resp = self.session.post(
                    self.cfg.endpoint,
                    json=payload,
                    headers=headers,
                    timeout=self.cfg.request_timeout,
                    verify=self.cfg.verify_ssl,
                )
                resp.raise_for_status()
                body = resp.json()

                if "errors" in body and body["errors"]:
                    err_text = json.dumps(body["errors"])
                    if self.cfg.username and self.cfg.password and \
                            any(marker in err_text.lower() for marker in SESSION_EXPIRED_MARKERS):
                        logging.warning("Session appears expired, re-authenticating...")
                        try:
                            self.login()
                        except GraphQLError as login_exc:
                            last_exc = login_exc
                            logging.warning("Re-login failed: %s", login_exc)
                            if attempt < self.cfg.max_retries:
                                time.sleep(self.cfg.retry_backoff * attempt)
                            continue
                        # retry this same request immediately with the fresh token
                        continue
                    raise GraphQLError(err_text)

                return body.get("data", {})

            except (requests.RequestException, GraphQLError) as exc:
                last_exc = exc
                logging.warning(
                    "Attempt %d/%d failed: %s", attempt, self.cfg.max_retries, exc
                )
                if attempt < self.cfg.max_retries:
                    time.sleep(self.cfg.retry_backoff * attempt)

        raise GraphQLError(f"Request failed after {self.cfg.max_retries} attempts: {last_exc}")


# --------------------------------------------------------------------------
# GraphQL operations
# --------------------------------------------------------------------------

CREATE_CUSTOMER = """
mutation CreateCustomer($name: String!, $alias: String!, $code: String!) {
  createCustomer(name: $name, alias: $alias, code: $code) {
    id
    name
    alias
    code
    active
    tenantCount
    authMethod
    __typename
  }
}
"""

CREATE_TENANT = """
mutation CreateTenant($customerId: BigInt!, $name: String!) {
  createTenant(customerId: $customerId, name: $name) {
    id
    name
    customerId
    customerAlias
    active
    createdAt
    __typename
  }
}
"""

CREATE_USER = """
mutation CreateUser(
  $login: String!,
  $humanName: String!,
  $password: String!,
  $customerId: BigInt!,
  $tenantId: BigInt!,
  $role: String!
) {
  createPlatformManagedUser(
    login: $login,
    humanName: $humanName,
    password: $password,
    customerId: $customerId,
    tenantId: $tenantId,
    role: $role
  ) {
    login
    humanName
    userId
    rolesSummary
    isPlatformAdmin
    __typename
  }
}
"""


PLATFORM_CUSTOMERS = """
query PlatformCustomers($includeInactive: Boolean) {
  platformCustomers(includeInactive: $includeInactive) {
    id
    name
    alias
    code
    active
    tenantCount
    authMethod
    __typename
  }
}
"""

PLATFORM_TENANTS = """
query PlatformTenants($customerId: BigInt, $includeInactive: Boolean) {
  platformTenants(customerId: $customerId, includeInactive: $includeInactive) {
    id
    name
    customerId
    customerAlias
    customerCode
    customerActive
    active
    createdAt
    __typename
  }
}
"""

PLATFORM_USERS = """
query PlatformUsers($customerId: BigInt, $login: String, $includeInactive: Boolean) {
  platformUsers(
    customerId: $customerId
    login: $login
    includeInactive: $includeInactive
  ) {
    login
    humanName
    status
    lockedOut
    rolesSummary
    isPlatformAdmin
    userId
    email
    customers {
      customerId
      alias
      name
      membershipRole
      membershipStatus
      customerRole
      tenantRoles {
        tenantId
        tenantName
        role
        __typename
      }
      __typename
    }
    __typename
  }
}
"""


def get_existing_customers(client: GraphQLClient, customer_state: str) -> dict:
    """Returns {alias: id} for all existing platform customers (active + inactive)."""
    data = client.execute(
        PLATFORM_CUSTOMERS,
        {"includeInactive": True},
        customer_alias=customer_state,
    )
    return {c["alias"]: c["id"] for c in data.get("platformCustomers", [])}


def get_existing_tenants(client: GraphQLClient, customer_id, customer_state: str) -> dict:
    """Returns {tenant_name: id} for all existing tenants under a given customer (active + inactive)."""
    data = client.execute(
        PLATFORM_TENANTS,
        {"customerId": customer_id, "includeInactive": True},
        customer_alias=customer_state,
    )
    return {t["name"]: t["id"] for t in data.get("platformTenants", [])}


def get_existing_users(client: GraphQLClient, customer_id, customer_state: str) -> dict:
    """Returns {login: userId} for all existing users under a given customer (active + inactive)."""
    data = client.execute(
        PLATFORM_USERS,
        {"customerId": customer_id, "includeInactive": True},
        customer_alias=customer_state,
    )
    return {u["login"]: u["userId"] for u in data.get("platformUsers", [])}


def create_customer(client: GraphQLClient, name: str, alias: str, code: str, customer_state: str) -> dict:
    data = client.execute(
        CREATE_CUSTOMER,
        {"name": name, "alias": alias, "code": code},
        customer_alias=customer_state,
    )
    return data["createCustomer"]


def create_tenant(client: GraphQLClient, customer_id: str, name: str, customer_state: str) -> dict:
    data = client.execute(
        CREATE_TENANT,
        {"customerId": customer_id, "name": name},
        customer_alias=customer_state,
    )
    return data["createTenant"]


def create_user(client: GraphQLClient, login: str, human_name: str, password: str,
                 customer_id: str, tenant_id: str, role: str, customer_state: str) -> dict:
    data = client.execute(
        CREATE_USER,
        {
            "login": login,
            "humanName": human_name,
            "password": password,
            "customerId": customer_id,
            "tenantId": tenant_id,
            "role": role,
        },
        customer_alias=customer_state,
    )
    return data["createPlatformManagedUser"]


# --------------------------------------------------------------------------
# Provisioning workflow
# --------------------------------------------------------------------------

def provision(cfg: Config) -> list[dict]:
    dry_run = getattr(cfg, "_dry_run", False)
    client = GraphQLClient(cfg)
    rows: list[dict] = []

    total_customers = len(cfg.customer_names)
    total_tenants = total_customers * cfg.tenants
    total_users = total_tenants * cfg.users
    logging.info(
        "Plan: %d customers x %d tenants x %d users = %d users total (%d tenants)",
        total_customers, cfg.tenants, cfg.users, total_users, total_tenants,
    )

    if not dry_run and not cfg.token and cfg.username and cfg.password:
        client.login()

    existing_customers: dict = {}
    if not dry_run:
        try:
            existing_customers = get_existing_customers(client, cfg.customer_state)
            logging.info("Found %d existing customer(s) on server", len(existing_customers))
        except GraphQLError as exc:
            logging.warning("Could not fetch existing customers, will attempt creation for all: %s", exc)

    for cust_name in cfg.customer_names:
        cust_alias = cust_name
        cust_code = cust_name

        if dry_run:
            logging.info("[DRY RUN] createCustomer name=%s alias=%s code=%s (x-isara-customer-state=%s)",
                          cust_name, cust_alias, cust_code, cfg.customer_state)
            customer_id = f"dryrun-{cust_name}"
        elif cust_alias in existing_customers:
            customer_id = existing_customers[cust_alias]
            logging.info("Customer %s already exists (id=%s), reusing it", cust_alias, customer_id)
        else:
            try:
                customer = create_customer(client, cust_name, cust_alias, cust_code, cfg.customer_state)
                customer_id = customer["id"]
                logging.info("Created customer %s (id=%s)", cust_alias, customer_id)
            except GraphQLError as exc:
                logging.error("Failed to create customer %s: %s", cust_name, exc)
                continue

        existing_tenants: dict = {}
        existing_users: dict = {}
        if not dry_run:
            try:
                existing_tenants = get_existing_tenants(client, customer_id, cfg.customer_state)
                logging.info("  Found %d existing tenant(s) under %s", len(existing_tenants), cust_alias)
            except GraphQLError as exc:
                logging.warning("  Could not fetch existing tenants for %s, will attempt creation for all: %s",
                                 cust_alias, exc)
            try:
                existing_users = get_existing_users(client, customer_id, cfg.customer_state)
                logging.info("  Found %d existing user(s) under %s", len(existing_users), cust_alias)
            except GraphQLError as exc:
                logging.warning("  Could not fetch existing users for %s, will attempt creation for all: %s",
                                 cust_alias, exc)

        for i in range(1, cfg.tenants + 1):
            tenant_name = f"{cust_name}_tenant{i}"

            if dry_run:
                logging.info("[DRY RUN]   createTenant customerId=%s name=%s", customer_id, tenant_name)
                tenant_id = f"dryrun-{tenant_name}"
            elif tenant_name in existing_tenants:
                tenant_id = existing_tenants[tenant_name]
                logging.info("  Tenant %s already exists (id=%s), reusing it", tenant_name, tenant_id)
            else:
                try:
                    tenant = create_tenant(client, customer_id, tenant_name, cfg.customer_state)
                    tenant_id = tenant["id"]
                    logging.info("  Created tenant %s (id=%s)", tenant_name, tenant_id)
                except GraphQLError as exc:
                    logging.error("  Failed to create tenant %s: %s", tenant_name, exc)
                    continue

            for j in range(1, cfg.users + 1):
                login = f"{tenant_name}_user{j}"
                password = cfg.user_password

                if dry_run:
                    logging.info(
                        "[DRY RUN]     createPlatformManagedUser login=%s customerId=%s tenantId=%s role=%s",
                        login, customer_id, tenant_id, cfg.role,
                    )
                    user_id = f"dryrun-{login}"
                elif login in existing_users:
                    user_id = existing_users[login]
                    logging.info("    User %s already exists (id=%s), reusing it", login, user_id)
                else:
                    try:
                        user = create_user(
                            client, login, login, password,
                            customer_id, tenant_id, cfg.role, cfg.customer_state,
                        )
                        user_id = user["userId"]
                        logging.info("    Created user %s (id=%s)", login, user_id)
                    except GraphQLError as exc:
                        logging.error("    Failed to create user %s: %s", login, exc)
                        continue

                rows.append({
                    "customer_alias": cust_alias,
                    "customer_id": customer_id,
                    "tenant_name": tenant_name,
                    "tenant_id": tenant_id,
                    "username": login,
                    "password": password,
                    "user_id": user_id,
                })

    return rows


def write_csv(rows: list[dict], path: str) -> None:
    if not rows:
        logging.warning("No rows to write to %s", path)
        return
    fieldnames = ["customer_alias", "customer_id", "tenant_name", "tenant_id", "username", "password", "user_id"]
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    logging.info("Wrote %d rows to %s", len(rows), path)


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )
    cfg = load_config()
    rows = provision(cfg)
    write_csv(rows, cfg.output)

    ok = len(rows)
    expected = len(cfg.customer_names) * cfg.tenants * cfg.users
    logging.info("Done: %d/%d users provisioned successfully", ok, expected)
    return 0 if ok == expected else 1


if __name__ == "__main__":
    sys.exit(main())
