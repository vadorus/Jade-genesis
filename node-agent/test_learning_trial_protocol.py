from __future__ import annotations

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


class LearningTrialProtocolTest(unittest.TestCase):
    def test_real_case_reserve_attestation_order_and_verified_learning(self) -> None:
        node_dir = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(prefix="jade-real-trial-") as config_dir:
            script = textwrap.dedent(
                r'''
                import os
                import sys
                os.environ["JADE_GENESIS_CONFIG_DIR"] = sys.argv[2]
                sys.path.insert(0, sys.argv[1])

                from first_learning_family import (
                    INPUT_CONTRACT,
                    OUTPUT_CONTRACT,
                    PARTITION_PLAN,
                    TASK_FAMILY,
                    family_status,
                    record_real_case,
                )
                from learning_environment import read_attribution_events
                from learning_stores import open_archive_ledger
                from learning_trial_protocol import (
                    POST_DEMO_CONFIRMATORY_FRESH_DATASETS,
                    POST_DEMO_TOTAL_HIDDEN_CASE_TARGET,
                    open_attested_gap,
                    publish_seal_attestation,
                    ready_dataset_pool,
                    run_attested_learning,
                )

                identity = "jade-real-trial"
                values = [
                    " JADE ", "  Pixel", "VPS  ", " Claude ", " GPT ",
                    "  Alpha ", "BETA  ", " Gamma ", "DELTA", " Epsilon ",
                    "  Node-A ", "NODE-B  ", " Skill ", "Workshop", " Archive ",
                ]
                sealed = []
                for index, text in enumerate(values):
                    result = record_real_case(
                        identity,
                        {"text": text},
                        now_ms=1_000 + index,
                    )
                    assert result["recorded"] is True
                    assert result["real_production_traffic"] is True
                    if result["dataset_sealed"]:
                        assert result["case_count"] == 5
                        assert result["partition_plan"] == list(PARTITION_PLAN)
                        sealed.append(result)

                status = family_status(identity)
                assert status["dataset_count"] == 3, status
                assert status["sealed_dataset_count"] == 3, status
                assert status["fixed_partition_plan"] == [
                    "TRAIN", "TRAIN", "VALIDATION", "SEALED_TEST", "SEALED_TEST"
                ]
                assert len(sealed) == 3

                ledger = open_archive_ledger()
                for item in sealed:
                    learning = ledger.learning_view(identity, item["dataset_id"])
                    visible = [case["partition"] for case in learning["cases"]]
                    assert visible.count("TRAIN") == 2
                    assert visible.count("VALIDATION") == 1
                    assert "SEALED_TEST" not in visible
                    assert learning["sealed_test_count"] == 2

                for index, item in enumerate(sealed[:2]):
                    attested = publish_seal_attestation(
                        identity,
                        item["dataset_id"],
                        item["sealed_set_sha256"],
                        case_count=item["case_count"],
                        partition_plan=item["partition_plan"],
                        external_publication_ref=f"external:trial-note-{index+1}",
                        now_ms=2_000 + index,
                    )
                    assert attested["case_count"] == 5
                    assert attested["partition_plan"] == list(PARTITION_PLAN)
                assert len(ready_dataset_pool(identity, TASK_FAMILY)) == 2
                try:
                    open_attested_gap(
                        identity,
                        "goal-real-v1",
                        TASK_FAMILY,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        reason="real repeated normalization gap",
                        now_ms=2_100,
                    )
                except PermissionError as exc:
                    assert str(exc) == "learning_trial_requires_dataset_reserve"
                else:
                    raise AssertionError("teacher gate opened without three reserve datasets")

                third = sealed[2]
                publish_seal_attestation(
                    identity,
                    third["dataset_id"],
                    third["sealed_set_sha256"],
                    case_count=third["case_count"],
                    partition_plan=third["partition_plan"],
                    external_publication_ref="external:trial-note-3",
                    now_ms=2_200,
                )
                assert len(ready_dataset_pool(identity, TASK_FAMILY)) == 3

                opened = open_attested_gap(
                    identity,
                    "goal-real-v1",
                    TASK_FAMILY,
                    INPUT_CONTRACT,
                    OUTPUT_CONTRACT,
                    reason="real repeated normalization gap",
                    now_ms=2_300,
                )
                assert opened["state"] == "OPEN"

                calls = []
                def teacher(request):
                    calls.append(request)
                    return {
                        "skill_id": "learned-normalize-label",
                        "description": "Trim and lowercase one label.",
                        "domain": "verifiable_procedure",
                        "body": {
                            "op": "object",
                            "args": [
                                {"op": "literal", "args": ["text"]},
                                {
                                    "op": "lower",
                                    "args": [
                                        {
                                            "op": "trim",
                                            "args": [
                                                {
                                                    "op": "get",
                                                    "args": [
                                                        {"op": "literal", "args": ["text"]}
                                                    ],
                                                }
                                            ],
                                        }
                                    ],
                                },
                            ],
                        },
                    }

                result = run_attested_learning(
                    identity,
                    "goal-real-v1",
                    teacher,
                    teacher_id="test-teacher",
                    source_model="fake-code-model",
                    now_ms=2_400,
                )
                assert result["state"] == "RETAINED", result
                assert result["skill_retained"] is True
                assert result["seal_attestation_verified"] is True
                assert result["structured_partition_attestation_verified"] is True
                assert result["trial_terminal"] is False
                assert result["initial_acquisition_is_mastery_claim"] is False
                assert result["post_demo_confirmatory_fresh_datasets_required"] == POST_DEMO_CONFIRMATORY_FRESH_DATASETS
                assert result["post_demo_total_hidden_case_target"] == POST_DEMO_TOTAL_HIDDEN_CASE_TARGET
                assert len(calls) == 1
                assert calls[0]["sealed_test_inputs_exposed"] is False
                assert calls[0]["sealed_test_answers_exposed"] is False

                events = read_attribution_events()
                selected_dataset = opened["dataset_id"]
                attested = next(
                    event for event in events
                    if event["event_kind"] == "sealed_dataset_attested"
                    and event["payload"]["dataset_id"] == selected_dataset
                )
                teacher_started = next(
                    event for event in events
                    if event["event_kind"] == "skill_teacher_started"
                    and event["payload"]["dataset_id"] == selected_dataset
                )
                assert attested["payload"]["case_count"] == 5
                assert attested["payload"]["partition_plan"] == list(PARTITION_PLAN)
                assert attested["sequence"] < teacher_started["sequence"]
                assert attested["occurred_at"] <= teacher_started["occurred_at"]
                '''
            )
            completed = subprocess.run(
                [sys.executable, "-c", script, str(node_dir), config_dir],
                capture_output=True,
                text=True,
                timeout=30,
            )
            if completed.returncode != 0:
                self.fail(
                    "isolated real trial protocol failed:\n"
                    + completed.stdout
                    + "\n"
                    + completed.stderr
                )

    def test_one_sealed_failure_terminates_trial_and_forbids_fresh_dataset_retry(self) -> None:
        node_dir = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(prefix="jade-terminal-trial-") as config_dir:
            script = textwrap.dedent(
                r'''
                import os
                import sys
                os.environ["JADE_GENESIS_CONFIG_DIR"] = sys.argv[2]
                sys.path.insert(0, sys.argv[1])

                from first_learning_family import (
                    INPUT_CONTRACT,
                    OUTPUT_CONTRACT,
                    TASK_FAMILY,
                    record_real_case,
                )
                from learning_environment import read_attribution_events
                from learning_trial_protocol import (
                    open_attested_gap,
                    publish_seal_attestation,
                    ready_dataset_pool,
                    run_attested_learning,
                )

                identity = "jade-terminal-trial"
                # Dataset 1 visible cases are already lowercase, so a trim-only
                # candidate passes TRAIN/VALIDATION but fails the uppercase hidden cases.
                values = [
                    " alpha ", " beta ", " gamma ", " DELTA ", " EPSILON ",
                    " one ", " two ", " three ", " FOUR ", " FIVE ",
                    " red ", " blue ", " green ", " WHITE ", " BLACK ",
                ]
                sealed = []
                for index, text in enumerate(values):
                    result = record_real_case(
                        identity,
                        {"text": text},
                        now_ms=10_000 + index,
                    )
                    if result["dataset_sealed"]:
                        sealed.append(result)
                assert len(sealed) == 3

                for index, item in enumerate(sealed):
                    publish_seal_attestation(
                        identity,
                        item["dataset_id"],
                        item["sealed_set_sha256"],
                        case_count=item["case_count"],
                        partition_plan=item["partition_plan"],
                        external_publication_ref=f"external:terminal-note-{index+1}",
                        now_ms=11_000 + index,
                    )
                assert len(ready_dataset_pool(identity, TASK_FAMILY)) == 3

                opened = open_attested_gap(
                    identity,
                    "goal-terminal-v1",
                    TASK_FAMILY,
                    INPUT_CONTRACT,
                    OUTPUT_CONTRACT,
                    reason="terminal failure protocol test",
                    now_ms=12_000,
                )

                calls = []
                def bad_teacher(request):
                    calls.append(request)
                    return {
                        "skill_id": "bad-trim-only",
                        "description": "Incorrectly trims without lowercasing.",
                        "domain": "verifiable_procedure",
                        "body": {
                            "op": "object",
                            "args": [
                                {"op": "literal", "args": ["text"]},
                                {
                                    "op": "trim",
                                    "args": [
                                        {
                                            "op": "get",
                                            "args": [
                                                {"op": "literal", "args": ["text"]}
                                            ],
                                        }
                                    ],
                                },
                            ],
                        },
                    }

                failed = run_attested_learning(
                    identity,
                    "goal-terminal-v1",
                    bad_teacher,
                    teacher_id="bad-test-teacher",
                    source_model="fake-code-model",
                    now_ms=12_100,
                )
                assert failed["state"] == "SEALED_FAILED", failed
                assert failed["trial_terminal"] is True
                assert failed["max_sealed_failures_per_trial"] == 1
                assert failed["failed_candidate_retry_on_fresh_dataset_allowed"] is False
                assert failed["sealed_dataset_reserve_after_run"] == 2
                assert len(calls) == 1

                # The two fresh reserve datasets still exist, but the protocol
                # must refuse to convert them into retries after seeing hidden feedback.
                assert len(ready_dataset_pool(identity, TASK_FAMILY)) == 2
                try:
                    open_attested_gap(
                        identity,
                        "goal-terminal-v2",
                        TASK_FAMILY,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        reason="must not retry after hidden failure",
                        min_ready_datasets=2,
                        now_ms=12_200,
                    )
                except PermissionError as exc:
                    assert str(exc) == "learning_trial_family_terminal_after_sealed_failure"
                else:
                    raise AssertionError("fresh sealed dataset was incorrectly used as retry budget")

                events = read_attribution_events()
                terminal = [
                    event for event in events
                    if event["event_kind"] == "learning_trial_terminal_failure"
                ]
                assert len(terminal) == 1
                assert terminal[0]["payload"]["dataset_id"] == opened["dataset_id"]
                assert terminal[0]["payload"]["sealed_failures_used"] == 1
                assert terminal[0]["payload"]["failed_candidate_retry_on_fresh_dataset_allowed"] is False
                assert terminal[0]["payload"]["remaining_reserve_is_not_retry_budget"] is True
                assert terminal[0]["payload"]["family_status_for_protocol"] == "NOT_LEARNED_TERMINAL"
                '''
            )
            completed = subprocess.run(
                [sys.executable, "-c", script, str(node_dir), config_dir],
                capture_output=True,
                text=True,
                timeout=30,
            )
            if completed.returncode != 0:
                self.fail(
                    "isolated terminal failure protocol failed:\n"
                    + completed.stdout
                    + "\n"
                    + completed.stderr
                )


if __name__ == "__main__":
    unittest.main()
