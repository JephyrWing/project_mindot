"""Normative schemas for simple-dialogue-1; not a product implementation.

Port these static shapes into mindot_ai. Do not import docs at runtime.
There are no source-dependent enums, source reviews, slot aliases, hidden
coverage gates, schema compaction, or provider calls in this reference.

All object properties are required; nullable fields express absence. IDs are
actual IDs from the current server view, validated once at the tool boundary.
JSON Schema establishes shape, not source validity or semantic correctness.
Strings have no arbitrary internal cap. The output call budget bounds internal
text; only existing public field capacities constrain user-facing text here.
Occurrence is a zero-based exact-match occurrence, or null when unambiguous.

Provider wrappers below show both Chat Completions and Responses wire shapes.
Local JSON Schema validation is not evidence of live provider acceptance.
"""

from copy import deepcopy
import json


CONTRACT_REVISION = "simple-dialogue-1"

# Frozen values from current contracts.Move, including the two entries omitted
# by the previous provider schema. No runtime source/goal/request enums exist.
MOVES = [
    "OBSERVABLE_DETAIL", "DIRECT_SUPPORT", "COUNTEREVIDENCE",
    "ALTERNATIVE_HYPOTHESIS", "FACT_CERTAINTY_CHECK", "BALANCED_SYNTHESIS",
    "USER_DIRECTION",
]
DOMAINS = [
    "evidenceFor", "evidenceAgainst", "alternativeViews", "acknowledgement",
]
EVIDENCE_ROLES = [
    "OBSERVED_FACT", "REPORTED_FACT", "USER_INFERENCE",
    "ALTERNATIVE_HYPOTHESIS", "BALANCED_SYNTHESIS", "EXPLICIT_NONE",
]
DISTORTION_CODES = [
    "ALL_OR_NOTHING_THINKING", "CATASTROPHIZING_FORTUNE_TELLING",
    "DISQUALIFYING_DISCOUNTING_POSITIVE", "EMOTIONAL_REASONING", "LABELING",
    "MAGNIFICATION_MINIMIZATION", "MENTAL_FILTER_SELECTIVE_ABSTRACTION",
    "MIND_READING", "OVERGENERALIZATION", "PERSONALIZATION",
    "SHOULD_MUST_STATEMENTS", "TUNNEL_VISION",
]
RISK_REASON_CODES = [
    "SELF_HARM", "SUICIDE", "HARM_TO_OTHERS", "IMMEDIATE_DANGER",
    "AMBIGUOUS_SAFETY_SIGNAL",
]
CLARIFICATION_GOALS = ["subject", "currentness", "intent", "immediacy"]


def obj(properties):
    return {
        "type": "object", "properties": properties,
        "required": list(properties), "additionalProperties": False,
    }


def text(maximum=None):
    result = {"type": "string", "minLength": 1}
    if maximum is not None:
        result["maxLength"] = maximum
    return result


def enum(values):
    return {"type": "string", "enum": list(values)}


def literal(value):
    return enum([value])


def nullable(schema):
    return {"anyOf": [schema, {"type": "null"}]}


def array(items, minimum=0):
    return {"type": "array", "items": items, "minItems": minimum}


def occurrence():
    return nullable({"type": "integer", "minimum": 0})


def reference():
    return obj({"sourceId": text(), "quote": text(), "occurrence": occurrence()})


def source_ids():
    # Existence, session membership and current validity are runtime invariants.
    return array(text())


def updates_schema():
    events = [
        obj({
            "type": literal("GOAL"), "questionCode": text(),
            "status": enum(["ANSWERED", "NONE", "SKIPPED", "REOPEN"]),
            "sourceId": text(), "note": text(),
        }),
        obj({
            "type": literal("CORRECTION"),
            "operation": enum(["RETRACT", "REAFFIRM"]),
            "targetSourceId": text(), "targetQuote": nullable(text()),
            "occurrence": occurrence(), "sourceId": text(),
            "instructionQuote": text(),
        }),
        obj({
            "type": literal("REQUEST"), "sourceId": text(), "quote": text(),
            "kind": enum(["EXAMPLE", "EXPLANATION"]),
            "targetQuestionCode": nullable(text()),
        }),
        obj({
            "type": literal("REQUEST_UPDATE"), "requestId": text(),
            "operation": enum(["CANCEL", "RETARGET"]), "sourceId": text(),
            "targetQuestionCode": nullable(text()),
        }),
        obj({
            "type": literal("RESUME"), "scope": enum(["DIALOGUE", "SAFETY"]),
            "targetId": text(), "sourceId": text(), "reason": text(),
        }),
    ]
    # When there is no relevant event, send null rather than an empty structure.
    return nullable(obj({"events": array({"anyOf": events}, minimum=1)}))


def selected_request_schema():
    # NEW appears only here when selected; a separate REQUEST event is solely
    # for another request that will remain unfulfilled after this response.
    return {"anyOf": [
        obj({
            "origin": literal("NEW"), "sourceId": text(), "quote": text(),
            "kind": enum(["EXAMPLE", "EXPLANATION"]),
            "targetQuestionCode": text(),
        }),
        obj({"origin": literal("EXISTING"), "requestId": text()}),
    ]}


def safety_schema():
    common = {
        "episodeId": nullable(text()), "trigger": reference(),
        "sourceIds": source_ids(), "reason": text(),
    }
    # STOP maps urgency to the existing public risk level; the single runtime
    # boundary retains current reasonCode/level compatibility. CLARIFY retains
    # the existing episode clarification allowance; no additional call is added.
    return {"anyOf": [
        obj({
            "action": literal("STOP"), **deepcopy(common),
            "reasonCode": enum(RISK_REASON_CODES[:-1]),
            "urgency": enum(["IMMEDIATE", "REVIEW"]),
        }),
        obj({
            "action": literal("CLARIFY"), **deepcopy(common),
            "reasonCode": enum([
                "SELF_HARM", "SUICIDE", "HARM_TO_OTHERS",
                "AMBIGUOUS_SAFETY_SIGNAL",
            ]),
            "clarificationGoal": enum(CLARIFICATION_GOALS),
        }),
    ]}


def select_schemas():
    """All five tools are static and independent of session size/state."""
    return {
        "write_turn": obj({
            "updates": updates_schema(), "move": enum(MOVES), "focus": text(),
            "sourceIds": source_ids(), "targetQuestionCode": nullable(text()),
        }),
        "present_pending_question": obj({
            "updates": updates_schema(), "request": selected_request_schema(),
        }),
        "assess_completion": obj({
            "updates": updates_schema(), "reason": text(),
            "sourceIds": source_ids(),
        }),
        "respond_control": obj({
            "updates": updates_schema(),
            "mode": enum([
                "STOP", "CLARIFY_TARGET", "WAIT", "ASSESSMENT_TARGET",
                "USER_DIRECTION",
            ]),
            "targetId": nullable(text()), "sourceIds": source_ids(),
            "reason": text(),
        }),
        "respond_safety": obj({
            "updates": updates_schema(), "safety": safety_schema(),
        }),
    }


TOOL_DESCRIPTIONS = {
    "write_turn": "Choose one useful CBT question goal; Writer expresses it.",
    "present_pending_question": "Fulfil one actual example or explanation request.",
    "assess_completion": "Ask Assessor for a grounded candidate; no four-domain gate.",
    "respond_control": "Return existing stop, target, waiting or direction guidance.",
    "respond_safety": "Return existing safety clarification or safety-stop guidance.",
}


def select_tools():
    """Chat Completions function-tool wire wrappers, not executable callables."""
    return [
        {"type": "function", "function": {
            "name": name, "description": TOOL_DESCRIPTIONS[name],
            "strict": True, "parameters": schema,
        }}
        for name, schema in select_schemas().items()
    ]


def writer_schema():
    # Whitespace-only rejection belongs to the one output boundary. No grammar,
    # question-mark, line-break, mandatory preface or ending-style constraint.
    return obj({"message": text(500)})


def evidence_schema():
    return obj({
        "sourceId": text(), "quote": text(), "occurrence": occurrence(),
        "role": enum(EVIDENCE_ROLES), "domain": nullable(enum(DOMAINS)),
    })


def assessor_schema():
    common = {
        "automaticThought": reference(), "evidence": array(evidence_schema()),
        "reason": text(), "calibratedThought": text(4000),
        "proposalMessage": text(1000),
    }
    return obj({"result": {"anyOf": [
        obj({
            "type": literal("DISTORTION_PRESENT"), **deepcopy(common),
            "matchedCode": enum(DISTORTION_CODES),
        }),
        obj({
            "type": literal("NO_CLEAR_DISTORTION"), **deepcopy(common),
            "matchedCode": {"type": "null"},
        }),
        obj({
            "type": literal("FACT_BOUNDARY_REQUIRED"),
            "missingFact": text(), "whyDecisionDependsOnIt": text(),
            "sourceIds": source_ids(), "question": text(500),
        }),
        obj({
            "type": literal("UNRESOLVED"), "reason": text(),
            "sourceIds": source_ids(),
        }),
    ]}})


def review_schema():
    # The candidate and its ToolMessage/call ID are supplied by the server; the
    # model does not copy an ID or author a replacement candidate in this phase.
    return obj({"decision": enum(["ACCEPT", "REJECT"]), "reason": text()})


def writer_repair_schema():
    return writer_schema()


def response_format(name, schema):
    """Chat Completions response_format; use the appropriate phase only."""
    return {"type": "json_schema", "json_schema": {
        "name": name, "strict": True, "schema": deepcopy(schema),
    }}


def responses_format(name, schema):
    """Responses text.format equivalent; do not mix provider wrappers."""
    return {
        "type": "json_schema", "name": name, "strict": True,
        "schema": deepcopy(schema),
    }


def responses_tools():
    return [
        {"type": "function", **deepcopy(item["function"])}
        for item in select_tools()
    ]


def provider_examples():
    """Configuration fragments only; the graph registers the real callables.

    SELECT uses exactly one required tool call. TOOL execution then produces an
    actual ToolMessage. ASSESSMENT_REVIEW consumes that actual result. Writer
    repair consumes its frozen original plan; it has no SELECT tools.
    """
    return {
        "SELECT": {
            "tools": select_tools(), "tool_choice": "required",
            "parallel_tool_calls": False,
        },
        "WRITER": {"response_format": response_format("cbt_writer", writer_schema())},
        "ASSESSOR": {"response_format": response_format("cbt_assessor", assessor_schema())},
        "ASSESSMENT_REVIEW": {
            "response_format": response_format("cbt_review", review_schema()),
        },
        "WRITER_REPAIR": {
            "response_format": response_format("cbt_writer_repair", writer_repair_schema()),
        },
    }


if __name__ == "__main__":
    def byte_size(value):
        return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))

    print(json.dumps({
        "contractRevision": CONTRACT_REVISION,
        "selectTools": len(select_tools()),
        "selectToolsBytes": byte_size(select_tools()),
        "writerSchemaBytes": byte_size(writer_schema()),
        "assessorSchemaBytes": byte_size(assessor_schema()),
        "reviewSchemaBytes": byte_size(review_schema()),
        "writerRepairSchemaBytes": byte_size(writer_repair_schema()),
        "liveProviderValidated": False,
    }, ensure_ascii=False, indent=2))
