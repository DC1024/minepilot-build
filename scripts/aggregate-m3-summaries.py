#!/usr/bin/env python3
"""Aggregate real M3 companion evidence summaries without private data."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.m3_protocol import (  # noqa: E402
    M3EvidenceError,
    aggregate_summaries,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Fail-closed aggregation of real dedicated-server/client/model "
            "M3 companion summaries."
        )
    )
    parser.add_argument("summaries", nargs="+", type=Path)
    parser.add_argument("--expected-product-sha256")
    parser.add_argument("--expected-source-commit")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        result = aggregate_summaries(
            args.summaries,
            expected_product_sha256=args.expected_product_sha256,
            expected_source_commit=args.expected_source_commit,
        )
    except (M3EvidenceError, OSError, ValueError) as exception:
        result = {
            "schemaVersion": 1,
            "protocol": "real-m3-companion-v1",
            "status": "FAIL",
            "error": type(exception).__name__ + ": " + str(exception)[:512],
            "claimBoundary": (
                "Invalid or incomplete M3 evidence is never promoted to a "
                "professional companion pass."
            ),
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("status") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
