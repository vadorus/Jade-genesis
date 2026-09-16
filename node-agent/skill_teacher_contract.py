"""Machine-readable teacher contract for JADE_PROCEDURE_DSL_V1.

This module exposes only public DSL syntax/semantics. It never contains or reads
SEALED_TEST inputs, expected outputs, seal nonces, verifier verdicts or runtime
activation state.
"""

from __future__ import annotations

import json
from typing import Any

REQUEST_KIND = "JADE_SKILL_TEACHER_REQUEST_V2"
DSL_CONTRACT_VERSION = 1

_OPERATION_CONTRACTS: dict[str, dict[str, Any]] = {
    "input": {"arity": [0], "meaning": "Return the complete root input value."},
    "literal": {
        "arity": [1],
        "meaning": "Return one bounded raw JSON literal. Its single argument is raw JSON, not an AST node.",
    },
    "get": {
        "arity": [1, 2],
        "meaning": "With one AST argument, read that key/index from root input. With two, read key/index from evaluated container.",
    },
    "object": {
        "arity_rule": "even number of AST arguments interpreted as key,value pairs",
        "meaning": "Build a JSON object. Evaluated keys must be non-empty strings.",
    },
    "array": {"arity_rule": "zero or more AST arguments", "meaning": "Build a JSON array from evaluated arguments."},
    "concat": {"arity_rule": "one or more AST arguments", "meaning": "Concatenate evaluated string arguments."},
    "lower": {"arity": [1], "meaning": "Lower-case one evaluated string using Python str.lower semantics."},
    "upper": {"arity": [1], "meaning": "Upper-case one evaluated string using Python str.upper semantics."},
    "trim": {"arity": [1], "meaning": "Trim leading and trailing whitespace from one evaluated string using Python str.strip semantics."},
    "replace": {"arity": [3], "meaning": "replace(text, old, new) on evaluated strings."},
    "split": {"arity": [2], "meaning": "split(text, separator); separator must be non-empty."},
    "join": {"arity": [2], "meaning": "join(separator, string_array)."},
    "length": {"arity": [1], "meaning": "Length of one evaluated string, array or object."},
    "add": {"arity": [2], "meaning": "Numeric addition."},
    "subtract": {"arity": [2], "meaning": "Numeric subtraction."},
    "multiply": {"arity": [2], "meaning": "Numeric multiplication."},
    "divide": {"arity": [2], "meaning": "Numeric division; divisor must be non-zero."},
    "modulo": {"arity": [2], "meaning": "Numeric modulo; divisor must be non-zero."},
    "equals": {"arity": [2], "meaning": "Canonical JSON equality."},
    "lt": {"arity": [2], "meaning": "Numeric-or-string less-than comparison."},
    "lte": {"arity": [2], "meaning": "Numeric-or-string less-than-or-equal comparison."},
    "gt": {"arity": [2], "meaning": "Numeric-or-string greater-than comparison."},
    "gte": {"arity": [2], "meaning": "Numeric-or-string greater-than-or-equal comparison."},
    "not": {"arity": [1], "meaning": "Boolean negation."},
    "and": {"arity": [2], "meaning": "Boolean conjunction."},
    "or": {"arity": [2], "meaning": "Boolean disjunction."},
    "if": {"arity": [3], "meaning": "if(condition, when_true, when_false); only the selected branch is evaluated."},
}

_GENERIC_EXAMPLES = [
    {
        "description": "Read field 'name' from root input and uppercase it.",
        "body": {
            "op": "upper",
            "args": [
                {"op": "get", "args": [{"op": "literal", "args": ["name"]}]}
            ],
        },
    },
    {
        "description": "Build an object containing the numeric sum of fields a and b.",
        "body": {
            "op": "object",
            "args": [
                {"op": "literal", "args": ["value"]},
                {
                    "op": "add",
                    "args": [
                        {"op": "get", "args": [{"op": "literal", "args": ["a"]}]},
                        {"op": "get", "args": [{"op": "literal", "args": ["b"]}]},
                    ],
                },
            ],
        },
    },
    {
        "description": "Select one of two literal strings from a boolean input field 'enabled'.",
        "body": {
            "op": "if",
            "args": [
                {"op": "get", "args": [{"op": "literal", "args": ["enabled"]}]},
                {"op": "literal", "args": ["on"]},
                {"op": "literal", "args": ["off"]},
            ],
        },
    },
]


def teacher_dsl_contract() -> dict[str, Any]:
    """Return a detached JSON-safe copy so callers cannot mutate module constants."""

    return json.loads(
        json.dumps(
            {
                "version": DSL_CONTRACT_VERSION,
                "node_shape": {
                    "required_fields": ["op", "args"],
                    "extra_fields_allowed": False,
                    "args_rule": "Each non-literal argument is itself an AST node.",
                },
                "operations": _OPERATION_CONTRACTS,
                "generic_examples": _GENERIC_EXAMPLES,
            },
            ensure_ascii=False,
        )
    )
