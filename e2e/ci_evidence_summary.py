#!/usr/bin/env python3
"""Summarize the newest real-client evidence without exposing credentials."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def read_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


FUNCTIONAL_SCENARIO = "real_client_chat_follow_inventory"


def newest_manifest(results: Path) -> Path | None:
    candidates = [
        path
        for path in results.glob("*/*/manifest.json")
        if read_object(path).get("scenario") == FUNCTIONAL_SCENARIO
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime_ns)


def clean_cell(value: Any) -> str:
    return str(value if value is not None else "unknown").replace(
        "|",
        "\\|",
    ).replace("\n", " ")


def render(results: Path) -> tuple[str, int]:
    manifest_path = newest_manifest(results)
    if manifest_path is None:
        return (
            "## Minecraft AI Companion functional E2E\n\n"
            "No evidence manifest was produced.\n",
            1,
        )

    run_root = manifest_path.parent
    manifest = read_object(manifest_path)
    verdict = read_object(run_root / "e2e-verdict.json")
    infrastructure_error = read_object(
        run_root / "infrastructure-error.json"
    )
    artifact = manifest.get("artifacts", {}).get("productionJar", {})
    model = manifest.get("model", {})
    platform = manifest.get("platform", {})
    missing = verdict.get("missingEvidence", [])
    if not isinstance(missing, list):
        missing = []

    verified_status = verdict.get("status")
    if verified_status not in {"PASS", "FAIL"}:
        verified_status = None
    status = (
        verified_status
        or ("ERROR" if infrastructure_error else None)
        or manifest.get("status")
        or "ERROR"
    )
    lines = [
        "## Minecraft AI Companion functional E2E",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| Run | `{clean_cell(manifest.get('runId'))}` |",
        f"| Verdict | **{clean_cell(status)}** |",
        f"| Evidence class | {clean_cell(manifest.get('evidenceClass'))} |",
        f"| Forge | {clean_cell(platform.get('forge'))} |",
        f"| Product SHA-256 | `{clean_cell(artifact.get('sha256'))}` |",
        f"| Model | {clean_cell(model.get('model'))} |",
        f"| API host | {clean_cell(model.get('baseUrlHost'))} |",
        (
            "| Credential present | "
            f"{clean_cell(model.get('credentialPresent'))} |"
        ),
        (
            "| Model causal request | "
            f"{clean_cell(verdict.get('causalModelTrace', {}).get('requestId') if isinstance(verdict.get('causalModelTrace'), dict) else None)} |"
        ),
        (
            "| Inventory causal request | "
            f"{clean_cell(verdict.get('inventoryCausalModelTrace', {}).get('requestId') if isinstance(verdict.get('inventoryCausalModelTrace'), dict) else None)} |"
        ),
        (
            "| Observer displacement | "
            f"{clean_cell(verdict.get('observerAiDisplacement'))} |"
        ),
        (
            "| Observer rendered PNG | "
            f"{clean_cell(verdict.get('observerRenderedScreenshot'))} |"
        ),
        "",
    ]
    if missing:
        lines.extend(
            [
                "Missing evidence:",
                "",
                *(f"- `{clean_cell(item)}`" for item in missing),
                "",
            ]
        )
    if infrastructure_error:
        lines.extend(
            [
                "Infrastructure error:",
                "",
                (
                    f"- `{clean_cell(infrastructure_error.get('type'))}`: "
                    f"{clean_cell(infrastructure_error.get('message'))}"
                ),
                "",
            ]
        )
    lines.append(
        f"Archived run root: `{run_root.as_posix()}`"
    )
    lines.append("")
    return "\n".join(lines), 0 if verified_status == "PASS" else 1


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    value.add_argument("--results", required=True, type=Path)
    value.add_argument("--step-summary", type=Path)
    return value


def main() -> int:
    args = parser().parse_args()
    summary, status = render(args.results)
    if args.step_summary is not None:
        args.step_summary.parent.mkdir(parents=True, exist_ok=True)
        with args.step_summary.open("a", encoding="utf-8") as stream:
            stream.write(summary)
    print(summary)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
