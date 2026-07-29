#!/usr/bin/env python3
"""
Net worth history — snapshot + graph.

Neither the Trading212 nor the Crypto.com API exposes a "portfolio value
over time" endpoint — both only return a live snapshot. So this script
builds its own history: each run appends a row (timestamp, Trading212
total, Crypto.com total, grand total) to a local SQLite database, then
redraws a graph from everything logged so far. The graph is only as long
as your history of runs — one data point after the first run, a real
trend line once you've been logging for a while.

Reuses the account-fetching logic from trading212_balance.py (must live in
the same directory) so credentials and .env handling stay in one place.

SETUP
-----
Same .env file as trading212_balance.py (TRADING212_API_KEY_ISA /
TRADING212_API_KEY_INVEST / CRYPTO_COM_KEY / CRYPTO_COM_SECRET etc.)

    pip install requests matplotlib --break-system-packages

USAGE
-----
    python3 net_worth_history.py                  # snapshot + redraw graph
    python3 net_worth_history.py --snapshot-only   # just log today's numbers (e.g. from cron)
    python3 net_worth_history.py --plot-only       # just redraw the graph from existing history
    python3 net_worth_history.py --show            # also open the graph in a window
    python3 net_worth_history.py --demo            # use Trading212's paper-trading environment

History lives in net_worth_history.db (SQLite, table "snapshots"), next to
this script. If an older net_worth_history.csv from a previous version of
this script is found and the database is still empty, its rows are
imported automatically on first run.

SCHEDULING
----------
Add a daily cron entry (see trading212_balance.py's own SCHEDULING section)
running this script with --snapshot-only, then run it without flags (or
with --plot-only) whenever you want an updated graph:

    0 7 * * * /usr/bin/python3 /path/to/net_worth_history.py --snapshot-only >> /path/to/net_worth.log 2>&1
"""

from __future__ import annotations

import argparse
import csv
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

try:
    import requests
except ImportError:
    sys.exit("Missing dependency. Run: pip install requests matplotlib --break-system-packages")

try:
    import matplotlib.dates as mdates
    import matplotlib.pyplot as plt
except ImportError:
    sys.exit("Missing dependency. Run: pip install matplotlib --break-system-packages")

try:
    import trading212_balance as t212
except ImportError:
    sys.exit("trading212_balance.py must be in the same directory as this script.")

DB_FILE = SCRIPT_DIR / "net_worth_history.db"
LEGACY_CSV_FILE = SCRIPT_DIR / "net_worth_history.csv"
GRAPH_FILE = SCRIPT_DIR / "net_worth_history.png"
CSV_FIELDS = ["timestamp", "currency", "t212_total", "crypto_total", "grand_total"]

SCHEMA = """
CREATE TABLE IF NOT EXISTS snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    currency TEXT NOT NULL,
    t212_total REAL NOT NULL,
    crypto_total REAL NOT NULL,
    grand_total REAL NOT NULL
)
"""


def collect_snapshot(env: str) -> list[dict]:
    """Fetch current totals from every configured source and return one row
    per currency observed (almost always just one, e.g. all-GBP)."""
    t212.load_dotenv(t212.ENV_FILE)
    base_url = "https://demo.trading212.com" if env == "demo" else "https://live.trading212.com"

    t212_totals = []
    for suffix, _label in t212.ACCOUNTS:
        api_key, api_secret = t212.account_credentials(suffix)
        if not api_key:
            continue
        session = requests.Session()
        session.headers.update({"Authorization": t212.build_auth_header(api_key, api_secret)})
        summary = t212.fetch(session, base_url, "/api/v0/equity/account/summary")
        t212_totals.append(t212.extract_totals(summary))

    cdc_totals: dict[str, dict] = {}
    cdc_key, cdc_secret = t212.cdc_credentials()
    if cdc_key and cdc_secret:
        data = t212.fetch_crypto_com(cdc_key, cdc_secret)
        cdc_totals = t212.cdc_extract_totals(data)

    if not t212_totals and not cdc_totals:
        sys.exit(
            "No API keys configured — nothing to snapshot. See trading212_balance.py's "
            "SETUP section for the .env variables it reads."
        )

    by_currency: dict[str, dict] = {}
    for totals in t212_totals:
        bucket = by_currency.setdefault(totals["currency"], {"t212_total": 0.0, "crypto_total": 0.0})
        bucket["t212_total"] += totals["total_value"]
    for currency, totals in cdc_totals.items():
        bucket = by_currency.setdefault(currency, {"t212_total": 0.0, "crypto_total": 0.0})
        bucket["crypto_total"] += totals["total"]

    timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    return [
        {
            "timestamp": timestamp,
            "currency": currency,
            "t212_total": round(vals["t212_total"], 2),
            "crypto_total": round(vals["crypto_total"], 2),
            "grand_total": round(vals["t212_total"] + vals["crypto_total"], 2),
        }
        for currency, vals in by_currency.items()
    ]


def get_connection(db_file: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_file))
    conn.execute(SCHEMA)
    return conn


def migrate_legacy_csv(csv_file: Path, db_file: Path) -> None:
    """One-time import of rows from the old CSV log, if one exists and the
    database is still empty."""
    if not csv_file.exists():
        return
    conn = get_connection(db_file)
    already_has_rows = conn.execute("SELECT 1 FROM snapshots LIMIT 1").fetchone() is not None
    if already_has_rows:
        conn.close()
        return
    with csv_file.open(newline="") as f:
        rows = list(csv.DictReader(f))
    if rows:
        with conn:
            conn.executemany(
                "INSERT INTO snapshots (timestamp, currency, t212_total, crypto_total, grand_total) "
                "VALUES (:timestamp, :currency, :t212_total, :crypto_total, :grand_total)",
                rows,
            )
        print(f"Migrated {len(rows)} row(s) from {csv_file.name} into {db_file.name}")
    conn.close()


def append_snapshot(rows: list[dict], db_file: Path) -> None:
    conn = get_connection(db_file)
    with conn:
        conn.executemany(
            "INSERT INTO snapshots (timestamp, currency, t212_total, crypto_total, grand_total) "
            "VALUES (:timestamp, :currency, :t212_total, :crypto_total, :grand_total)",
            rows,
        )
    conn.close()


def load_history(db_file: Path) -> list[dict]:
    if not db_file.exists():
        return []
    conn = get_connection(db_file)
    cursor = conn.execute(
        "SELECT timestamp, currency, t212_total, crypto_total, grand_total FROM snapshots ORDER BY timestamp"
    )
    columns = [d[0] for d in cursor.description]
    rows = [dict(zip(columns, row)) for row in cursor.fetchall()]
    conn.close()
    return rows


def plot_history(rows: list[dict], output_file: Path, currency: str | None, show: bool) -> str | None:
    if not rows:
        print(f"No history yet in {DB_FILE.name} — run without --plot-only first to log a snapshot.")
        return None

    currencies = sorted({r["currency"] for r in rows})
    chosen = currency or currencies[0]
    if currency and currency not in currencies:
        sys.exit(f"No history logged for currency '{currency}'. Available: {', '.join(currencies)}")
    if len(currencies) > 1 and not currency:
        print(f"Multiple currencies logged ({', '.join(currencies)}); plotting {chosen}. Use --currency to pick another.")

    series = [r for r in rows if r["currency"] == chosen]
    dates = [datetime.fromisoformat(r["timestamp"]) for r in series]
    t212_vals = [float(r["t212_total"]) for r in series]
    crypto_vals = [float(r["crypto_total"]) for r in series]
    grand_vals = [float(r["grand_total"]) for r in series]

    fig, ax = plt.subplots(figsize=(10, 5.5))
    if any(t212_vals):
        ax.plot(dates, t212_vals, marker="o", label="Trading212", linewidth=1.5)
    if any(crypto_vals):
        ax.plot(dates, crypto_vals, marker="o", label="Crypto.com", linewidth=1.5)
    ax.plot(dates, grand_vals, marker="o", label="Net worth", linewidth=2.5, color="black")

    ax.set_title(f"Net worth over time ({chosen})")
    ax.set_ylabel(chosen)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%Y-%m-%d"))
    fig.autofmt_xdate()
    ax.legend()
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(output_file, dpi=150)
    print(f"Graph saved to {output_file} ({len(series)} data point{'s' if len(series) != 1 else ''})")

    if show:
        plt.show()
    return chosen


def main() -> None:
    parser = argparse.ArgumentParser(description="Log and graph net worth over time")
    parser.add_argument("--demo", action="store_true", help="Use the Trading212 paper-trading (demo) environment")
    parser.add_argument("--snapshot-only", action="store_true", help="Only log today's snapshot, skip plotting")
    parser.add_argument("--plot-only", action="store_true", help="Only redraw the graph, skip fetching a new snapshot")
    parser.add_argument("--show", action="store_true", help="Also open the graph in a window")
    parser.add_argument("--currency", help="Which logged currency to plot (default: whichever appears first)")
    parser.add_argument("--db", type=Path, default=DB_FILE, help=f"SQLite database path (default: {DB_FILE})")
    parser.add_argument("--output", type=Path, default=GRAPH_FILE, help=f"Graph output path (default: {GRAPH_FILE})")
    args = parser.parse_args()

    if args.snapshot_only and args.plot_only:
        sys.exit("--snapshot-only and --plot-only are mutually exclusive.")

    migrate_legacy_csv(LEGACY_CSV_FILE, args.db)

    env = "demo" if args.demo else "live"

    if not args.plot_only:
        rows = collect_snapshot(env)
        append_snapshot(rows, args.db)
        for row in rows:
            print(
                f"Logged {row['timestamp']} — T212 {row['t212_total']:,.2f} + "
                f"Crypto.com {row['crypto_total']:,.2f} = {row['grand_total']:,.2f} {row['currency']}"
            )

    if not args.snapshot_only:
        plot_history(load_history(args.db), args.output, args.currency, args.show)


if __name__ == "__main__":
    main()
