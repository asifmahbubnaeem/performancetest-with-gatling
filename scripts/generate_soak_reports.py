#!/usr/bin/env python3
"""
Generate three separate soak-run reports — memory leak, connection
exhaustion, resource saturation — from:
  - Prometheus (monitoring/docker-compose.yml stack: node/cadvisor/postgres
    exporters, already scraping at 5s resolution during the run)
  - monitoring/soak-sampler.sh's CSV (fd counts, TCP state, conntrack,
    backend log timeout-error deltas — signals not covered by the exporters)
  - the Gatling run directory's simulation.log (to auto-detect the run window)

Stdlib only — no extra pip installs required.

Usage:
    python3 scripts/generate_soak_reports.py \\
        --run-dir target/gatling/soaksimulation-20260827123000000 \\
        --sampler-csv monitoring/soak-samples/soak-2026-08-27/sampler.csv \\
        --prom-url http://EC2_HOST:9000

    # or with an explicit window (no Gatling dir, e.g. a manual/partial run):
    python3 scripts/generate_soak_reports.py \\
        --start 2026-08-27T12:00:00Z --end 2026-08-27T18:00:00Z --run-id soak-manual-1

Output: docs/soak-reports/<run-id>/{memory-leak,connection-exhaustion,resource-saturation}-report.md + index.md
"""
import argparse
import csv
import json
import os
import re
import statistics
import sys
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen


# --- Prometheus client -------------------------------------------------------

def prom_range(prom_url, query, start, end, step, timeout=15):
    params = {"query": query, "start": start, "end": end, "step": step}
    url = f"{prom_url}/api/v1/query_range?{urlencode(params)}"
    try:
        with urlopen(url, timeout=timeout) as r:
            data = json.load(r)
    except (URLError, HTTPError, OSError, ValueError) as e:
        return None, str(e)
    if data.get("status") != "success":
        return None, data.get("error", "prometheus query failed")
    result = data["data"]["result"]
    if not result:
        return [], None
    series = []
    for s in result:
        pts = [(float(t), float(v)) for t, v in s["values"]]
        series.append({"metric": s.get("metric", {}), "points": pts})
    return series, None


# --- Stats helpers ------------------------------------------------------------

def mean(points):
    vals = [v for _, v in points]
    return statistics.mean(vals) if vals else None


def minmax(points):
    vals = [v for _, v in points]
    return (min(vals), max(vals)) if vals else (None, None)


def linreg_slope_per_hour(points):
    n = len(points)
    if n < 2:
        return None
    t0 = points[0][0]
    xs = [t - t0 for t, _ in points]
    ys = [v for _, v in points]
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return None
    return (num / den) * 3600


def first_last_window(points, frac=0.2):
    n = len(points)
    k = max(1, int(n * frac)) if n >= 10 else max(1, n // 3)
    return points[:k], points[-k:]


def pct_change(a, b):
    if a in (None, 0):
        return None
    return (b - a) / abs(a) * 100


def sparkline(points, width=60):
    blocks = "▁▂▃▄▅▆▇█"
    vals = [v for _, v in points]
    if not vals:
        return ""
    n = len(vals)
    if n > width:
        bucket = n / width
        vals = [
            statistics.mean(vals[int(i * bucket):int((i + 1) * bucket)] or [vals[-1]])
            for i in range(width)
        ]
    lo, hi = min(vals), max(vals)
    if hi == lo:
        return blocks[0] * len(vals)
    return "".join(
        blocks[min(len(blocks) - 1, int((v - lo) / (hi - lo) * (len(blocks) - 1)))]
        for v in vals
    )


def fmt_ts(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")


def fmt_duration(seconds):
    h, rem = divmod(int(seconds), 3600)
    m, _ = divmod(rem, 60)
    return f"{h}h{m:02d}m"


def human_bytes(n):
    if n is None:
        return "n/a"
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024:
            return f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}PB"


def write(path, content):
    with open(path, "w") as f:
        f.write(content)


# --- Input loading --------------------------------------------------------

def detect_window_from_gatling(run_dir):
    sim_log = os.path.join(run_dir, "simulation.log")
    if not os.path.isfile(sim_log):
        return None
    pattern = re.compile(r"\b(1\d{12})\b")  # 13-digit epoch-millis timestamps
    epochs = []
    with open(sim_log, "r", errors="ignore") as f:
        for line in f:
            epochs.extend(int(m) for m in pattern.findall(line))
    if not epochs:
        return None
    return min(epochs) / 1000.0, max(epochs) / 1000.0


def load_sampler_csv(path):
    if not path or not os.path.isfile(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def parse_time(v):
    if v is None:
        return None
    try:
        return float(v)
    except ValueError:
        pass
    return datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp()


# --- Report builders -----------------------------------------------------

def build_memory_leak_report(ctx):
    lines = [
        f"# Memory Leak Report — {ctx['run_id']}\n",
        f"Window: {fmt_ts(ctx['start'])} to {fmt_ts(ctx['end'])} ({fmt_duration(ctx['end'] - ctx['start'])})\n",
    ]
    verdict = "INCONCLUSIVE"

    q = f'container_memory_working_set_bytes{{name="{ctx["backend_container"]}"}}'
    series, err = prom_range(ctx["prom_url"], q, ctx["start"], ctx["end"], ctx["step"])
    lines.append("## Backend container RSS (working set)\n")
    if err or not series or not series[0]["points"]:
        lines.append(
            f"Data unavailable ({err or 'empty result — check the cadvisor Prometheus target is UP and --backend-container matches the running container name'}).\n"
        )
    else:
        points = series[0]["points"]
        lines.append(f"Samples: {len(points)}\n")
        if len(points) < 6:
            lines.append(
                "Too few samples across the run window to assess a trend — re-run with the monitoring stack up for the full duration.\n"
            )
        else:
            first, last = first_last_window(points)
            fm, lm = mean(first), mean(last)
            growth = pct_change(fm, lm)
            slope = linreg_slope_per_hour(points)
            mn, mx = minmax(points)
            lines.append("| | value |\n|---|---|\n")
            lines.append(f"| First-window mean | {human_bytes(fm)} |\n")
            lines.append(f"| Last-window mean | {human_bytes(lm)} |\n")
            lines.append(f"| Change | {f'{growth:+.1f}%' if growth is not None else 'n/a'} |\n")
            lines.append(f"| Linear trend | {human_bytes(slope) + '/hour' if slope is not None else 'n/a'} |\n")
            lines.append(f"| Min / Max over run | {human_bytes(mn)} / {human_bytes(mx)} |\n")
            lines.append(f"\n```\n{sparkline(points)}\n```\n(left = start of run, right = end)\n")

            if growth is None:
                verdict = "INCONCLUSIVE"
            elif growth > 15:
                verdict = "LEAK SUSPECTED"
            elif growth > 7:
                verdict = "WATCH"
            else:
                verdict = "FLAT / NO LEAK"
            lines.append(f"\n**Verdict: {verdict}**\n")
            lines.append(
                "\nHeuristic: mean of the first ~20% of samples vs. the last ~20%. "
                ">15% growth = leak suspected, 7-15% = watch, under 7% = flat. A real "
                "leak shows monotonic growth that never recovers, even during any lull "
                "in load — check the sparkline for a saw-tooth (normal GC) vs. a "
                "one-way climb (leak) before trusting this verdict.\n"
            )

    lines.append("\n## Host memory headroom (secondary signal)\n")
    hseries, herr = prom_range(ctx["prom_url"], "node_memory_MemAvailable_bytes", ctx["start"], ctx["end"], ctx["step"])
    if herr or not hseries or not hseries[0]["points"]:
        lines.append(f"Data unavailable ({herr or 'empty result'}).\n")
    else:
        mn, mx = minmax(hseries[0]["points"])
        lines.append(f"MemAvailable ranged {human_bytes(mn)} to {human_bytes(mx)} over the run.\n")

    return "\n".join(lines), verdict


def build_connection_exhaustion_report(ctx):
    lines = [
        f"# Connection Exhaustion Report — {ctx['run_id']}\n",
        f"Window: {fmt_ts(ctx['start'])} to {fmt_ts(ctx['end'])} ({fmt_duration(ctx['end'] - ctx['start'])})\n",
    ]
    worst = []

    def pool_section(title, datname, pool_max):
        q = f'sum(pg_stat_activity_count{{datname="{datname}"}})'
        series, err = prom_range(ctx["prom_url"], q, ctx["start"], ctx["end"], ctx["step"])
        sec = [f"## {title} (pool max = {pool_max})\n"]
        if err or not series or not series[0]["points"]:
            sec.append(
                f"Data unavailable ({err or 'empty result — check postgres-exporter target and the pg_stat_activity_count custom query'}).\n"
            )
            worst.append("INCONCLUSIVE")
            return "".join(sec)
        points = series[0]["points"]
        vals = [v for _, v in points]
        peak = max(vals)
        pct_over_80 = sum(1 for v in vals if v > 0.8 * pool_max) / len(vals) * 100
        pct_at_max = sum(1 for v in vals if v >= pool_max) / len(vals) * 100
        sec.append("| | value |\n|---|---|\n")
        sec.append(f"| Peak connections used | {peak:.0f} / {pool_max} |\n")
        sec.append(f"| Time >80% of pool | {pct_over_80:.1f}% of run |\n")
        sec.append(f"| Time at/over pool max | {pct_at_max:.1f}% of run |\n")
        sec.append(f"\n```\n{sparkline(points)}\n```\n")
        v = "FAIL" if pct_at_max > 0 else ("WARN" if pct_over_80 > 5 else "PASS")
        sec.append(f"\n**{title} verdict: {v}**\n")
        worst.append(v)
        return "".join(sec)

    lines.append(pool_section("Advance app DB pool", "advance", ctx["pool_max"]))
    lines.append(pool_section("Audit DB pool", "audit_logs", ctx["audit_pool_max"]))

    lines.append("\n## Idle-in-transaction connections (advance pool)\n")
    q = 'sum(pg_stat_activity_count{datname="advance", state="idle in transaction"})'
    series, err = prom_range(ctx["prom_url"], q, ctx["start"], ctx["end"], ctx["step"])
    if err or not series or not series[0]["points"]:
        lines.append(f"Data unavailable ({err or 'empty result'}).\n")
    else:
        vals = [v for _, v in series[0]["points"]]
        lines.append(f"Peak: {max(vals):.0f}, mean: {statistics.mean(vals):.1f}\n")
        lines.append(
            "Sustained idle-in-transaction connections indicate sessions holding the "
            "pool without releasing (e.g. sequential cleanup round trips before "
            "`connection.release()`) rather than the pool being genuinely undersized — "
            "raising `DATABASE_POOL_MAX` will not fix this pattern.\n"
        )

    lines.append("\n## Backend log evidence\n")
    rows = ctx["sampler_rows"]
    if not rows:
        lines.append("No sampler CSV supplied (`--sampler-csv`) — run `monitoring/soak-sampler.sh` during the soak to capture this.\n")
    else:
        total_timeouts = 0
        for r in rows:
            v = r.get("new_pgpool_timeout_errors", "0")
            if v not in ("NA", ""):
                try:
                    total_timeouts += int(v)
                except ValueError:
                    pass
        lines.append(f"`\"timeout exceeded when trying to connect\"` occurrences during the run: **{total_timeouts}**\n")
        if total_timeouts > 0:
            worst.append("FAIL" if total_timeouts >= 20 else "WARN")

    # WARN/FAIL must outrank INCONCLUSIVE: missing Prometheus data on one
    # sub-check must never bury a genuine WARN/FAIL found via another
    # (e.g. the sampler CSV) — see smoke-test finding 2026-08-27.
    order = {"PASS": 0, "INCONCLUSIVE": 1, "WARN": 2, "FAIL": 3}
    verdict = max(worst, key=lambda v: order.get(v, 1)) if worst else "INCONCLUSIVE"
    lines.append(f"\n## Overall verdict: {verdict}\n")
    return "\n".join(lines), verdict


def build_resource_saturation_report(ctx):
    lines = [
        f"# Resource Saturation Report — {ctx['run_id']}\n",
        f"Window: {fmt_ts(ctx['start'])} to {fmt_ts(ctx['end'])} ({fmt_duration(ctx['end'] - ctx['start'])})\n",
    ]
    worst = []

    lines.append("## Host CPU\n")
    q = '100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m])))'
    series, err = prom_range(ctx["prom_url"], q, ctx["start"], ctx["end"], ctx["step"])
    if err or not series or not series[0]["points"]:
        lines.append(f"Data unavailable ({err or 'empty result — check the node Prometheus target is UP'}).\n")
        worst.append("INCONCLUSIVE")
    else:
        points = series[0]["points"]
        vals = sorted(v for _, v in points)
        p95 = vals[int(0.95 * (len(vals) - 1))]
        pct_over_95 = sum(1 for v in vals if v > 95) / len(vals) * 100
        lines.append(
            f"Mean: {statistics.mean(vals):.1f}%, p95: {p95:.1f}%, peak: {max(vals):.1f}%, "
            f"time >95% busy: {pct_over_95:.1f}% of run\n"
        )
        lines.append(f"\n```\n{sparkline(points)}\n```\n")
        v = "FAIL" if pct_over_95 > 10 else ("WARN" if p95 > 80 else "PASS")
        lines.append(f"\n**CPU verdict: {v}**\n")
        worst.append(v)

    lines.append("\n## Backend container CPU (cores used)\n")
    q = f'rate(container_cpu_usage_seconds_total{{name="{ctx["backend_container"]}"}}[1m])'
    series, err = prom_range(ctx["prom_url"], q, ctx["start"], ctx["end"], ctx["step"])
    if err or not series or not series[0]["points"]:
        lines.append(f"Data unavailable ({err or 'empty result'}).\n")
    else:
        vals = [v for _, v in series[0]["points"]]
        lines.append(
            f"Mean: {statistics.mean(vals):.2f} cores, peak: {max(vals):.2f} cores "
            "(compare against `nproc` on the host, or any container CPU limit — "
            "sustained use at that ceiling is the capacity limit.)\n"
        )

    rows = ctx["sampler_rows"]

    lines.append("\n## File descriptors (backend container)\n")
    if not rows:
        lines.append("No sampler CSV supplied — run `monitoring/soak-sampler.sh` during the soak to capture this.\n")
        worst.append("INCONCLUSIVE")
    else:
        ratios = []
        for r in rows:
            fd, lim = r.get("fd_count"), r.get("ulimit_n")
            if fd not in (None, "NA", "") and lim not in (None, "NA", "0", ""):
                try:
                    ratios.append(int(fd) / int(lim))
                except (ValueError, ZeroDivisionError):
                    pass
        if ratios:
            peak_ratio = max(ratios)
            lines.append(f"Peak fd usage: {peak_ratio * 100:.1f}% of `ulimit -n` across {len(ratios)} samples\n")
            v = "FAIL" if peak_ratio > 0.9 else ("WARN" if peak_ratio > 0.7 else "PASS")
            lines.append(f"\n**fd verdict: {v}**\n")
            worst.append(v)
        else:
            lines.append("Sampler CSV present but had no usable fd_count/ulimit_n rows.\n")
            worst.append("INCONCLUSIVE")

    lines.append("\n## Conntrack table\n")
    if not rows:
        lines.append("No sampler CSV supplied.\n")
    else:
        ratios = []
        for r in rows:
            c, m = r.get("conntrack_count"), r.get("conntrack_max")
            if c not in (None, "NA", "") and m not in (None, "NA", "0", ""):
                try:
                    ratios.append(int(c) / int(m))
                except (ValueError, ZeroDivisionError):
                    pass
        if ratios:
            peak_ratio = max(ratios)
            lines.append(f"Peak conntrack usage: {peak_ratio * 100:.1f}% of `nf_conntrack_max`\n")
            v = "FAIL" if peak_ratio > 0.9 else ("WARN" if peak_ratio > 0.7 else "PASS")
            lines.append(f"\n**conntrack verdict: {v}**\n")
            worst.append(v)
        else:
            lines.append("Sampler CSV present but had no usable conntrack rows.\n")

    lines.append("\n## TCP socket state (backend container)\n")
    if rows:
        tw_vals = [int(r["tcp_timewait"]) for r in rows if r.get("tcp_timewait") not in (None, "NA", "")]
        if tw_vals:
            lines.append(f"TIME-WAIT peak: {max(tw_vals)}, mean: {statistics.mean(tw_vals):.0f}\n")
        else:
            lines.append("Sampler CSV present but had no usable tcp_timewait rows.\n")
    else:
        lines.append("No sampler CSV supplied.\n")

    order = {"PASS": 0, "INCONCLUSIVE": 1, "WARN": 2, "FAIL": 3}
    verdict = max(worst, key=lambda v: order.get(v, 1)) if worst else "INCONCLUSIVE"
    lines.append(f"\n## Overall verdict: {verdict}\n")
    return "\n".join(lines), verdict


def build_index(ctx, mem_v, conn_v, sat_v):
    return (
        f"# Soak Run Report Index — {ctx['run_id']}\n\n"
        f"Window: {fmt_ts(ctx['start'])} to {fmt_ts(ctx['end'])} ({fmt_duration(ctx['end'] - ctx['start'])})\n\n"
        "| Report | Verdict |\n|---|---|\n"
        f"| [Memory leak](memory-leak-report.md) | {mem_v} |\n"
        f"| [Connection exhaustion](connection-exhaustion-report.md) | {conn_v} |\n"
        f"| [Resource saturation](resource-saturation-report.md) | {sat_v} |\n\n"
        "Generated by `scripts/generate_soak_reports.py`. Verdicts use fixed heuristic "
        "thresholds — read the underlying report before treating a WARN/FAIL as "
        "confirmed; they're a triage signal, not a certified pass/fail.\n"
    )


# --- Main ------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run-dir", help="Gatling results dir (target/gatling/<run>) — used to auto-detect start/end from simulation.log")
    ap.add_argument("--start", help="run start: epoch seconds or ISO8601 (overrides --run-dir detection)")
    ap.add_argument("--end", help="run end: epoch seconds or ISO8601")
    ap.add_argument("--run-id", help="label for the output dir; default derived from --run-dir or --start")
    ap.add_argument("--prom-url", default=os.environ.get("PROM_URL", "http://localhost:9000"))
    ap.add_argument("--sampler-csv", help="monitoring/soak-sampler.sh CSV for this run")
    ap.add_argument("--backend-container", default=os.environ.get("BACKEND_CONTAINER", "isaraadvance-backend-1"))
    ap.add_argument("--pool-max", type=int, default=25, help="DATABASE_POOL_MAX (default matches monitoring/rules.yml)")
    ap.add_argument("--audit-pool-max", type=int, default=20, help="AUDIT_DATABASE_POOL_MAX")
    ap.add_argument("--step", help="Prometheus range-query step, e.g. 15s (default: auto, ~600 points across the window)")
    ap.add_argument("--out-dir", help="default: docs/soak-reports/<run-id>")
    args = ap.parse_args()

    start = parse_time(args.start)
    end = parse_time(args.end)
    if start is None or end is None:
        if not args.run_dir:
            sys.exit("error: need --run-dir (to auto-detect the window) or explicit --start/--end")
        window = detect_window_from_gatling(args.run_dir)
        if not window:
            sys.exit(f"error: could not find/parse simulation.log under {args.run_dir} — pass --start/--end explicitly")
        start, end = window

    duration = end - start
    step = args.step or f"{max(15, int(duration / 600))}s"

    run_id = args.run_id or (
        os.path.basename(os.path.normpath(args.run_dir))
        if args.run_dir
        else datetime.fromtimestamp(start, tz=timezone.utc).strftime("soak-%Y%m%dT%H%M%SZ")
    )
    out_dir = args.out_dir or os.path.join("docs", "soak-reports", run_id)
    os.makedirs(out_dir, exist_ok=True)

    ctx = dict(
        prom_url=args.prom_url,
        start=start,
        end=end,
        step=step,
        backend_container=args.backend_container,
        pool_max=args.pool_max,
        audit_pool_max=args.audit_pool_max,
        run_id=run_id,
        sampler_rows=load_sampler_csv(args.sampler_csv),
    )

    mem_md, mem_v = build_memory_leak_report(ctx)
    conn_md, conn_v = build_connection_exhaustion_report(ctx)
    sat_md, sat_v = build_resource_saturation_report(ctx)

    write(os.path.join(out_dir, "memory-leak-report.md"), mem_md)
    write(os.path.join(out_dir, "connection-exhaustion-report.md"), conn_md)
    write(os.path.join(out_dir, "resource-saturation-report.md"), sat_md)
    write(os.path.join(out_dir, "index.md"), build_index(ctx, mem_v, conn_v, sat_v))

    print(f"wrote reports to {out_dir}/")
    for name, v in (("memory-leak", mem_v), ("connection-exhaustion", conn_v), ("resource-saturation", sat_v)):
        print(f"  {name}: {v}")


if __name__ == "__main__":
    main()
