#!/usr/bin/env python3
"""Aggregate disjoint public hidden-seed summaries without exposing seeds."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.hidden_seed_protocol import (  # noqa: E402
    HiddenSeedEvidenceError,
    aggregate_summaries,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Fail-closed aggregation of executed hidden Hardcore seed "
            "summary.json files. Raw seeds are never read."
        )
    )
    parser.add_argument("summaries", nargs="+", type=Path)
    parser.add_argument(
        "--route",
        choices=("completion", "foundation"),
        default="completion",
    )
    parser.add_argument("--minimum-cases", type=int, required=True)
    parser.add_argument("--expected-product-sha256")
    parser.add_argument("--expected-source-commit")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        result = aggregate_summaries(
            args.summaries,
            args.route,
            minimum_cases=args.minimum_cases,
            expected_product_sha256=args.expected_product_sha256,
            expected_source_commit=args.expected_source_commit,
        )
    except (HiddenSeedEvidenceError, OSError, ValueError) as exception:
        result = {
            "schemaVersion": 1,
            "protocol": "fresh-hidden-random-seed-hardcore-v2",
            "route": args.route,
            "status": "FAIL",
            "minimumCases": args.minimum_cases,
            "error": type(exception).__name__ + ": " + str(exception)[:512],
            "claimBoundary": (
                "Invalid or incomplete evidence is never promoted to a "
                "statistical pass."
            ),
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("status") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
