#!/usr/bin/env python3
"""Compare benchmark results in ./results with another results folder.

Usage: scripts/compare_results.py OTHER_DIR [--base DIR]

Prints a markdown table of relative deltas (OTHER vs base) for requests/s and
CPU for every test/connections/framework present in both folders.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def parse_cpu(v):
    try:
        return float(str(v).rstrip("%"))
    except (TypeError, ValueError):
        return None


def load(root):
    out = {}
    for f in sorted(root.rglob("*.json")):
        try:
            d = json.loads(f.read_text())
        except (OSError, ValueError):
            continue
        if not isinstance(d, dict) or "rps" not in d:
            continue
        rel = f.relative_to(root).parts
        framework = f.stem
        test = "/".join(rel[:-1]) or "."
        try:
            rps = float(d["rps"])
        except (TypeError, ValueError):
            continue
        out[(test, framework)] = (rps, parse_cpu(d.get("cpu")))
    return out


def delta(a, b):
    if a is None or b is None or a == 0:
        return "n/a"
    return f"{(b - a) / a * 100:+.1f}%"


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("other", type=Path, help="results folder to compare against")
    p.add_argument("--base", type=Path, default=ROOT / "results",
                   help="base results folder (default: ./results)")
    args = p.parse_args()
    for d in (args.base, args.other):
        if not d.is_dir():
            sys.exit(f"Not a directory: {d}")

    base, other = load(args.base), load(args.other)
    rows = [["Test", "Framework", "RPS (base)", "RPS (other)", "RPS Δ",
             "CPU (base)", "CPU (other)", "CPU Δ"]]
    for key in sorted(set(base) & set(other)):
        (r1, c1), (r2, c2) = base[key], other[key]
        fmt = lambda c: "n/a" if c is None else f"{c:.1f}%"
        rows.append([key[0], key[1], f"{r1:.0f}", f"{r2:.0f}", delta(r1, r2),
                     fmt(c1), fmt(c2), delta(c1, c2)])
    if len(rows) == 1:
        sys.exit("No common results found.")

    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    left = {0, 1}

    def line(r):
        cells = [c.ljust(w) if i in left else c.rjust(w)
                 for i, (c, w) in enumerate(zip(r, widths))]
        return "| " + " | ".join(cells) + " |"

    sep = "| " + " | ".join(
        ("-" * w) if i in left else ("-" * (w - 1) + ":")
        for i, w in enumerate(widths)) + " |"
    print(line(rows[0]))
    print(sep)
    for r in rows[1:]:
        print(line(r))


if __name__ == "__main__":
    main()
