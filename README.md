# tenant-perf-framework

Reusable, config-driven load/stress/spike/soak test framework (Gatling + Java 17)
for a multi-tenant app with schema-per-tenant Postgres, all services dockerised
on a single EC2 instance.

## Layout

```
pom.xml                          Maven + gatling-maven-plugin
seed/seed_users.py               Creates tenants + admin users, emits users.csv feeder
src/test/java/perf/
  config/TestConfig.java         Every knob as a -D system property (no code changes to rescale)
  workflows/UserWorkflows.java   Login + weighted random user actions (EDIT endpoints here)
  simulations/                   Load / Stress / Spike / Soak entry points
src/test/resources/data/         users.csv lands here (generated, gitignore if creds matter)
monitoring/                      Prometheus + Grafana + node_exporter + cAdvisor + postgres_exporter
.github/workflows/perf-test.yml  Parameterized manual runs, report uploaded as artifact
```

## Quick start

```bash
# 1. Start monitoring on the EC2 instance
cd monitoring && docker compose up -d
# Import Grafana dashboards: 1860 (node), 14282 (cadvisor), 9628 (postgres)

# 2. Seed data (from your load-gen machine, NOT the EC2 instance)
pip install requests
python seed/seed_users.py --base-url http://EC2_HOST:8080 \
  --tenants 5 --users-per-tenant 10 \
  --out src/test/resources/data/users.csv

# 3. Smoke run
mvn gatling:test -Dgatling.simulationClass=perf.simulations.LoadSimulation \
  -DbaseUrl=http://EC2_HOST:8080 -DtargetRps=2 -DrampSeconds=30 -DsteadySeconds=120

# 4. Real load run
mvn gatling:test -Dgatling.simulationClass=perf.simulations.LoadSimulation \
  -DbaseUrl=http://EC2_HOST:8080 -DtargetRps=25 -DsteadySeconds=900

# Stress / Spike / Soak: swap the simulationClass and relevant -D params
```

Report: `target/gatling/<run>/index.html`

## Things you must edit

1. **`UserWorkflows.java`** — the endpoints/payloads are placeholders. Replace
   with your real auth + workflow APIs and adjust the `randomSwitch` weights to
   match production traffic mix.
2. **`seed_users.py`** — same: point the two calls at your real tenant/user
   creation endpoints.
3. **`monitoring/docker-compose.yml`** — set the postgres-exporter DSN and
   attach it to your app's docker network.

## Design notes

- **Open workload model** (`injectOpen`, users/sec) — arrival rate is what
  production sees; closed thread pools hide queueing collapse.
- **Random feeder** (`csv(...).random()`) — every virtual user independently
  draws a random (tenant, user) pair, giving natural cross-tenant mixing.
- **SLOs as Gatling assertions** — CI runs fail automatically when p95/p99 or
  error-rate budgets are blown.
- **Scale up/down = change `-D` params** — tenants, users, RPS, durations,
  step sizes are all in `TestConfig`.

## Analysis checklist per run

- Client side: p50/p95/p99 per request name, error taxonomy (4xx vs 5xx vs timeout), throughput achieved vs requested.
- EC2 host: CPU (incl. iowait & steal — you're on shared EC2 hardware), memory, disk IOPS/throughput, network.
- Containers: which container saturates first (cAdvisor) — that's your capacity ceiling.
- Postgres: active connections vs max_connections, lock waits, cache hit ratio, checkpoint frequency, WAL rate, top queries by total time (pg_stat_statements), temp file spills, autovacuum activity.
- Multi-tenant fairness: break down latency per tenantSlug (add it as a Gatling group or analyze the simulation.log) — a single noisy tenant degrading others is the key risk in schema-per-tenant designs.
- Soak runs: compare first hour vs last hour; drift = leak.

## Suggested extensions

- Real-time Gatling metrics: enable the Graphite writer in `gatling.conf` and
  point it at InfluxDB/Telegraf, then overlay client latency with server
  metrics in one Grafana dashboard.
- Tenant-scaling test: keep RPS fixed, re-seed with 50/100/200 tenants, and
  chart p95 + Postgres planning time vs schema count.
- Add PgBouncer in front of Postgres and re-run stress — connection storms are
  usually the first failure mode in this architecture.
