"""Deterministic typed search over JADE_PROCEDURE_DSL_V1 for Jade Genesis 0.1.21 V3.

The search assembles small procedure bodies from visible TRAIN/VALIDATION
examples only. It never reads SEALED_TEST data: callers pass the visible cases
already present in the teacher request. Optional hints (operation names and
string literals proposed by an LLM teacher) are ingredients, not answers: they
only reorder operations and add literal leaves.

Search is breadth-first by composition depth, pruned by observational
equivalence (two expressions producing identical outputs on every visible input
are kept once), and bounded by a fixed evaluation budget. The smallest body that
reproduces every visible output is returned; the verifier-owned hidden exam
still decides whether it generalizes.
"""

from __future__ import annotations

from typing import Any, Iterable

from procedure_runtime import ExecutionBudget, _canonical_json, _eval_node

MAX_EVALUATIONS = 20_000
MAX_DEPTH = 3
MAX_HINT_LITERALS = 8
MAX_HINT_LITERAL_CHARS = 64
_STRING_UNARY_OPS = ("trim", "lower", "upper")

_ERROR = object()


def _literal(value: Any) -> dict[str, Any]:
    return {"op": "literal", "args": [value]}


def _run(body: dict[str, Any], input_value: Any) -> Any:
    try:
        return _eval_node(body, input_value, ExecutionBudget())
    except Exception:
        return _ERROR


def hints_from_body(body: Any) -> tuple[set[str], list[str]]:
    """Collect operation names and short string literals from a proposed AST."""

    ops: set[str] = set()
    literals: list[str] = []
    stack = [body]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            op = node.get("op")
            if isinstance(op, str):
                ops.add(op)
            args = node.get("args")
            if op == "literal" and isinstance(args, list) and args:
                value = args[0]
                if isinstance(value, str) and len(value) <= MAX_HINT_LITERAL_CHARS:
                    literals.append(value)
            elif isinstance(args, list):
                stack.extend(args)
        elif isinstance(node, list):
            stack.extend(node)
        elif isinstance(node, str) and len(node) <= MAX_HINT_LITERAL_CHARS:
            # Small models often write a bare key string where a literal node
            # is required; the string is still a useful ingredient.
            literals.append(node)
    return ops, list(dict.fromkeys(literals))[:MAX_HINT_LITERALS]


class _Search:
    def __init__(self, inputs: list[Any], hint_ops: Iterable[str], hint_literals: list[str]):
        self.inputs = inputs
        self.evaluations = 0
        hinted = [op for op in _STRING_UNARY_OPS if op in set(hint_ops)]
        self.unary_ops = hinted + [op for op in _STRING_UNARY_OPS if op not in hinted]
        self.hint_literals = hint_literals

    def _signature(self, body: dict[str, Any]) -> tuple[str, ...] | None:
        if self.evaluations >= MAX_EVALUATIONS:
            return None
        self.evaluations += 1
        outputs = []
        for value in self.inputs:
            result = _run(body, value)
            if result is _ERROR:
                return None
            outputs.append(_canonical_json(result))
        return tuple(outputs)

    def _leaves(self) -> list[dict[str, Any]]:
        leaves: list[dict[str, Any]] = [{"op": "input", "args": []}]
        if all(isinstance(value, dict) for value in self.inputs):
            common = set.intersection(*(set(value) for value in self.inputs))
            for key in sorted(common):
                leaves.append({"op": "get", "args": [_literal(key)]})
        leaves.extend(_literal(value) for value in self.hint_literals)
        return leaves

    def find(self, targets: list[Any]) -> dict[str, Any] | None:
        goal = tuple(_canonical_json(value) for value in targets)
        pool: dict[tuple[str, ...], dict[str, Any]] = {}
        frontier: list[dict[str, Any]] = []
        for leaf in self._leaves():
            signature = self._signature(leaf)
            if signature is None:
                continue
            if signature == goal:
                return leaf
            if signature not in pool:
                pool[signature] = leaf
                frontier.append(leaf)

        for _ in range(MAX_DEPTH):
            strings = [
                (signature, body)
                for signature, body in pool.items()
                if all(output.startswith('"') for output in signature)
            ]
            candidates: list[dict[str, Any]] = []
            frontier_ids = {id(body) for body in frontier}
            for op in self.unary_ops:
                candidates.extend(
                    {"op": op, "args": [body]}
                    for _, body in strings
                    if id(body) in frontier_ids
                )
            for _, left in strings:
                for _, right in strings:
                    if id(left) in frontier_ids or id(right) in frontier_ids:
                        candidates.append({"op": "concat", "args": [left, right]})

            frontier = []
            for body in candidates:
                signature = self._signature(body)
                if signature is None:
                    if self.evaluations >= MAX_EVALUATIONS:
                        return None
                    continue
                if signature == goal:
                    return body
                if signature not in pool:
                    pool[signature] = body
                    frontier.append(body)
            if not frontier:
                return None
        return None


def search_body(
    visible_cases: list[tuple[Any, Any]],
    *,
    hint_ops: Iterable[str] = (),
    hint_literals: list[str] | None = None,
) -> dict[str, Any] | None:
    """Return the smallest found body reproducing every visible output, or None."""

    if not visible_cases:
        return None
    inputs = [case[0] for case in visible_cases]
    expected = [case[1] for case in visible_cases]
    search = _Search(inputs, hint_ops, list(hint_literals or [])[:MAX_HINT_LITERALS])

    if all(isinstance(value, dict) for value in expected):
        keys = sorted(expected[0])
        if not keys or any(sorted(value) != keys for value in expected):
            return None
        args: list[dict[str, Any]] = []
        for key in keys:
            body = search.find([value[key] for value in expected])
            if body is None:
                return None
            args.extend([_literal(key), body])
        return {"op": "object", "args": args}
    return search.find(expected)
