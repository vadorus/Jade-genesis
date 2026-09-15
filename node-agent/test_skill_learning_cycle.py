from __future__ import annotations

import unittest

from skill_learning_cycle import (
    GOAL_ID,
    TASK_FAMILY,
    _interrupted_visible_goal_status,
)


class SkillLearningCycleReentryTest(unittest.TestCase):
    def test_interrupted_visible_goal_fails_closed_without_teacher_retry(self) -> None:
        goal = {
            "goal_id": GOAL_ID,
            "state": "VISIBLE_TESTING",
            "candidate_count": 2,
            "visible_attempts": [
                {"candidate_number": 1},
                {"candidate_number": 2},
            ],
        }
        result = _interrupted_visible_goal_status(
            goal,
            ready_attested_dataset_count=3,
        )
        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual("GOAL_INTERRUPTED_MANUAL_REVIEW", result["status"])
        self.assertEqual(TASK_FAMILY, result["task_family"])
        self.assertEqual(2, result["candidate_count"])
        self.assertEqual(2, result["persisted_visible_attempt_count"])
        self.assertFalse(result["automatic_retry_allowed"])
        self.assertFalse(result["teacher_called"])
        self.assertFalse(result["sealed_exam_run"])

    def test_open_goal_is_not_treated_as_interrupted(self) -> None:
        goal = {
            "goal_id": GOAL_ID,
            "state": "OPEN",
            "candidate_count": 0,
            "visible_attempts": [],
        }
        self.assertIsNone(
            _interrupted_visible_goal_status(
                goal,
                ready_attested_dataset_count=3,
            )
        )


if __name__ == "__main__":
    unittest.main()
