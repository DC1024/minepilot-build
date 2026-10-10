#!/usr/bin/env python3
"""Verify one worker result against its exact public job manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.worker_protocol import (  # noqa: E402
    WorkerProtocolError,
    validate_job_manifest,
    validate_result_manifest,
)


def read_object(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise WorkerProtocolError(f"invalid JSON: {path}") from exception
    if not isinstance(value, dict):
        raise WorkerProtocolError(f"JSON document is not an object: {path}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fail-closed verification of one worker result bundle."
    )
    parser.add_argument("--job", required=True, type=Path)
    parser.add_argument("--result", required=True, type=Path)
    args = parser.parse_args()
    try:
        job = validate_job_manifest(read_object(args.job))
        result_root = args.result.resolve()
        result = validate_result_manifest(
            read_object(result_root / "worker-result.json"),
            result_root,
            job,
        )
    except (WorkerProtocolError, OSError, ValueError) as exception:
        print(
            json.dumps(
                {
                    "status": "FAIL",
                    "error": type(exception).__name__ + ": " + str(exception)[:512],
                },
                ensure_ascii=False,
            )
        )
        return 1
    print(
        json.dumps(
            {
                "status": "PASS",
                "jobId": result["jobId"],
                "shardId": result["shardId"],
                "resultStatus": result["status"],
                "resultSha256": result["resultSha256"],
                "functionalAiClaim": result["functionalAiClaim"],
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

