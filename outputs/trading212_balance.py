#!/usr/bin/env python3
"""
Trading212 daily balance summary.

Pulls your account summary (cash, invested value, P&L) and optionally your
open positions from the Trading212 Public API, and prints a short summary.

Also pulls your Crypto.com portfolio (cash + crypto balances across all
products: wallet, staking, etc.) via the Crypto.com App API, and folds it
into a combined net-worth total alongside Trading212.

SETUP
-----
1. Trading212 issues a separate API key per account, so if you have both a
   Stocks ISA and an Invest account, generate a key for each (in the app:
   Settings -> API, switch accounts to get a key for the other one) and put
   both in a `.env` file next to this script:

       TRADING212_API_KEY_ISA=your_isa_key_here
       # TRADING212_API_SECRET_ISA=your_isa_secret_here      # only needed for newer key/secret pairs

       TRADING212_API_KEY_INVEST=your_invest_key_here
       # TRADING212_API_SECRET_INVEST=your_invest_secret_here

       # TRADING212_ENV=live                       # "live" (default) or "demo"

   You only need to set the pair(s) for the account(s) you want to check.
   (For backwards compatibility, a plain TRADING212_API_KEY / _SECRET is
   still read as the ISA account if TRADING212_API_KEY_ISA isn't set.)

   For Crypto.com, add an API key (Settings -> API in the app):

       CRYPTO_COM_KEY=your_crypto_com_key_here
       CRYPTO_COM_SECRET=your_crypto_com_secret_here

2. Install dependencies:

       pip install requests --break-system-packages

3. Run it:

       python3 trading212_balance.py                 # everything configured (T212 + Crypto.com)
       python3 trading212_balance.py --account isa    # Stocks ISA only
       python3 trading212_balance.py --account invest # Invest only
       python3 trading212_balance.py --positions       # also list open T212 positions
       python3 trading212_balance.py --demo             # use the paper-trading environment
       python3 trading212_balance.py --no-crypto         # Trading212 only
       python3 trading212_balance.py --no-t212           # Crypto.com only

AUTHENTICATION
---------------
Trading212 supports two auth styles on the same endpoints:
  - Legacy: a single API key sent raw in the `Authorization` header.
  - Newer key/secret pairs: HTTP Basic auth built from "API_KEY:API_SECRET".

This script picks Basic auth automatically if the account's *_SECRET is set,
otherwise it sends the key alone (legacy style). If you get a 401, try
setting the matching *_SECRET too (some newer keys require it).

Crypto.com requests are signed with HMAC-SHA256 over
"<timestamp><METHOD><path><body>", sent as the Cdc-Api-Key /
Cdc-Api-Timestamp / Cdc-Api-Signature headers.

SCHEDULING
----------
To get a daily summary automatically, schedule this script with your OS:
  - macOS/Linux: add a line to `crontab -e`, e.g.
        0 7 * * * /usr/bin/python3 /path/to/trading212_balance.py >> /path/to/t212.log 2>&1
  - Windows: use Task Scheduler to run `python trading212_balance.py` daily.

NOTE: The Trading212 Public API only supports Invest and Stocks ISA accounts.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("Missing dependency. Run: pip install requests --break-system-packages")

SCRIPT_DIR = Path(__file__).resolve().parent
ENV_FILE = SCRIPT_DIR / ".env"


def load_dotenv(path: Path) -> None:
    """Minimal .env loader (no external dependency). Does not override
    variables already set in the real environment."""
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def build_auth_header(api_key: str, api_secret: str | None) -> str:
    if api_secret:
        raw = f"{api_key}:{api_secret}".encode("utf-8")
        return "Basic " + base64.b64encode(raw).decode("utf-8")
    return api_key


def fetch(session: requests.Session, base_url: str, path: str) -> dict:
    resp = session.get(f"{base_url}{path}", timeout=15)
    if resp.status_code == 401:
        sys.exit(
            "401 Unauthorized: your API key was rejected. Double-check "
            "TRADING212_API_KEY (and TRADING212_API_SECRET if your key needs one) "
            "in your .env file."
        )
    if resp.status_code == 429:
        sys.exit("429 Rate limited: you're calling the API too often. Wait a bit and retry.")
    resp.raise_for_status()
    return resp.json()


def format_money(value: float, currency: str) -> str:
    return f"{value:,.2f} {currency}"


ACCOUNTS = [
    ("isa", "Stocks ISA"),
    ("invest", "Invest"),
]


def account_credentials(suffix: str) -> tuple[str | None, str | None]:
    """Look up an account's API key/secret, falling back to the unsuffixed
    TRADING212_API_KEY / TRADING212_API_SECRET for the ISA account only
    (kept for backwards compatibility with single-account setups)."""
    api_key = os.environ.get(f"TRADING212_API_KEY_{suffix.upper()}")
    api_secret = os.environ.get(f"TRADING212_API_SECRET_{suffix.upper()}")
    if not api_key and suffix == "isa":
        api_key = os.environ.get("TRADING212_API_KEY")
        api_secret = os.environ.get("TRADING212_API_SECRET")
    return api_key, api_secret


def extract_totals(summary: dict) -> dict:
    currency = summary.get("currency", "")
    cash = summary.get("cash", {})
    investments = summary.get("investments", {})

    available = cash.get("availableToTrade", 0)
    reserved = cash.get("reservedForOrders", 0)
    in_pies = cash.get("inPies", 0)
    total_cash = available + reserved + in_pies

    current_value = investments.get("currentValue", 0)
    unrealized = investments.get("unrealizedProfitLoss", 0)
    realized = investments.get("realizedProfitLoss", 0)
    total_value = summary.get("totalValue", current_value + total_cash)

    return {
        "currency": currency,
        "available": available,
        "reserved": reserved,
        "in_pies": in_pies,
        "current_value": current_value,
        "unrealized": unrealized,
        "realized": realized,
        "total_value": total_value,
    }


def print_totals(header: str, totals: dict) -> None:
    currency = totals["currency"]
    print(header)
    print("-" * 40)
    print(f"Total account value:   {format_money(totals['total_value'], currency)}")
    print(f"  Cash available:      {format_money(totals['available'], currency)}")
    print(f"  Cash reserved:       {format_money(totals['reserved'], currency)}")
    print(f"  Cash in pies:        {format_money(totals['in_pies'], currency)}")
    print(f"  Investments value:   {format_money(totals['current_value'], currency)}")
    print(f"Unrealized P&L:        {format_money(totals['unrealized'], currency)}")
    print(f"Realized P&L (all-time): {format_money(totals['realized'], currency)}")


def print_summary(env: str, label: str, summary: dict) -> None:
    totals = extract_totals(summary)
    print_totals(f"Trading212 ({env}) — {label} — account #{summary.get('id')}", totals)


def aggregate_totals(all_totals: list[dict]) -> dict[str, dict]:
    """Sum per-account totals, grouped by currency (accounts are normally
    all the same currency, but this stays correct if they ever differ)."""
    by_currency: dict[str, dict] = {}
    for totals in all_totals:
        bucket = by_currency.setdefault(
            totals["currency"],
            {"currency": totals["currency"], "available": 0, "reserved": 0, "in_pies": 0,
             "current_value": 0, "unrealized": 0, "realized": 0, "total_value": 0},
        )
        for field in ("available", "reserved", "in_pies", "current_value", "unrealized", "realized", "total_value"):
            bucket[field] += totals[field]
    return by_currency


CDC_BASE_URL = "https://wapi.crypto.com"


def cdc_credentials() -> tuple[str | None, str | None]:
    api_key = os.environ.get("CRYPTO_COM_KEY") or os.environ.get("CDC_API_KEY")
    api_secret = os.environ.get("CRYPTO_COM_SECRET") or os.environ.get("CDC_API_SECRET")
    return api_key, api_secret


def cdc_fetch(session: requests.Session, method: str, path: str, api_key: str, api_secret: str, body: dict | None = None) -> dict:
    timestamp = str(int(time.time() * 1000))
    body_str = json.dumps(body) if body else ""
    payload = f"{timestamp}{method.upper()}{path}{body_str}"
    signature = base64.b64encode(hmac.new(api_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).digest()).decode("utf-8")

    headers = {
        "Cdc-Api-Key": api_key,
        "Cdc-Api-Timestamp": timestamp,
        "Cdc-Api-Signature": signature,
    }
    if body:
        headers["Content-Type"] = "application/json"

    resp = session.request(method, f"{CDC_BASE_URL}{path}", headers=headers, json=body, timeout=15)
    if resp.status_code == 429:
        sys.exit("429 Rate limited (Crypto.com): you're calling the API too often. Wait a bit and retry.")
    data = resp.json()
    if resp.status_code != 200 or not data.get("ok"):
        error = data.get("error") or data.get("code")
        message = data.get("error_message") or data.get("message")
        detail = f"{error}: {message}" if error and message else (error or message or f"HTTP {resp.status_code}")
        sys.exit(f"Crypto.com API error: {detail}")
    return data


def fetch_crypto_com(api_key: str, api_secret: str) -> dict:
    session = requests.Session()
    fiat = cdc_fetch(session, "GET", "/v1/fiat-account", api_key, api_secret)
    crypto = cdc_fetch(session, "GET", "/v1/crypto-account", api_key, api_secret)
    portfolio = cdc_fetch(session, "GET", "/v1/portfolio", api_key, api_secret)
    return {"fiat": fiat, "crypto": crypto, "portfolio": portfolio}


def cdc_extract_totals(data: dict) -> dict[str, dict]:
    """Sum cash + crypto value (across all products: wallet, staking, etc.)
    grouped by currency, using the portfolio breakdown rather than the raw
    wallet balances so staked/locked funds aren't missed or double-counted."""
    by_currency: dict[str, dict] = {}

    for balance in data["fiat"]["account"]["balances"]:
        amount = float(balance["amount"]["amount"])
        if amount <= 0:
            continue
        currency = balance["currency"]
        bucket = by_currency.setdefault(currency, {"currency": currency, "cash": 0.0, "crypto_value": 0.0})
        bucket["cash"] += amount

    for product in data["portfolio"].get("products", []):
        price_native = product.get("price_native", {})
        amount = float(price_native.get("amount", 0) or 0)
        if amount <= 0:
            continue
        currency = price_native.get("currency", "")
        bucket = by_currency.setdefault(currency, {"currency": currency, "cash": 0.0, "crypto_value": 0.0})
        bucket["crypto_value"] += amount

    for bucket in by_currency.values():
        bucket["total"] = bucket["cash"] + bucket["crypto_value"]
    return by_currency


def cdc_nonzero_wallets(data: dict) -> list:
    wallets = data["crypto"]["account"]["wallets"]
    return [w for w in wallets if float(w.get("balance", {}).get("amount", 0) or 0) > 0]


def print_crypto_com(by_currency: dict[str, dict], wallets: list) -> None:
    print("Crypto.com")
    print("-" * 40)
    for currency, totals in by_currency.items():
        print(f"Total value:           {format_money(totals['total'], currency)}")
        print(f"  Cash:                {format_money(totals['cash'], currency)}")
        print(f"  Crypto (all products): {format_money(totals['crypto_value'], currency)}")
    if wallets:
        print("Holdings (wallet, available for trading):")
        for w in wallets:
            ticker = w["currency"]
            amount = w["balance"]["amount"]
            native = w.get("native_balance", {})
            native_str = format_money(float(native["amount"]), native["currency"]) if native.get("amount") else ""
            print(f"  {ticker:<8} {amount:<16} {native_str}")


def print_positions(positions: list) -> None:
    print("\nOpen positions")
    print("-" * 40)
    if not positions:
        print("(none)")
    for p in positions:
        instrument = p.get("instrument", {})
        wallet = p.get("walletImpact", {})
        ticker = instrument.get("ticker", "?")
        qty = p.get("quantity", 0)
        cur_price = p.get("currentPrice", 0)
        pnl = wallet.get("unrealizedProfitLoss", 0)
        cur = wallet.get("currency", "")
        print(f"  {ticker:<15} qty={qty:<10} price={cur_price:<10} P&L={format_money(pnl, cur)}")


def run_account(base_url: str, env: str, suffix: str, label: str, api_key: str, api_secret: str | None, args) -> dict:
    session = requests.Session()
    session.headers.update({"Authorization": build_auth_header(api_key, api_secret)})

    summary = fetch(session, base_url, "/api/v0/equity/account/summary")

    if args.json:
        print(json.dumps({"account": suffix, "summary": summary}, indent=2))
    else:
        print_summary(env, label, summary)

    if args.positions:
        positions = fetch(session, base_url, "/api/v0/equity/positions")
        if args.json:
            print(json.dumps({"account": suffix, "positions": positions}, indent=2))
        else:
            print_positions(positions)

    return summary


def main() -> None:
    load_dotenv(ENV_FILE)

    parser = argparse.ArgumentParser(description="Trading212 balance summary")
    parser.add_argument("--demo", action="store_true", help="Use the paper-trading (demo) environment")
    parser.add_argument("--positions", action="store_true", help="Also list open positions")
    parser.add_argument("--json", action="store_true", help="Print raw JSON instead of a formatted summary")
    parser.add_argument(
        "--account",
        choices=["isa", "invest", "all"],
        default="all",
        help="Which Trading212 account to check (default: all configured accounts)",
    )
    parser.add_argument("--no-crypto", action="store_true", help="Skip Crypto.com even if credentials are configured")
    parser.add_argument("--no-t212", action="store_true", help="Skip Trading212 (Crypto.com only)")
    args = parser.parse_args()

    env = "demo" if args.demo else os.environ.get("TRADING212_ENV", "live").lower()
    base_url = "https://demo.trading212.com" if env == "demo" else "https://live.trading212.com"

    t212_totals = []
    if not args.no_t212:
        wanted = ACCOUNTS if args.account == "all" else [a for a in ACCOUNTS if a[0] == args.account]

        runs = []
        for suffix, label in wanted:
            api_key, api_secret = account_credentials(suffix)
            if api_key:
                runs.append((suffix, label, api_key, api_secret))
            elif args.account == suffix:
                sys.exit(
                    f"No API key found for the {label} account. Add this to your .env file:\n"
                    f"  TRADING212_API_KEY_{suffix.upper()}=your_key_here"
                )

        for i, (suffix, label, api_key, api_secret) in enumerate(runs):
            if i > 0 and not args.json:
                print()
            summary = run_account(base_url, env, suffix, label, api_key, api_secret, args)
            t212_totals.append(extract_totals(summary))

        if len(runs) > 1:
            agg = aggregate_totals(t212_totals)
            if args.json:
                print(json.dumps({"account": "aggregate", "totals": agg}, indent=2))
            else:
                print()
                for currency, totals in agg.items():
                    header = "TOTAL — all accounts" if len(agg) == 1 else f"TOTAL — all accounts ({currency})"
                    print_totals(header, totals)

    cdc_totals: dict[str, dict] = {}
    if not args.no_crypto:
        cdc_key, cdc_secret = cdc_credentials()
        if cdc_key and cdc_secret:
            if t212_totals and not args.json:
                print()
            data = fetch_crypto_com(cdc_key, cdc_secret)
            cdc_totals = cdc_extract_totals(data)
            wallets = cdc_nonzero_wallets(data)
            if args.json:
                print(json.dumps({"account": "crypto_com", "totals": cdc_totals, "wallets": wallets}, indent=2))
            else:
                print_crypto_com(cdc_totals, wallets)

    if not t212_totals and not cdc_totals:
        sys.exit(
            "No API keys found. Add at least one of these to your .env file:\n"
            "  TRADING212_API_KEY_ISA=your_isa_key_here\n"
            "  TRADING212_API_KEY_INVEST=your_invest_key_here\n"
            "  CRYPTO_COM_KEY=your_crypto_com_key_here (with CRYPTO_COM_SECRET)"
        )

    if t212_totals and cdc_totals:
        combined: dict[str, float] = {}
        for totals in t212_totals:
            combined[totals["currency"]] = combined.get(totals["currency"], 0.0) + totals["total_value"]
        for currency, totals in cdc_totals.items():
            combined[currency] = combined.get(currency, 0.0) + totals["total"]

        if args.json:
            print(json.dumps({"account": "grand_total", "totals": combined}, indent=2))
        else:
            print()
            print("NET WORTH — Trading212 + Crypto.com")
            print("-" * 40)
            for currency, total in combined.items():
                print(f"Total: {format_money(total, currency)}")


if __name__ == "__main__":
    main()
