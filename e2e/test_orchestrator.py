import base64
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from e2e.orchestrator import (
    CAUSAL_AUDIT_TYPES,
    accepted_task_goal_after_chat,
    causal_model_trace,
    E2E_ACTOR_NAME,
    forge_version,
    functional_preflight,
    java_major_version,
    model_metadata,
    normalized_chat_sha256,
    offline_player_uuid,
    observer_motion_summary,
    record_functional_preflight_failure,
    record_anchor_infrastructure_not_run,
    current_build_version,
    verify_evidence,
    verify_delayed_anchor_evidence,
    without_model_credentials,
    write_instance_files,
)

TEST_NONCE = "TESTNONCE"


class OrchestratorEvidenceTest(unittest.TestCase):
    def test_java_major_parser_does_not_expose_version_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = root / "java"
            java.write_text(
                "#!/bin/sh\n"
                "echo 'openjdk version \"21.0.8\"' >&2\n",
                encoding="utf-8",
            )
            java.chmod(0o755)
            self.assertEqual(21, java_major_version(str(java)))

    def test_functional_preflight_rejects_non_java25(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java_dir = root / "bin"
            java_dir.mkdir()
            java = java_dir / "java"
            java.write_text(
                "#!/bin/sh\n"
                "echo 'openjdk version \"21.0.8\"' >&2\n",
                encoding="utf-8",
            )
            java.chmod(0o755)
            report = functional_preflight(
                {
                    "JAVA_HOME": str(root),
                    "MCAI_BASE_URL": "https://provider.example/v1",
                    "MCAI_MODEL": "model-name",
                    "MCAI_API_KEY": "test-only-placeholder",
                },
                "65.1.0",
            )

        self.assertFalse(report["ready"])
        self.assertEqual(21, report["javaMajor"])
        self.assertIn("java25", report["missing"])
        self.assertNotIn("21.0.8", json.dumps(report))

    def test_artifact_version_is_bound_to_current_gradle_source(self):
        version = current_build_version()
        self.assertRegex(version, r"^0\.1\.\d+-dev-mc26\.2$")
        build_source = (Path(__file__).resolve().parents[1] / "build.gradle").read_text(
            encoding="utf-8"
        )
        self.assertIn(f"version = '{version}'", build_source)

    def test_functional_preflight_is_not_a_gameplay_verdict(self):
        report = functional_preflight(
            {
                "MCAI_BASE_URL": "https://provider.example/v1",
                "MCAI_MODEL": "model-name",
            },
            "65.1.0",
        )

        self.assertEqual("functional_e2e_preflight", report["kind"])
        self.assertFalse(report["ready"])
        self.assertIn("MCAI_API_KEY_or_MCAI_API_KEY_FILE", report["missing"])
        self.assertTrue(report["physicalDisplayUntouched"])
        self.assertNotIn("apiKey", json.dumps(report))

    def test_runtime_forge_selector_accepts_only_65_patches(self):
        self.assertEqual("65.1.0", forge_version("65.1.0"))
        with self.assertRaises(Exception):
            forge_version("66.0.0")
        with self.assertRaises(Exception):
            forge_version("../65.1.0")

    def test_chat_digest_uses_trimmed_code_point_bounded_text(self):
        self.assertEqual(
            hashlib.sha256(b"hello").hexdigest(),
            normalized_chat_sha256(" \thello\n"),
        )
        emoji = "🙂"
        self.assertEqual(
            hashlib.sha256((emoji * 512).encode("utf-8")).hexdigest(),
            normalized_chat_sha256(emoji * 513),
        )
        self.assertIsNone(normalized_chat_sha256(" \n\t "))

    def test_isolated_server_authorizes_only_the_real_offline_actor(self):
        self.assertEqual("MCAIActor", E2E_ACTOR_NAME)
        self.assertEqual(
            "73328ef7-064e-35fd-adaf-fdc3c77b8fdf",
            offline_player_uuid(E2E_ACTOR_NAME),
        )
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            server = run_root / "instances" / "server"
            server.mkdir(parents=True)
            (run_root / "instances" / "actor").mkdir(parents=True)
            (run_root / "instances" / "observer").mkdir(parents=True)
            write_instance_files(run_root, 25575, "AUTHZTEST")
            config = (
                server / "config" / "mcai-companion.toml"
            ).read_text(encoding="utf-8")
            self.assertIn(
                "73328ef7-064e-35fd-adaf-fdc3c77b8fdf",
                config,
            )
            self.assertNotIn("ops", config)

    def test_delayed_anchor_verifier_requires_zero_human_wait_and_clients(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            for role in ("server", "actor", "observer"):
                mods = run_root / "instances" / role / "mods"
                mods.mkdir(parents=True)
                (mods / "mcai_companion-test.jar").write_bytes(b"product")
            expected = hashlib.sha256(b"product").hexdigest()
            oracle_events = [
                {"type": "dedicated_server_started"},
                {
                    "type": "delayed_anchor_zero_human_active",
                    "zeroHumanTicks": 40,
                },
                {"type": "delayed_anchor_human_login_observed"},
                {
                    "type": "delayed_anchor_objective_oracle_passed",
                    "zeroHumanTicks": 45,
                },
            ]
            oracle_events = [
                {"nonce": TEST_NONCE, **event}
                for event in oracle_events
            ]
            (run_root / "oracle-events.jsonl").write_text(
                "".join(json.dumps(event) + "\n" for event in oracle_events),
                encoding="utf-8",
            )
            (run_root / "oracle-result.json").write_text(
                json.dumps({
                    "nonce": TEST_NONCE,
                    "status": "PASS",
                    "reason": "delayed_initial_anchor_passed",
                    "scenario": "delayed_first_human_anchor",
                    "delayedAnchorPassed": True,
                }),
                encoding="utf-8",
            )
            for role in ("actor", "observer"):
                events = [
                    {"type": "client_logged_in"},
                    {"type": "scenario_ready_received"},
                    {
                        "type": "client_initial_anchor_observed",
                        "sameDimension": True,
                        "distance": 6.0,
                    },
                ]
                events = [
                    {"nonce": TEST_NONCE, **event}
                    for event in events
                ]
                (run_root / f"{role}-client-events.jsonl").write_text(
                    "".join(json.dumps(event) + "\n" for event in events),
                    encoding="utf-8",
                )
            verdict = verify_delayed_anchor_evidence(
                run_root,
                expected,
                TEST_NONCE,
            )
            self.assertEqual("PASS", verdict["status"])
            self.assertEqual(45, verdict["zeroHumanTicks"])

            observer_path = run_root / "observer-client-events.jsonl"
            observer_events = self.read_json_lines(observer_path)
            observer_events[0]["nonce"] = "OTHER-RUN"
            self.write_json_lines(observer_path, observer_events)
            mixed_run = verify_delayed_anchor_evidence(
                run_root,
                expected,
                TEST_NONCE,
            )
            self.assertEqual("FAIL", mixed_run["status"])
            self.assertIn(
                "observer:run-nonce-binding",
                mixed_run["missingEvidence"],
            )

    def test_delayed_anchor_verifier_rejects_short_zero_human_window(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            for role in ("server", "actor", "observer"):
                mods = run_root / "instances" / role / "mods"
                mods.mkdir(parents=True)
                (mods / "mcai_companion-test.jar").write_bytes(b"product")
            expected = hashlib.sha256(b"product").hexdigest()
            events = [
                {"type": "dedicated_server_started"},
                {"type": "delayed_anchor_zero_human_active"},
                {"type": "delayed_anchor_human_login_observed"},
                {"type": "delayed_anchor_objective_oracle_passed"},
            ]
            events = [
                {"nonce": TEST_NONCE, **event}
                for event in events
            ]
            (run_root / "oracle-events.jsonl").write_text(
                "".join(json.dumps(event) + "\n" for event in events),
                encoding="utf-8",
            )
            (run_root / "oracle-result.json").write_text(
                json.dumps({
                    "nonce": TEST_NONCE,
                    "status": "PASS",
                    "reason": "delayed_initial_anchor_passed",
                    "scenario": "delayed_first_human_anchor",
                    "delayedAnchorPassed": True,
                }),
                encoding="utf-8",
            )
            for role in ("actor", "observer"):
                (run_root / f"{role}-client-events.jsonl").write_text(
                    "\n".join([
                        json.dumps({
                            "nonce": TEST_NONCE,
                            "type": "client_logged_in",
                        }),
                        json.dumps({
                            "nonce": TEST_NONCE,
                            "type": "scenario_ready_received",
                        }),
                        json.dumps({
                            "nonce": TEST_NONCE,
                            "type": "client_initial_anchor_observed",
                            "sameDimension": True,
                            "distance": 4.0,
                        }),
                    ]) + "\n",
                    encoding="utf-8",
                )
            verdict = verify_delayed_anchor_evidence(
                run_root,
                expected,
                TEST_NONCE,
            )
            self.assertEqual("FAIL", verdict["status"])
            self.assertIn(
                "oracle:at-least-40-zero-human-ticks",
                verdict["missingEvidence"],
            )

    def test_anchor_infrastructure_result_is_not_a_gameplay_failure(self):
        # Exercise the same exception-path writer used by anchor-smoke.
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            (run_root / "manifest.json").write_text(
                json.dumps({
                    "status": "RUNNING",
                    "functionalAiClaim": False,
                }),
                encoding="utf-8",
            )
            error = record_anchor_infrastructure_not_run(
                run_root,
                [["Xvfb", ":92"]],
                RuntimeError("Xvfb missing"),
            )
            manifest = json.loads(
                (run_root / "manifest.json").read_text(encoding="utf-8")
            )
            verdict = json.loads(
                (run_root / "anchor-verdict.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual("RuntimeError", error["type"])
            self.assertEqual("NOT_RUN", manifest["status"])
            self.assertEqual("NOT_RUN", verdict["status"])
            self.assertFalse(verdict["functionalAiClaim"])

    def test_causal_trace_requires_ordered_real_model_follow_move(self):
        events = []
        for sequence, event_type in enumerate(
            CAUSAL_AUDIT_TYPES,
            start=10,
        ):
            payload = {"requestId": "brain-4-1"}
            if event_type in {
                "model_response_received",
                "decision_schema_validated",
                "decision_revision_accepted",
                "skill_started",
            }:
                payload.update(
                    {
                        "decision": "START_SKILL",
                        "skillName": "follow_entity",
                        "protocol": "RESPONSES",
                        "httpStatus": 200,
                        "elapsedMillis": 250,
                    }
                )
            if event_type == "low_level_actions_issued":
                payload.update(
                    {
                        "skillName": "follow_entity",
                        "action": "move",
                        "outcome": "QUEUED",
                    }
                )
            events.append(
                {
                    "sequence": sequence,
                    "atUtc": "2026-08-02T09:00:01Z",
                    "type": event_type,
                    "payload": payload,
                    "goalRevision": 4,
                }
            )

        selected = causal_model_trace(
            events,
            "2026-08-02T09:00:00Z",
        )
        self.assertIsNotNone(selected)
        self.assertEqual("brain-4-1", selected["requestId"])
        self.assertEqual("follow_entity", selected["skillName"])

        without_action = events[:-1]
        self.assertIsNone(
            causal_model_trace(
                without_action,
                "2026-08-02T09:00:00Z",
            )
        )
        reordered = list(events)
        reordered[-1] = {
            **reordered[-1],
            "sequence": 11,
        }
        self.assertIsNone(
            causal_model_trace(
                reordered,
                "2026-08-02T09:00:00Z",
            )
        )

        collect_events = []
        for event in events:
            payload = dict(event["payload"])
            payload["requestId"] = "brain-5-1"
            if payload.get("skillName") == "follow_entity":
                payload["skillName"] = "collect_observed_item"
            collect_events.append(
                {
                    **event,
                    "sequence": event["sequence"] + 20,
                    "atUtc": "2026-08-02T09:00:03Z",
                    "goalRevision": 5,
                    "payload": payload,
                }
            )
        collected = causal_model_trace(
            collect_events,
            "2026-08-02T09:00:02Z",
            "collect_observed_item",
            "move",
        )
        self.assertIsNotNone(collected)
        self.assertEqual("brain-5-1", collected["requestId"])
        self.assertEqual(
            "collect_observed_item",
            collected["skillName"],
        )
        self.assertIsNone(
            causal_model_trace(
                collect_events,
                "2026-08-02T09:00:02Z",
            )
        )

    def test_task_acceptance_binds_model_trace_to_post_chat_goal(self):
        chat_at = "2026-08-02T09:00:00.500000Z"
        early_notice = {
            "sequence": 1,
            "atUtc": "2026-08-02T09:00:00Z",
            "type": "conversation_task_accepted",
            "payload": {
                "senderUuid": "00000000-0000-0000-0000-000000000001",
                "messageSha256": "a" * 64,
                "intentCode": "follow",
            },
            "goalRevision": 3,
        }
        self.assertIsNone(
            accepted_task_goal_after_chat([early_notice], chat_at)
        )

        acceptance = {
            "sequence": 2,
            "atUtc": "2026-08-02T09:00:01Z",
            "type": "conversation_task_accepted",
            "payload": {
                "senderUuid": "00000000-0000-0000-0000-000000000001",
                "messageSha256": "a" * 64,
                "intentCode": "follow",
            },
            "goalRevision": 4,
        }
        binding = accepted_task_goal_after_chat(
            [early_notice, acceptance],
            chat_at,
        )
        self.assertIsNotNone(binding)
        self.assertEqual(4, binding["goalRevision"])
        self.assertIsNone(
            accepted_task_goal_after_chat(
                [acceptance],
                chat_at,
                expected_sender_uuid=
                    "00000000-0000-0000-0000-000000000002",
                expected_message_sha256="a" * 64,
            )
        )

        def trace_events(
            request_id: str,
            goal_revision: int,
            first_sequence: int,
        ):
            events = []
            for offset, event_type in enumerate(CAUSAL_AUDIT_TYPES):
                payload = {"requestId": request_id}
                if event_type in {
                    "model_response_received",
                    "decision_schema_validated",
                    "decision_revision_accepted",
                    "skill_started",
                }:
                    payload.update(
                        {
                            "decision": "START_SKILL",
                            "skillName": "follow_entity",
                            "protocol": "RESPONSES",
                            "httpStatus": 200,
                        }
                    )
                if event_type == "low_level_actions_issued":
                    payload.update(
                        {
                            "skillName": "follow_entity",
                            "action": "move",
                        }
                    )
                events.append(
                    {
                        "sequence": first_sequence + offset,
                        "atUtc": "2026-08-02T09:00:02Z",
                        "type": event_type,
                        "payload": payload,
                        "goalRevision": goal_revision,
                    }
                )
            return events

        unrelated = trace_events("brain-7-1", 7, 10)
        bound = trace_events("brain-4-1", 4, 30)
        self.assertEqual(
            "brain-7-1",
            causal_model_trace(unrelated + bound, chat_at)["requestId"],
        )
        selected = causal_model_trace(
            unrelated + bound,
            chat_at,
            expected_goal_revision=binding["goalRevision"],
        )
        self.assertIsNotNone(selected)
        self.assertEqual("brain-4-1", selected["requestId"])
        self.assertIsNone(
            causal_model_trace(
                unrelated,
                chat_at,
                expected_goal_revision=binding["goalRevision"],
            )
        )

    def test_file_credential_is_present_without_exposing_its_path(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / "credential"
            secret.write_text("test-secret\n", encoding="utf-8")
            metadata = model_metadata(
                {
                    "MCAI_API_KEY_FILE": str(secret),
                    "MCAI_BASE_URL": "https://provider.example/v1",
                    "MCAI_MODEL": "model-name",
                },
                "run-1",
            )
        self.assertTrue(metadata["credentialPresent"])
        self.assertEqual("file", metadata["credentialSource"])
        self.assertEqual(16, len(
            metadata["credentialRunSaltedFingerprint"]
        ))
        self.assertNotIn(str(secret), str(metadata))

    def test_non_server_processes_do_not_receive_model_credentials(self):
        original = {
            "MCAI_API_KEY": "secret",
            "MCAI_API_KEY_FILE": "/secret/file",
            "CREDENTIALS_DIRECTORY": "/run/credentials",
            "MCAI_BASE_URL": "https://provider.example/v1",
            "MCAI_MODEL": "model-name",
            "PATH": "/usr/bin",
        }

        restricted = without_model_credentials(original)

        self.assertNotIn("MCAI_API_KEY", restricted)
        self.assertNotIn("MCAI_API_KEY_FILE", restricted)
        self.assertNotIn("CREDENTIALS_DIRECTORY", restricted)
        self.assertEqual(
            "https://provider.example/v1",
            restricted["MCAI_BASE_URL"],
        )
        self.assertEqual("model-name", restricted["MCAI_MODEL"])
        self.assertEqual("secret", original["MCAI_API_KEY"])

    def test_functional_preflight_failure_is_archived_as_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            preflight = {
                "kind": "functional_e2e_preflight",
                "ready": False,
                "missing": ["linux_host", "Xvfb"],
                "physicalDisplayUntouched": True,
            }
            record_functional_preflight_failure(
                run_id="run-1",
                nonce=TEST_NONCE,
                run_root=run_root,
                source={"label": "test", "dirty": True},
                environment={
                    "MCAI_API_KEY": "secret-must-not-appear",
                    "MCAI_BASE_URL": "https://provider.example/v1",
                    "MCAI_MODEL": "model-name",
                },
                forge_version="65.1.0",
                preflight=preflight,
                error="missing prerequisites",
            )

            manifest = json.loads(
                (run_root / "manifest.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual("NOT_RUN", manifest["status"])
            self.assertEqual(TEST_NONCE, manifest["nonce"])
            self.assertFalse(manifest["functionalAiClaim"])
            self.assertEqual(
                "INFRASTRUCTURE_PRECHECK",
                manifest["evidenceClass"],
            )
            self.assertTrue(
                (run_root / "functional-preflight.json").is_file()
            )
            self.assertTrue(
                (run_root / "infrastructure-error.json").is_file()
            )
            self.assertNotIn(
                "secret-must-not-appear",
                json.dumps(manifest),
            )

    def test_observer_motion_ignores_fixture_teleport_before_chat(self):
        events = [
            {
                "type": "client_world_sample",
                "atUtc": "2026-08-02T08:59:59Z",
                "ai": {"x": 100.0, "y": 70.0, "z": 100.0},
                "relevantTabNames": ["MCAI"],
            },
            {
                "type": "client_world_sample",
                "atUtc": "2026-08-02T09:00:00Z",
                "ai": {"x": -10.5, "y": 101.0, "z": 0.5},
                "relevantTabNames": ["MCAI"],
            },
            {
                "type": "client_world_sample",
                "atUtc": "2026-08-02T09:00:01Z",
                "ai": {"x": -9.0, "y": 101.0, "z": 0.5},
                "relevantTabNames": ["MCAI"],
            },
            {
                "type": "client_world_sample",
                "atUtc": "2026-08-02T09:00:02Z",
                "ai": {"x": -7.5, "y": 101.0, "z": 0.5},
                "relevantTabNames": ["MCAI"],
            },
        ]
        summary = observer_motion_summary(
            events,
            "2026-08-02T09:00:00Z",
        )
        self.assertTrue(summary["sawMotion"])
        self.assertEqual(3.0, summary["displacement"])
        self.assertEqual(1.5, summary["maximumStep"])

    def test_complete_two_request_inventory_bundle_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)

            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )

            self.assertEqual("PASS", verdict["status"])
            self.assertEqual([], verdict["missingEvidence"])
            self.assertEqual(
                "brain-1-1",
                verdict["causalModelTrace"]["requestId"],
            )
            self.assertEqual(
                "brain-2-1",
                verdict["inventoryCausalModelTrace"]["requestId"],
            )

            oracle_events = self.read_json_lines(
                run_root / "oracle-events.jsonl"
            )
            self.write_json_lines(
                run_root / "oracle-events.jsonl",
                [
                    event
                    for event in oracle_events
                    if event.get("type")
                        != "server_observed_inventory_delta"
                ],
            )
            missing_delta = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )
            self.assertEqual("FAIL", missing_delta["status"])
            self.assertIn(
                "oracle:server_observed_inventory_delta",
                missing_delta["missingEvidence"],
            )
            self.assertIn(
                "oracle:vanilla-inventory-delta-contract",
                missing_delta["missingEvidence"],
            )

    def test_direct_inventory_write_without_vanilla_pickup_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)
            oracle_events = self.read_json_lines(
                run_root / "oracle-events.jsonl"
            )
            self.write_json_lines(
                run_root / "oracle-events.jsonl",
                [
                    event
                    for event in oracle_events
                    if event.get("type")
                        != "server_vanilla_item_pickup"
                ],
            )
            delta = next(
                event
                for event in oracle_events
                if event.get("type")
                    == "server_observed_inventory_delta"
            )
            delta.pop("vanillaPickupObserved", None)
            delta.pop("vanillaPickupCount", None)
            self.write_json_lines(
                run_root / "oracle-events.jsonl",
                [
                    *[
                        event
                        for event in self.read_json_lines(
                            run_root / "oracle-events.jsonl"
                        )
                        if event.get("type")
                            != "server_observed_inventory_delta"
                    ],
                    delta,
                ],
            )
            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )
            self.assertEqual("FAIL", verdict["status"])
            self.assertIn(
                "oracle:server_vanilla_item_pickup",
                verdict["missingEvidence"],
            )
            self.assertIn(
                "oracle:vanilla-inventory-delta-contract",
                verdict["missingEvidence"],
            )

    def test_rendered_observer_must_complete_real_client_lifecycle(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)
            observer = self.read_json_lines(
                run_root / "observer-client-events.jsonl"
            )
            self.write_json_lines(
                run_root / "observer-client-events.jsonl",
                [
                    event
                    for event in observer
                    if event.get("type") != "client_logged_in"
                ],
            )

            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )

            self.assertEqual("FAIL", verdict["status"])
            self.assertIn(
                "observer:client_logged_in",
                verdict["missingEvidence"],
            )
            self.assertIn(
                "observer:ordered-real-client-lifecycle",
                verdict["missingEvidence"],
            )

    def test_rendered_observer_requires_a_real_png_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)
            (run_root / "screenshots" / "observer-rendered.png").unlink()

            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )

            self.assertEqual("FAIL", verdict["status"])
            self.assertIn(
                "observer:rendered_screenshot_file",
                verdict["missingEvidence"],
            )
            self.assertIn(
                "observer:rendered_screenshot_contract",
                verdict["missingEvidence"],
            )

    def test_mixed_run_events_cannot_pass_nonce_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)
            observer = self.read_json_lines(
                run_root / "observer-client-events.jsonl"
            )
            observer[0]["nonce"] = "OTHER-RUN"
            self.write_json_lines(
                run_root / "observer-client-events.jsonl",
                observer,
            )

            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )

            self.assertEqual("FAIL", verdict["status"])
            self.assertIn(
                "observer:run-nonce-binding",
                verdict["missingEvidence"],
            )

    def test_missing_oracle_event_nonce_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)
            oracle = self.read_json_lines(
                run_root / "oracle-events.jsonl"
            )
            oracle[0].pop("nonce")
            (run_root / "oracle-events.jsonl").write_text(
                "".join(json.dumps(event) + "\n" for event in oracle),
                encoding="utf-8",
            )

            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )

            self.assertEqual("FAIL", verdict["status"])
            self.assertIn(
                "oracle:run-nonce-binding",
                verdict["missingEvidence"],
            )

    def test_consistent_foreign_bundle_cannot_choose_its_own_nonce(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            expected_hash = self.write_functional_bundle(run_root)
            for name in (
                "actor-client-events.jsonl",
                "observer-client-events.jsonl",
                "oracle-events.jsonl",
            ):
                path = run_root / name
                events = self.read_json_lines(path)
                for event in events:
                    event["nonce"] = "FOREIGNRUN"
                self.write_json_lines(path, events)
            result_path = run_root / "oracle-result.json"
            result = json.loads(result_path.read_text(encoding="utf-8"))
            result["nonce"] = "FOREIGNRUN"
            result_path.write_text(
                json.dumps(result),
                encoding="utf-8",
            )

            verdict = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )

            self.assertEqual("FAIL", verdict["status"])
            for binding in (
                "actor:run-nonce-binding",
                "observer:run-nonce-binding",
                "oracle:run-nonce-binding",
                "oracle-result:run-nonce-binding",
            ):
                self.assertIn(binding, verdict["missingEvidence"])

    @staticmethod
    def write_functional_bundle(run_root: Path) -> str:
        product = b"frozen exact product jar"
        expected_hash = hashlib.sha256(product).hexdigest()
        actor_uuid = "73328ef7-064e-35fd-adaf-fdc3c77b8fdf"
        follow_chat = "MCAI, follow me. TESTNONCE"
        inventory_chat = (
            "MCAI, collect the visible oak log. TESTNONCE-ITEM"
        )
        screenshot_bytes = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        screenshot_path = (
            run_root / "screenshots" / "observer-rendered.png"
        )
        screenshot_path.parent.mkdir(parents=True, exist_ok=True)
        screenshot_path.write_bytes(screenshot_bytes)
        for role in ("server", "actor", "observer"):
            mod = (
                run_root
                / "instances"
                / role
                / "mods"
                / "mcai_companion-test-mc26.2.jar"
            )
            mod.parent.mkdir(parents=True, exist_ok=True)
            mod.write_bytes(product)

        OrchestratorEvidenceTest.write_json_lines(
            run_root / "actor-client-events.jsonl",
            [
                {
                    "sequence": 1,
                    "atUtc": "2026-08-02T08:59:50Z",
                    "type": "client_mod_ready",
                },
                {
                    "sequence": 2,
                    "atUtc": "2026-08-02T08:59:51Z",
                    "type": "client_connect_started",
                },
                {
                    "sequence": 3,
                    "atUtc": "2026-08-02T08:59:52Z",
                    "type": "client_logged_in",
                },
                {
                    "sequence": 4,
                    "atUtc": "2026-08-02T08:59:59Z",
                    "type": "scenario_ready_received",
                },
                {
                    "sequence": 5,
                    "atUtc": "2026-08-02T09:00:00Z",
                    "type": "actor_chat_sent",
                },
                {
                    "sequence": 6,
                    "atUtc": "2026-08-02T09:00:01Z",
                    "type": "ai_chat_followup_received_by_actor",
                    "actorRole": True,
                    "afterInventoryChat": False,
                },
                {
                    "sequence": 7,
                    "atUtc": "2026-08-02T09:00:03Z",
                    "type": "actor_follow_arrival_observed",
                },
                {
                    "sequence": 8,
                    "atUtc": "2026-08-02T09:00:04Z",
                    "type": "actor_inventory_chat_sent",
                },
                {
                    "atUtc": "2026-08-02T09:00:06Z",
                    "sequence": 9,
                    "type": "ai_chat_followup_received_by_actor",
                    "actorRole": True,
                    "afterInventoryChat": True,
                },
            ],
        )
        OrchestratorEvidenceTest.write_json_lines(
            run_root / "observer-client-events.jsonl",
            [
                {
                    "sequence": 1,
                    "atUtc": "2026-08-02T08:59:50Z",
                    "type": "client_mod_ready",
                },
                {
                    "sequence": 2,
                    "atUtc": "2026-08-02T08:59:51Z",
                    "type": "client_connect_started",
                },
                {
                    "sequence": 3,
                    "atUtc": "2026-08-02T08:59:52Z",
                    "type": "client_logged_in",
                },
                {
                    "sequence": 4,
                    "atUtc": "2026-08-02T08:59:59Z",
                    "type": "scenario_ready_received",
                },
                {
                    "sequence": 5,
                    "atUtc": "2026-08-02T09:00:01Z",
                    "type": "client_world_sample",
                    "ai": {"x": 0.0, "y": 101.0, "z": 0.0},
                    "relevantTabNames": ["MCAI"],
                },
                {
                    "sequence": 6,
                    "atUtc": "2026-08-02T09:00:02Z",
                    "type": "client_world_sample",
                    "ai": {"x": 1.5, "y": 101.0, "z": 0.0},
                    "relevantTabNames": ["MCAI"],
                },
                {
                    "sequence": 7,
                    "atUtc": "2026-08-02T09:00:03Z",
                    "type": "client_world_sample",
                    "ai": {"x": 3.0, "y": 101.0, "z": 0.0},
                    "relevantTabNames": ["MCAI"],
                },
                {
                    "sequence": 8,
                    "atUtc": "2026-08-02T09:00:04Z",
                    "type": "observer_screenshot_saved",
                    "file": str(screenshot_path),
                    "bytes": len(screenshot_bytes),
                    "width": 1,
                    "height": 1,
                },
            ],
        )
        oracle_events = [
            {
                "sequence": 1,
                "atUtc": "2026-08-02T08:59:58Z",
                "type": "dedicated_server_started",
            },
            {
                "sequence": 2,
                "atUtc": "2026-08-02T08:59:59Z",
                "type": "fixture_setup_complete",
            },
            {
                "sequence": 3,
                "atUtc": "2026-08-02T08:59:59Z",
                "type": "fixture_item_spawned",
                "itemId": "minecraft:oak_log",
                "count": 3,
            },
            {
                "sequence": 4,
                "atUtc": "2026-08-02T09:00:01Z",
                "type": "server_chat_received",
                "sender": E2E_ACTOR_NAME,
                "senderUuid": actor_uuid,
                "message": follow_chat,
            },
            {
                "sequence": 5,
                "atUtc": "2026-08-02T09:00:03Z",
                "type": "server_observed_world_delta",
                "maxDisplacement": 3.0,
                "finalActorDistance": 3.0,
                "maxTickStep": 0.3,
            },
            {
                "sequence": 6,
                "atUtc": "2026-08-02T09:00:03Z",
                "type": "movement_objective_oracle_passed",
            },
            {
                "sequence": 7,
                "atUtc": "2026-08-02T09:00:04Z",
                "type": "inventory_chat_received",
                "sender": E2E_ACTOR_NAME,
                "senderUuid": actor_uuid,
                "message": inventory_chat,
                "initialOakLogCount": 0,
                "fixtureDropAlive": True,
            },
            {
                "sequence": 8,
                "atUtc": "2026-08-02T09:00:05Z",
                "type": "server_vanilla_item_pickup",
                "itemId": "minecraft:oak_log",
                "pickedCount": 3,
                "cumulativePickedCount": 3,
                "path": "forge_player_item_pickup_event",
            },
            {
                "sequence": 9,
                "atUtc": "2026-08-02T09:00:05Z",
                "type": "server_inventory_sample",
                "initialOakLogCount": 0,
                "currentOakLogCount": 3,
                "fixtureDropRemoved": True,
            },
            {
                "sequence": 10,
                "atUtc": "2026-08-02T09:00:05Z",
                "type": "server_observed_inventory_delta",
                "initialOakLogCount": 0,
                "finalOakLogCount": 3,
                "fixtureDropRemoved": True,
                "vanillaPickupObserved": True,
                "vanillaPickupCount": 3,
                "maxInventoryDisplacement": 4.0,
                "maxInventoryTickStep": 0.25,
            },
            {
                "sequence": 11,
                "atUtc": "2026-08-02T09:00:05Z",
                "type": "inventory_transaction_oracle_passed",
            },
            {
                "sequence": 12,
                "atUtc": "2026-08-02T09:00:05Z",
                "type": "objective_oracle_passed",
            },
        ]
        OrchestratorEvidenceTest.write_json_lines(
            run_root / "oracle-events.jsonl",
            oracle_events,
        )
        (run_root / "oracle-result.json").write_text(
            json.dumps(
                {
                    "nonce": "TESTNONCE",
                    "status": "PASS",
                    "reason": "movement_and_inventory_passed",
                    "platformBuilt": True,
                    "setupComplete": True,
                    "serverChatReceived": True,
                    "movementPassed": True,
                    "inventoryChatReceived": True,
                    "inventoryPassed": True,
                    "oraclePassed": True,
                    "evidenceDropped": 0,
                    "initialOakLogCount": 0,
                    "finalOakLogCount": 3,
                }
            ),
            encoding="utf-8",
        )
        database = (
            run_root
            / "instances"
            / "server"
            / "world-test"
            / "data"
            / "mcai_companion"
            / "memory.db"
        )
        database.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(database)
        try:
            connection.execute(
                """
                CREATE TABLE event_log (
                    sequence INTEGER PRIMARY KEY,
                    occurred_at TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    source TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    world_revision INTEGER NOT NULL,
                    goal_revision INTEGER NOT NULL
                )
                """
            )
            sequence = 0
            for (
                request_id,
                goal_revision,
                at_utc,
                skill_name,
                message,
            ) in (
                (
                    "brain-1-1",
                    1,
                    "2026-08-02T09:00:01Z",
                    "follow_entity",
                    follow_chat,
                ),
                (
                    "brain-2-1",
                    2,
                    "2026-08-02T09:00:04Z",
                    "collect_observed_item",
                    inventory_chat,
                ),
            ):
                sequence += 1
                connection.execute(
                    """
                    INSERT INTO event_log VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        sequence,
                        at_utc,
                        "conversation_task_accepted",
                        "conversation",
                        json.dumps(
                            {
                                "senderUuid": actor_uuid,
                                "messageSha256": hashlib.sha256(
                                    message.encode("utf-8")
                                ).hexdigest(),
                                "intentCode": skill_name,
                            }
                        ),
                        sequence,
                        goal_revision,
                    ),
                )
                for event_type in CAUSAL_AUDIT_TYPES:
                    sequence += 1
                    payload = {"requestId": request_id}
                    if event_type in {
                        "model_response_received",
                        "decision_schema_validated",
                        "decision_revision_accepted",
                        "skill_started",
                    }:
                        payload.update(
                            {
                                "decision": "START_SKILL",
                                "skillName": skill_name,
                                "protocol": "RESPONSES",
                                "httpStatus": 200,
                            }
                        )
                    if event_type == "low_level_actions_issued":
                        payload.update(
                            {
                                "skillName": skill_name,
                                "action": "move",
                                "outcome": "QUEUED",
                            }
                        )
                    connection.execute(
                        """
                        INSERT INTO event_log VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            sequence,
                            at_utc,
                            event_type,
                            "test",
                            json.dumps(payload),
                            sequence,
                            goal_revision,
                        ),
                    )
            sequence += 1
            connection.execute(
                """
                INSERT INTO event_log VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    sequence,
                    "2026-08-02T09:00:06Z",
                    "connection_transport_audit",
                    "embodiment",
                    json.dumps(
                        {
                            "discardedPackets": 3,
                            "keepAliveAcknowledgements": 2,
                            "teleportAcknowledgements": 1,
                            "chunkBatchAcknowledgements": 4,
                            "endCreditsRespawnRequests": 0,
                            "largestDrain": 8,
                            "outboundQueueHighWatermark": 8,
                            "unreleasedOutboundPackets": 0,
                            "disconnectHandled": True,
                        }
                    ),
                    sequence,
                    2,
                ),
            )
            for phase, at_utc in (
                ("started", "2026-08-02T08:59:58Z"),
                ("stopping", "2026-08-02T09:00:07Z"),
            ):
                sequence += 1
                connection.execute(
                    """
                    INSERT INTO event_log VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        sequence,
                        at_utc,
                        "runtime_lifecycle_audit",
                        "runtime",
                        json.dumps(
                            {
                                "phase": phase,
                                "companionUuid":
                                    "00000000-0000-0000-0000-000000000001",
                                "goalRevision": 2,
                                "goalStatus": "RUNNING",
                                "goalSource": "PLAYER_CHAT",
                                "bodyEverSpawned": True,
                                "hardcoreDead": False,
                                "evaluationLocked": False,
                                "evaluationContaminated": False,
                                "memorySchemaVersion": 1,
                                "serverTick": 200,
                            }
                        ),
                        sequence,
                        2,
                    ),
                )
            connection.commit()
        finally:
            connection.close()
        return expected_hash

    @staticmethod
    def write_json_lines(path: Path, values: list[dict]) -> None:
        path.write_text(
            "".join(
                json.dumps(
                    {"nonce": "TESTNONCE", **value}
                    if "nonce" not in value
                    else value
                )
                + "\n"
                for value in values
            ),
            encoding="utf-8",
        )

    @staticmethod
    def read_json_lines(path: Path) -> list[dict]:
        return [
            json.loads(line)
            for line in path.read_text(encoding="utf-8").splitlines()
        ]


if __name__ == "__main__":
    unittest.main()
