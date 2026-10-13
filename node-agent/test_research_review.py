import unittest
from research_review import experiment_digest, execution_authorized, render_review


class ResearchReviewTest(unittest.TestCase):
    def setUp(self):
        self.experiment = {"experiment_id": "exp-1", "automatic_execution": False,
                           "maximum_samples_per_side": 12}
        self.approval = {"approved_by": "user", "action": "execute_experiment",
                         "environment": "bureau-sandbox", "experiment_id": "exp-1",
                         "experiment_sha256": experiment_digest(self.experiment),
                         "issued_at_ms": 100, "expires_at_ms": 200}

    def allowed(self, experiment=None, approval=None, now=150, environment="bureau-sandbox"):
        return execution_authorized(self.experiment if experiment is None else experiment,
                                    self.approval if approval is None else approval,
                                    now_ms=now, environment=environment)

    def test_exact_scoped_approval(self):
        self.assertTrue(self.allowed())
        self.assertFalse(self.allowed(approval={}))

    def test_changed_proposal_requires_new_approval(self):
        self.assertFalse(self.allowed(experiment={**self.experiment, "maximum_samples_per_side": 13}))

    def test_malformed_inputs_fail_closed(self):
        for experiment in [[], "exp-1", {}, {"experiment_id": 12}, {"experiment_id": " "}]:
            with self.subTest(experiment=experiment):
                self.assertFalse(self.allowed(experiment=experiment))
        for approval in [[], "approved", False]:
            self.assertFalse(self.allowed(approval=approval))
        for now in [True, 150.0, "150", -1]:
            self.assertFalse(self.allowed(now=now))

    def test_automatic_execution_rejected_even_with_matching_digest(self):
        for automatic in [True, 0, None]:
            experiment = {**self.experiment, "automatic_execution": automatic}
            approval = {**self.approval, "experiment_sha256": experiment_digest(experiment)}
            self.assertFalse(self.allowed(experiment=experiment, approval=approval))

    def test_expired_future_and_unbounded_approval(self):
        self.assertFalse(self.allowed(now=200))
        self.assertFalse(self.allowed(now=99))
        self.assertFalse(self.allowed(approval={**self.approval, "expires_at_ms": 90_000_000}))
        self.assertFalse(self.allowed(approval={**self.approval, "issued_at_ms": True}))

    def test_other_action_actor_environment_and_id_rejected(self):
        for key, value in [("action", "promote"), ("approved_by", "model"),
                           ("environment", "vps"), ("experiment_id", "exp-2")]:
            with self.subTest(key=key):
                self.assertFalse(self.allowed(approval={**self.approval, key: value}))
        self.assertFalse(self.allowed(environment="home"))

    def test_review_does_not_disclose_unselected_personal_fields(self):
        report = {"memories": ["PRIVATE_SENTINEL"], "research_evidence": [
            {"title": "Documentation", "url": "https://example.test", "snippet": "Reported text"}],
            "experiments": [self.experiment]}
        rendered = render_review(report)
        self.assertNotIn("PRIVATE_SENTINEL", rendered)
        self.assertIn("contenu non vérifié", rendered)
        self.assertIn(experiment_digest(self.experiment), rendered)
        self.assertEqual(12, self.experiment["maximum_samples_per_side"])


if __name__ == "__main__":
    unittest.main()
