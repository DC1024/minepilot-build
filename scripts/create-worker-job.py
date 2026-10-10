#!/usr/bin/env python3
"""Create a public, fail-closed worker job manifest.

The coordinator runs this command from a source checkout that contains the
exact product JAR.  Only the Git commit, JAR digest, endpoint host, model
identifier, and seed commitments are written to the manifest.  Credentials,
raw seeds, source paths, and world paths never cross the coordinator/worker
boundary.  A dirty checkout is rejected unless ``--allow-dirty`` is supplied;
that escape hatch is for development only and keeps ``source.dirty=true`` so
formal release gates cannot mistake it for release evidence.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.worker_protocol import (  # noqa: E402
    COMMIT_RE,
    WorkerProtocolError,
    build_job_manifest,
    sha256_file,
)


def _git_output(repo: Path, *arguments: str) -> str:
    try:
        completed = subprocess.run(
            ["git", "-C", str(repo), *arguments],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as exception:
        raise WorkerProtocolError("git is unavailable") from exception
    if completed.returncode != 0:
        detail = (completed.stderr or "").strip()[:256]
        raise WorkerProtocolError("cannot read Git source metadata" + (f": {detail}" if detail else ""))
    return completed.stdout.strip()


def source_metadata(repo: Path, *, allow_dirty: bool) -> tuple[str, bool]:
    repo = repo.resolve()
    commit = _git_output(repo, "rev-parse", "HEAD").lower()
    if COMMIT_RE.fullmatch(commit) is None:
        raise WorkerProtocolError("Git HEAD is not a full 40-hex commit")
    dirty = bool(_git_output(repo, "status", "--porcelain", "--untracked-files=all"))
    if dirty and not allow_dirty:
        raise WorkerProtocolError(
            "refusing a dirty checkout; commit all source changes or use --allow-dirty for non-release development"
        )
    return commit, dirty


def endpoint_host(base_url: str) -> str:
    """Return only a safe endpoint host; never serialize the URL or its path."""

    if not isinstance(base_url, str) or not base_url.strip():
        raise WorkerProtocolError("base URL is required")
    parsed = urlsplit(base_url.strip())
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise WorkerProtocolError("base URL must not contain credentials, query, or fragment")
    if parsed.scheme not in {"https", "http"} or not parsed.hostname:
        raise WorkerProtocolError("base URL must be an HTTP(S) URL with a host")
    if parsed.scheme != "https" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise WorkerProtocolError("non-HTTPS base URL is allowed only for a local host")
    return parsed.hostname.lower()


def read_seed_commitments(path: Path) -> list[str]:
    """Read one 64-hex commitment per line, or a JSON array of commitments."""

    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exception:
        raise WorkerProtocolError(f"cannot read seed commitment file: {path}") from exception
    if "\x00" in text:
        raise WorkerProtocolError("seed commitment file contains NUL bytes")
    stripped = text.strip()
    if not stripped:
        raise WorkerProtocolError("seed commitment file is empty")
    if stripped.startswith("["):
        try:
            parsed = json.loads(stripped)
        except json.JSONDecodeError as exception:
            raise WorkerProtocolError("seed commitment JSON is invalid") from exception
        if not isinstance(parsed, list):
            raise WorkerProtocolError("seed commitment JSON must be an array")
        values = parsed
    else:
        values = [line.strip() for line in text.splitlines() if line.strip()]
    if not values or len(values) > 100_000:
        raise WorkerProtocolError("seed commitment count is invalid")
    if any(not isinstance(value, str) for value in values):
        raise WorkerProtocolError("seed commitments must be text")
    # build_job_manifest/validate_job_manifest performs the canonical 64-hex
    # check.  Do not accept integers or raw numeric Minecraft seeds here.
    return [value.strip().lower() for value in values]


def write_json_once(path: Path, value: dict) -> None:
    path = path.resolve()
    if path.exists():
        raise WorkerProtocolError(f"refusing to overwrite existing job manifest: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    except OSError as exception:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        raise WorkerProtocolError(f"cannot write job manifest: {path}") from exception


def command_create(args: argparse.Namespace) -> int:
    if args.credential_present and args.credential_source is None:
        raise WorkerProtocolError("--credential-source is required with --credential-present")
    if not args.credential_present and args.credential_source is not None:
        raise WorkerProtocolError("--credential-source requires --credential-present")
    repo = Path(args.repo).resolve()
    commit, dirty = source_metadata(repo, allow_dirty=args.allow_dirty)
    product = Path(args.product_jar).resolve()
    if not product.is_file() or product.is_symlink():
        raise WorkerProtocolError(f"product JAR is not a regular file: {product}")
    if product.suffix.lower() != ".jar":
        raise WorkerProtocolError("product path must point to a .jar file")
    commitments = read_seed_commitments(Path(args.seed_commitments_file).resolve())
    manifest = build_job_manifest(
        job_id=args.job_id,
        shard_id=args.shard_id,
        source_commit=commit,
        source_dirty=dirty,
        source_label=args.source_label,
        product_jar_name=product.name,
        product_sha256=sha256_file(product),
        forge_version=args.forge_version,
        model_name=args.model,
        base_url_host=endpoint_host(args.base_url),
        credential_present=args.credential_present,
        credential_source=args.credential_source,
        scenario_id=args.scenario_id,
        case_count=args.case_count,
        seed_commitments=commitments,
        timeout_seconds=args.timeout_seconds,
        max_worker_hours=args.max_worker_hours,
        max_parallelism=args.max_parallelism,
    )
    write_json_once(Path(args.output), manifest)
    print(
        json.dumps(
            {
                "status": "PASS",
                "jobId": manifest["jobId"],
                "shardId": manifest["shardId"],
                "manifestSha256": manifest["manifestSha256"],
                "sourceCommit": commit,
                "sourceDirty": dirty,
                "productSha256": manifest["product"]["sha256"],
                "output": str(Path(args.output).resolve()),
            },
            ensure_ascii=False,
        )
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create a public worker job manifest without copying seeds or credentials."
    )
    parser.add_argument("--repo", type=Path, default=ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--shard-id", required=True)
    parser.add_argument("--scenario-id", required=True)
    parser.add_argument("--case-count", required=True, type=int)
    parser.add_argument("--seed-commitments-file", required=True, type=Path)
    parser.add_argument("--product-jar", required=True, type=Path)
    parser.add_argument("--forge-version", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--base-url", required=True)
    credential = parser.add_mutually_exclusive_group(required=True)
    credential.add_argument("--credential-present", action="store_true")
    credential.add_argument("--credential-absent", action="store_false", dest="credential_present")
    parser.add_argument("--credential-source", choices=["environment", "file", "injected"], default=None)
    parser.add_argument("--source-label", default="coordinator")
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--max-worker-hours", type=float, default=8.0)
    parser.add_argument("--max-parallelism", type=int, default=1)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--allow-dirty",
        action="store_true",
        help="allow a development-only dirty source; manifest remains source.dirty=true",
    )
    args = parser.parse_args()
    try:
        return command_create(args)
    except WorkerProtocolError as exception:
        print(f"worker protocol error: {exception}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
