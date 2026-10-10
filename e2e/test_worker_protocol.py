import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from e2e.worker_protocol import (
    WorkerProtocolError,
    artifact_inventory,
    build_job_manifest,
    build_result_manifest,
    manifest_sha256,
    model_binding_errors,
    validate_job_manifest,
    validate_result_manifest,
    worker_scenario_errors,
)
from e2e.orchestrator import current_build_version


ROOT = Path(__file__).resolve().parents[1]
CREATE_JOB = ROOT / "scripts" / "create-worker-job.py"


class WorkerProtocolTest(unittest.TestCase):
    def make_job(self, *, commitments=None):
        return build_job_manifest(
            job_id="job-001",
            shard_id="shard-01",
            source_commit="0123456789abcdef0123456789abcdef01234567",
            source_dirty=False,
            source_label="release-candidate",
            product_jar_name=(
                f"mcai_companion-{current_build_version()}.jar"
            ),
            product_sha256="a" * 64,
            forge_version="65.1.0",
            model_name="provider-model",
            base_url_host="provider.example",
            credential_present=True,
            credential_source="file",
            scenario_id="real_client_chat_follow_inventory",
            case_count=1,
            seed_commitments=commitments or ["b" * 64],
            timeout_seconds=7200,
            max_worker_hours=8.0,
            max_parallelism=2,
        )

    def test_job_hash_and_seed_commitments_are_bound_without_raw_seed(self):
        job = self.make_job()
        self.assertEqual(job, validate_job_manifest(job))
        self.assertNotIn("seed", json.dumps(job).lower().replace("seedcommitment", ""))
        tampered = dict(job)
        tampered["scenario"] = dict(job["scenario"])
        tampered["scenario"]["caseCount"] = 3
        with self.assertRaises(WorkerProtocolError):
            validate_job_manifest(tampered)

    def test_raw_seed_and_credentials_are_rejected(self):
        job = self.make_job()
        raw = dict(job)
        raw["manifestSha256"] = "a" * 64
        raw["scenario"] = dict(job["scenario"])
        raw["scenario"]["seed"] = 42
        with self.assertRaises(WorkerProtocolError):
            validate_job_manifest(raw)
        credential = dict(job)
        credential["manifestSha256"] = "a" * 64
        credential["model"] = dict(job["model"])
        credential["model"]["apiKey"] = "do-not-store"
        with self.assertRaises(WorkerProtocolError):
            validate_job_manifest(credential)

    def test_worker_model_metadata_is_bound_without_comparing_secret_values(self):
        expected = self.make_job()["model"]
        self.assertEqual(
            model_binding_errors(
                expected,
                {**expected, "credentialSource": "file"},
            ),
            [],
        )
        self.assertEqual(
            model_binding_errors(
                {**expected, "credentialSource": "injected"},
                {**expected, "credentialSource": "environment"},
            ),
            [],
        )
        self.assertEqual(
            model_binding_errors(
                expected,
                {**expected, "model": "different-model"},
            ),
            ["model"],
        )
        self.assertEqual(
            model_binding_errors(
                {**expected, "credentialSource": "injected"},
                {**expected, "credentialSource": None},
            ),
            ["credentialSource"],
        )

    def test_job_rejects_inconsistent_credential_presence_metadata(self):
        job = self.make_job()
        inconsistent = dict(job)
        inconsistent["model"] = {
            **job["model"],
            "credentialPresent": False,
        }
        with self.assertRaises(WorkerProtocolError):
            validate_job_manifest(inconsistent)

    def test_result_recomputes_artifact_hashes_and_provenance(self):
        job = self.make_job()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "oracle-result.json").write_text(
                '{"status":"PASS"}\n', encoding="utf-8"
            )
            job_model = self.make_job()["model"]
            (root / "functional-preflight.json").write_text(
                json.dumps(
                    {
                        "ready": True,
                        "model": job_model,
                        "jobModelBinding": {
                            "matches": True,
                            "mismatchedFields": [],
                        },
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            self.write_passing_run_evidence(root, job)
            result = build_result_manifest(
                job,
                status="PASS",
                exit_code=0,
                started_at_utc="2026-08-10T00:00:00Z",
                finished_at_utc="2026-08-10T00:01:00Z",
                root=root,
                functional_ai_claim=True,
                reason="real_client_slice_passed",
            )
            self.assertEqual(result, validate_result_manifest(result, root, job))
            self.assertEqual(
                hashlib.sha256(
                    (root / "oracle-result.json").read_bytes()
                ).hexdigest(),
                result["artifacts"]["oracle-result.json"]["sha256"],
            )
            (root / "oracle-result.json").write_text(
                '{"status":"FAIL"}\n', encoding="utf-8"
            )
            with self.assertRaises(WorkerProtocolError):
                validate_result_manifest(result, root)
            mismatched = self.make_job()
            mismatched["jobId"] = "other-job"
            mismatched["manifestSha256"] = "0" * 64
            with self.assertRaises(WorkerProtocolError):
                validate_result_manifest(result, root, mismatched)

    def test_worker_refuses_to_run_an_unimplemented_scenario(self):
        self.assertEqual(
            [],
            worker_scenario_errors({
                "id": "real_client_chat_follow_inventory",
                "caseCount": 1,
                "seedCommitments": ["a" * 64],
            }),
        )
        self.assertIn(
            "unsupported_scenario",
            worker_scenario_errors({
                "id": "m4_hidden_hardcore",
                "caseCount": 1000,
                "seedCommitments": ["a" * 64] * 1000,
            }),
        )
        self.assertIn(
            "unsupported_case_count",
            worker_scenario_errors({
                "id": "real_client_chat_follow_inventory",
                "caseCount": 2,
                "seedCommitments": ["a" * 64, "b" * 64],
            }),
        )

    def test_worker_run_archives_unsupported_scenario_without_launching_child(self):
        job = self.make_job()
        job["scenario"] = {
            "id": "m4_hidden_hardcore",
            "caseCount": 2,
            "seedCommitments": ["b" * 64, "c" * 64],
        }
        job["manifestSha256"] = manifest_sha256(job)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "job.json"
            output = root / "result"
            manifest.write_text(
                json.dumps(job) + "\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "run-e2e-worker.py"),
                    "run",
                    "--manifest",
                    str(manifest),
                    "--output",
                    str(output),
                ],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(2, completed.returncode, completed.stderr)
            result = json.loads(
                (output / "worker-result.json").read_text(encoding="utf-8")
            )
            self.assertEqual("NOT_RUN", result["status"])
            self.assertIn("unsupported_worker_scenario", result["reason"])
            self.assertFalse((output / "run").exists())

    def test_passing_result_rejects_unbound_model_preflight(self):
        job = self.make_job()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "functional-preflight.json").write_text(
                json.dumps(
                    {
                        "ready": True,
                        "model": {**job["model"], "model": "wrong-model"},
                        "jobModelBinding": {
                            "matches": False,
                            "mismatchedFields": ["model"],
                        },
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            result = build_result_manifest(
                job,
                status="PASS",
                exit_code=0,
                started_at_utc="2026-08-10T00:00:00Z",
                finished_at_utc="2026-08-10T00:01:00Z",
                root=root,
                functional_ai_claim=True,
                reason="claimed",
            )
            with self.assertRaises(WorkerProtocolError):
                validate_result_manifest(result, root, job)

    def test_passing_result_requires_causal_run_evidence(self):
        job = self.make_job()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "functional-preflight.json").write_text(
                json.dumps({
                    "ready": True,
                    "model": job["model"],
                    "jobModelBinding": {
                        "matches": True,
                        "mismatchedFields": [],
                    },
                }) + "\n",
                encoding="utf-8",
            )
            result = build_result_manifest(
                job,
                status="PASS",
                exit_code=0,
                started_at_utc="2026-08-10T00:00:00Z",
                finished_at_utc="2026-08-10T00:01:00Z",
                root=root,
                functional_ai_claim=True,
                reason="claimed",
            )
            with self.assertRaisesRegex(
                WorkerProtocolError,
                "run/manifest.json",
            ):
                validate_result_manifest(result, root, job)

    @staticmethod
    def write_passing_run_evidence(root: Path, job: dict) -> None:
        run = root / "run"
        run.mkdir(parents=True, exist_ok=True)
        (run / "manifest.json").write_text(
            json.dumps({
                "status": "PASS",
                "source": job["source"],
                "platform": {
                    "minecraft": job["minecraft"]["version"],
                    "forge": job["minecraft"]["forgeVersion"],
                },
                "artifacts": {
                    "productionJar": {
                        "sha256": job["product"]["sha256"],
                    },
                },
                "model": job["model"],
            }) + "\n",
            encoding="utf-8",
        )
        trace = {
            "requestId": "brain-1-1",
            "skillName": "follow_entity",
        }
        (run / "e2e-verdict.json").write_text(
            json.dumps({
                "status": "PASS",
                "missingEvidence": [],
                "causalModelTrace": trace,
                "inventoryCausalModelTrace": {
                    **trace,
                    "requestId": "brain-2-1",
                },
                "expectedProductSha256": job["product"]["sha256"],
                "loadedProductCopiesSha256": {
                    role: job["product"]["sha256"]
                    for role in ("server", "actor", "observer")
                },
            }) + "\n",
            encoding="utf-8",
        )
        (run / "oracle-result.json").write_text(
            json.dumps({"status": "PASS", "oraclePassed": True}) + "\n",
            encoding="utf-8",
        )
        for name in ("model-audit.jsonl", "action-trace.jsonl", "world-events.jsonl"):
            (run / name).write_text('{"type":"evidence"}\n', encoding="utf-8")

    def test_artifact_inventory_does_not_follow_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            regular = root / "regular.txt"
            regular.write_text("ok", encoding="utf-8")
            link = root / "link.txt"
            try:
                link.symlink_to(regular)
            except OSError:
                self.skipTest("symlinks unavailable")
            inventory = artifact_inventory(root)
            self.assertIn("regular.txt", inventory)
            self.assertNotIn("link.txt", inventory)

    def test_create_worker_job_binds_clean_checkout_and_jar_without_url_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            self.run_git(repo, "init", "-q")
            self.run_git(repo, "config", "user.email", "worker-test@example.invalid")
            self.run_git(repo, "config", "user.name", "Worker Test")
            (repo / "source.txt").write_text("clean\n", encoding="utf-8")
            self.run_git(repo, "add", "source.txt")
            self.run_git(repo, "commit", "-qm", "test source")
            product = root / "mcai_companion-test.jar"
            product.write_bytes(b"exact product bytes\n")
            commitments = root / "commitments.txt"
            commitments.write_text("a" * 64 + "\n" + "b" * 64 + "\n", encoding="utf-8")
            output = root / "job.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(CREATE_JOB),
                    "--repo",
                    str(repo),
                    "--job-id",
                    "job-clean",
                    "--shard-id",
                    "shard-01",
                    "--scenario-id",
                    "m4-hidden",
                    "--case-count",
                    "2",
                    "--seed-commitments-file",
                    str(commitments),
                    "--product-jar",
                    str(product),
                    "--forge-version",
                    "65.1.0",
                    "--model",
                    "provider-model",
                    "--base-url",
                    "https://provider.example/v1",
                    "--credential-present",
                    "--credential-source",
                    "injected",
                    "--output",
                    str(output),
                ],
                cwd=ROOT,
                env={**os.environ, "MCAI_API_KEY": "must-not-be-read"},
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            document = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(document, validate_job_manifest(document))
            self.assertFalse(document["source"]["dirty"])
            self.assertEqual(document["model"]["baseUrlHost"], "provider.example")
            self.assertNotIn("/v1", json.dumps(document))
            self.assertNotIn("must-not-be-read", output.read_text(encoding="utf-8"))

    def test_create_worker_job_rejects_dirty_checkout_and_raw_seed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            self.run_git(repo, "init", "-q")
            self.run_git(repo, "config", "user.email", "worker-test@example.invalid")
            self.run_git(repo, "config", "user.name", "Worker Test")
            (repo / "source.txt").write_text("initial\n", encoding="utf-8")
            self.run_git(repo, "add", "source.txt")
            self.run_git(repo, "commit", "-qm", "test source")
            (repo / "source.txt").write_text("dirty\n", encoding="utf-8")
            product = root / "product.jar"
            product.write_bytes(b"jar")
            commitments = root / "commitments.txt"
            commitments.write_text("a" * 64 + "\n", encoding="utf-8")
            common = [
                sys.executable,
                str(CREATE_JOB),
                "--repo",
                str(repo),
                "--job-id",
                "job-dirty",
                "--shard-id",
                "shard-01",
                "--scenario-id",
                "m1",
                "--case-count",
                "1",
                "--seed-commitments-file",
                str(commitments),
                "--product-jar",
                str(product),
                "--forge-version",
                "65.1.0",
                "--model",
                "provider-model",
                "--base-url",
                "https://provider.example/v1",
                "--credential-absent",
                "--output",
                str(root / "dirty.json"),
            ]
            dirty = subprocess.run(common, cwd=ROOT, check=False, capture_output=True, text=True)
            self.assertEqual(dirty.returncode, 3)
            self.assertIn("dirty checkout", dirty.stderr)
            self.run_git(repo, "add", "source.txt")
            self.run_git(repo, "commit", "-qm", "clean source")
            commitments.write_text("42\n", encoding="utf-8")
            raw_seed = subprocess.run(
                [*common, "--allow-dirty"],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(raw_seed.returncode, 3)
            self.assertIn("not SHA-256", raw_seed.stderr)

    @staticmethod
    def run_git(repo: Path, *arguments: str) -> None:
        completed = subprocess.run(
            ["git", "-C", str(repo), *arguments],
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            raise AssertionError(completed.stderr)
