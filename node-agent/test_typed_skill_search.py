from __future__ import annotations

import unittest

from procedure_runtime import ExecutionBudget, _eval_node
from typed_skill_search import hints_from_body, search_body


def run(body: dict, value: object) -> object:
    return _eval_node(body, value, ExecutionBudget())


class TypedSkillSearchTest(unittest.TestCase):
    NORMALIZE = [
        ({"text": "  Rouge-Vif"}, {"text": "rouge-vif"}),
        ({"text": "MAISON 3 "}, {"text": "maison 3"}),
        ({"text": " ÉtÉ_Chaud"}, {"text": "été_chaud"}),
    ]

    def test_assembles_normalize_label_from_visible_cases(self) -> None:
        body = search_body(self.NORMALIZE)
        self.assertIsNotNone(body)
        for input_value, expected in self.NORMALIZE:
            self.assertEqual(expected, run(body, input_value))
        # Generalizes to an unseen input of the same family.
        self.assertEqual({"text": "éclair-ça_fête42"}, run(body, {"text": " ÉcLaiR-ÇA_fÊTe42"}))

    def test_search_is_deterministic(self) -> None:
        self.assertEqual(search_body(self.NORMALIZE), search_body(self.NORMALIZE))

    def test_hint_literal_enables_concatenation(self) -> None:
        cases = [({"name": "Jade"}, {"text": "Jade!"}), ({"name": "Pixel"}, {"text": "Pixel!"})]
        self.assertIsNone(search_body(cases))
        body = search_body(cases, hint_literals=["!"])
        self.assertIsNotNone(body)
        self.assertEqual({"text": "Nœud!"}, run(body, {"name": "Nœud"}))

    def test_returns_none_when_outputs_are_not_derivable(self) -> None:
        cases = [({"text": "a"}, {"text": "zz"}), ({"text": "b"}, {"text": "qq"})]
        self.assertIsNone(search_body(cases))

    def test_rejects_inconsistent_output_shapes(self) -> None:
        cases = [({"text": "a"}, {"text": "a"}), ({"text": "b"}, {"other": "b"})]
        self.assertIsNone(search_body(cases))
        self.assertIsNone(search_body([]))

    def test_hints_collect_ops_and_bare_key_strings(self) -> None:
        proposal = {
            "op": "concat",
            "args": [
                {"op": "trim", "args": [{"op": "get", "args": ["text"]}]},
                {"op": "lower", "args": [{"op": "literal", "args": ["-"]}]},
            ],
        }
        ops, literals = hints_from_body(proposal)
        self.assertEqual({"concat", "trim", "get", "lower", "literal"}, ops)
        self.assertEqual({"text", "-"}, set(literals))


if __name__ == "__main__":
    unittest.main()
