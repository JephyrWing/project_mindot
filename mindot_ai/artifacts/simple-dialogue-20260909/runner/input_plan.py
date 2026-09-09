"""Pre-lock formal input/schedule codec. Stdlib only; no provider or grading.

Known bytes stay immutable. Hidden plaintext is not accessed by this module
until a caller supplies an already-verified readiness receipt and file hashes.
All schedules, source rows and original-to-execution mappings are FULL ONLY.
"""
from copy import deepcopy
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
from uuid import UUID, NAMESPACE_URL, uuid5

SEED = 20260908
VERSIONS = ("Q10", "Q11")
NAMESPACE = uuid5(NAMESPACE_URL, "mindot-evaluation-input-plan:" + str(SEED))
FORMAT_VERSION = "formal-input-plan-1"
KNOWN_FILES = {
    "canonical-boundary-cases.json": (20, 51889, "74f6220e5fbdeba7a260f2b49b2479ec1355b901e29d441411d516a5fe7f3762"),
    "canonical-feature-cases.json": (40, 284373, "394f57655774afad81803548807095a05e6426722ce1e321e260399ca2e62b8a"),
    "canonical-regression-cases.json": (40, 357002, "7a780ca3205577f1dbdfa62aacbe84124916b6bd9377aa19145b4dcc467de0f0"),
    "fresh-q10-holdout-cases.json": (36, 273016, "90c2cb93855e3e5300b18981a7416bdd101eae7d2a56986f1f1831ab7c06149c"),
    "known-holdout-regression-cases.json": (24, 185432, "2a0d9917198e5f7a5e51bb8769573b01029531ffdebf461f80460679e80508ce"),
    "known-long-sessions.json": (4, 2802, "4172949989456f86d36f034d8be96db42e3a8a0ffddfcd447802db423ccad986"),
    "q10-long-sessions.json": (6, 3847, "29c9e712f6d37d63f5f7bacf3dda558d1a031b23ee644c28af2f04f0c48808e5"),
}
LONG_FILES = {"known-long-sessions.json", "q10-long-sessions.json"}
RECORD_KEYS = {"recordId", "situation", "automaticThought", "primaryEmotionCode", "primaryIntensity", "beforeBeliefStrength", "contextCategory"}
QUESTION_KEYS = {"questionCode", "questionPurpose", "semanticRouteType", "question", "answer", "askedAt", "answeredAt"}
QUESTION_OUTPUT_KEYS = {"questionCode", "questionPurpose", "semanticRouteType", "question"}
SINGLE_KEYS = {"caseId", "requestType", "currentStep", "record", "questionAnswers", "beforeDistortions",
    "evaluationContext", "situationFamily", "vulnerabilityType", "caseType", "latestInteraction",
    "latestUserIntentHint", "previousQuestions", "answerDisposition", "blockedRoutes", "blockedRouteFamilies",
    "resolvedButIrrelevantTopics", "distortionDefinitions", "safetyCandidates", "expectedDecision",
    "expectedAssessment", "expectedDistortions", "forbiddenDistortions", "allowedQuestionMeanings",
    "forbiddenQuestionMeanings", "explorationCoverage", "completionAssessmentAllowed", "safetyExpectation",
    "caseRationale", "semanticRouteDefinitions", "versions"}
LONG_KEYS = {"sessionCaseId", "versions", "flow", "recordSourceCaseId", "freshSourceCaseId", "maxTurns",
             "removeRuntimeAfterTurn", "answerStrategy", "initialLatestAnswer", "requiredGates",
             "evaluationContext"}
METADATA_ALIASES = {"Q7_BOUNDARY": "BOUNDARY"}
VERSION_METADATA = re.compile(r"(?i)(?:\bQ\d+(?:[_-]|\b)|gpt[-_]|checkpoint|baseline|candidate)")


class InputPlanError(ValueError):
    pass


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha(value):
    return hashlib.sha256(value if isinstance(value, bytes) else canonical(value)).hexdigest()


def _require(condition, message):
    if not condition:
        raise InputPlanError(message)


def _text(value, label):
    _require(isinstance(value, str) and len(value) > 0, label + "_text_required")
    return value


def _time(value):
    _text(value, "timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise InputPlanError("unsupported_timestamp") from exc
    _require(parsed.tzinfo is not None, "timezone_required")


def _read_json(path, *, expected_hash=None, expected_bytes=None):
    raw = Path(path).read_bytes()
    if expected_hash is not None:
        _require(sha(raw) == expected_hash, "input_hash_mismatch")
    if expected_bytes is not None:
        _require(len(raw) == expected_bytes, "input_bytes_mismatch")
    def pairs(items):
        result = {}
        for key, value in items:
            _require(key not in result, "duplicate_json_key")
            result[key] = value
        return result
    try:
        return json.loads(raw.decode("utf-8-sig"), object_pairs_hook=pairs,
                          parse_constant=lambda value: (_ for _ in ()).throw(InputPlanError("nonfinite_json")))
    except (ValueError, UnicodeError) as exc:
        raise InputPlanError("invalid_json_input") from exc


def _record(record):
    _require(isinstance(record, dict), "record_object_required")
    _require(set(record) <= RECORD_KEYS, "unsupported_record_field")
    _require(type(record.get("recordId")) is int and record["recordId"] > 0, "record_id_required")
    _text(record.get("automaticThought"), "automatic_thought")


def _questions(history):
    _require(isinstance(history, list), "question_answers_array_required")
    seen = set()
    for row in history:
        _require(isinstance(row, dict) and set(row) <= QUESTION_KEYS, "unsupported_question_answer_shape")
        for key in ("questionCode", "questionPurpose", "question", "answer"):
            _text(row.get(key), key)
        _require(row["questionCode"] not in seen, "duplicate_question_code")
        seen.add(row["questionCode"])
        if row.get("semanticRouteType") is not None:
            _text(row["semanticRouteType"], "semantic_route")
        _time(row.get("askedAt"))
        _time(row.get("answeredAt"))


def _single(row):
    _require(isinstance(row, dict) and set(row) <= SINGLE_KEYS, "unsupported_single_shape")
    _text(row.get("caseId"), "case_id")
    _record(row.get("record"))
    _require(row.get("requestType") in {"START", "TURN"}, "unsupported_request_type")
    _questions(row.get("questionAnswers"))
    if row["requestType"] == "START":
        _require(row["questionAnswers"] == [] and row.get("currentStep") is None, "start_history_not_empty")
    else:
        _require(bool(row["questionAnswers"]), "turn_history_required")
        _require(row.get("currentStep") == row["questionAnswers"][-1]["questionCode"], "current_step_mismatch")
    _require(isinstance(row.get("beforeDistortions"), list), "distortion_reviews_array_required")
    for review in row["beforeDistortions"]:
        _require(isinstance(review, dict) and set(review) <= {"code", "reviewStatus", "classifierConfidence"}, "unsupported_distortion_review")
        _text(review.get("code"), "distortion_code")
        _text(review.get("reviewStatus"), "distortion_review_status")
    _require(row.get("evaluationContext") is None or isinstance(row["evaluationContext"], dict), "context_object_required")


def _long(row, sources):
    _require(isinstance(row, dict) and set(row) <= LONG_KEYS, "unsupported_long_shape")
    _text(row.get("sessionCaseId"), "session_case_id")
    maximum = row.get("maxTurns")
    _require(type(maximum) is int and maximum > 0, "positive_max_turns_required")
    _require(isinstance(row.get("requiredGates"), list), "required_gates_array_required")
    _require(all(isinstance(item, str) for item in row["requiredGates"]), "required_gates_text_required")
    if row.get("flow") == "TURN_READY_WITH_LATEST_SAFETY":
        _require(row.get("freshSourceCaseId") in sources and maximum == 1, "unsupported_safety_root")
        _require(sources[row["freshSourceCaseId"]]["requestType"] == "TURN", "safety_source_requires_turn")
        _text(row.get("initialLatestAnswer"), "initial_latest_answer")
        _require("answerStrategy" not in row and "recordSourceCaseId" not in row, "ambiguous_safety_root")
    else:
        _require(row.get("flow") in {"START_CONTINUE_REMOVE_REHYDRATE", "START_CONTINUE_4_REMOVE_REHYDRATE"}, "unsupported_long_flow")
        _require(row.get("recordSourceCaseId") in sources and "freshSourceCaseId" not in row, "unresolved_record_source")
        remove = row.get("removeRuntimeAfterTurn")
        _require(type(remove) is int and 0 < remove < maximum, "invalid_remove_turn")
        strategy = row.get("answerStrategy")
        _require(isinstance(strategy, dict) and set(strategy) == {"sequence", "default"}, "unsupported_answer_strategy")
        _require(isinstance(strategy["sequence"], list), "answer_sequence_array_required")
        for answer in strategy["sequence"]:
            _text(answer, "fixed_answer")
        _text(strategy["default"], "default_answer")


def _choice(row, names):
    values = []
    for container in (row, row.get("evaluationContext") or {}):
        for name in names:
            if name in container:
                values.append(container[name])
    if not values:
        return None
    _require(all(value == values[0] for value in values), "conflicting_context_aliases:" + names[0])
    return deepcopy(values[0])


def _as_list(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [value]  # Preserve the complete scalar; never split/summarize it.
    _require(isinstance(value, list), "unsupported_context_collection")
    return deepcopy(value)


def _metadata(value):
    _require(value is None or isinstance(value, str), "metadata_scalar_required")
    if value in METADATA_ALIASES:
        return METADATA_ALIASES[value]
    _require(value is None or (isinstance(value, str) and not VERSION_METADATA.search(value)), "unmapped_metadata_iteration")
    return value


def grading_context(case, common_definitions):
    row = case["original"]
    result = {"suite": case["suite"], "caseType": _metadata(row.get("caseType")) if case["kind"] == "single" else "long",
        "situation_family": _metadata(_choice(row, ("situationFamily", "situation_family"))),
        "vulnerability_type": _metadata(_choice(row, ("vulnerabilityType", "vulnerability_type"))),
        "latestUserIntentHint": _choice(row, ("latestUserIntentHint", "latest_user_intent_hint")),
        "expectedDecision": _choice(row, ("expectedDecision", "expected_decision")),
        "expectedAssessment": _choice(row, ("expectedAssessment", "expected_assessment"))}
    if result["caseType"] is None:
        result["caseType"] = "single"
    for key, snake in (("expectedDistortions", "expected_distortions"),
                       ("allowedQuestionMeanings", "allowed_question_meanings"),
                       ("forbiddenQuestionMeanings", "forbidden_question_meanings")):
        result[key] = _as_list(_choice(row, (key, snake)))
    definitions = _choice(row, ("semanticRouteDefinitions", "semantic_route_definitions"))
    result["semanticRouteDefinitions"] = deepcopy(common_definitions if definitions is None else definitions)
    return result


def _catalog(single_files, long_files, *, suite, common_definitions=None):
    sources = {}
    cases = []
    for filename, rows in single_files.items():
        _require(isinstance(rows, list), "single_top_level_array_required")
        for row in rows:
            _single(row)
            _require(row["caseId"] not in sources, "duplicate_source_case_id")
            sources[row["caseId"]] = deepcopy(row)
            cases.append({"caseKey": suite + ":" + row["caseId"], "kind": "single", "suite": suite,
                          "sourceFile": filename, "original": deepcopy(row), "maxTurns": 1})
    for filename, rows in long_files.items():
        _require(isinstance(rows, list), "long_top_level_array_required")
        for row in rows:
            _long(row, sources)
            cases.append({"caseKey": suite + ":" + row["sessionCaseId"], "kind": "long", "suite": suite,
                          "sourceFile": filename, "original": deepcopy(row), "maxTurns": row["maxTurns"]})
    _require(len({case["caseKey"] for case in cases}) == len(cases), "duplicate_case_key")
    definitions = [_choice(row, ("semanticRouteDefinitions", "semantic_route_definitions")) for row in sources.values()]
    definitions = [value for value in definitions if value is not None]
    if common_definitions is None:
        _require(bool(definitions), "common_route_definitions_missing")
        common_definitions = definitions[0]
    _require(all(value == common_definitions for value in definitions), "common_route_definitions_conflict")
    for case in cases:
        case["gradingContext"] = grading_context(case, common_definitions)
    return {"formatVersion": FORMAT_VERSION, "suite": suite, "cases": cases, "sources": sources,
            "commonSemanticRouteDefinitions": deepcopy(common_definitions)}


def load_known(directory):
    directory = Path(directory).resolve()
    singles, longs = {}, {}
    for name, (count, length, digest) in KNOWN_FILES.items():
        rows = _read_json(directory / name, expected_hash=digest, expected_bytes=length)
        _require(isinstance(rows, list) and len(rows) == count, "known_count_mismatch")
        (longs if name in LONG_FILES else singles)[name] = rows
    result = _catalog(singles, longs, suite="known")
    _require(len(result["cases"]) == 170, "known_case_count_mismatch")
    _require(sum(case["maxTurns"] for case in result["cases"] if case["kind"] == "long") == 60, "known_long_ceiling_mismatch")
    result["inputHashes"] = {name: digest for name, (_, _, digest) in KNOWN_FILES.items()}
    return result


def hidden_schema_contract():
    """Declares supported shapes without opening ciphertext, key or plaintext."""
    return {"formatVersion": FORMAT_VERSION, "topLevel": "JSON_ARRAY_PER_KIND",
        "singleAllowedKeys": sorted(SINGLE_KEYS), "longAllowedKeys": sorted(LONG_KEYS),
        "singleCount": 12, "longCount": 2,
        "singleRequired": ["caseId", "requestType", "record", "questionAnswers", "beforeDistortions"],
        "longFlows": ["START_CONTINUE_REMOVE_REHYDRATE", "START_CONTINUE_4_REMOVE_REHYDRATE", "TURN_READY_WITH_LATEST_SAFETY"],
        "answerStrategy": "sequence[index] else default; exact text; no semantic branch",
        "sourceReferences": "exact unique single caseId in this same hidden suite",
        "unknownShapes": "FAIL_CLOSED_PRESERVE_ORIGINAL_NO_TRANSLATION",
        "maxTurns": "positive integer declared by sealed input, not read before gate",
        "formula": "requests_per_version=160+60+12+sum(hidden_long.maxTurns)",
        "metadataAliases": deepcopy(METADATA_ALIASES)}


def load_hidden(single_path, long_path, *, gate_receipt, expected_gate_sha256,
                single_sha256, long_sha256, common_definitions):
    # Readiness evidence is checked before either plaintext path is opened.
    _require(isinstance(gate_receipt, dict) and sha(gate_receipt) == expected_gate_sha256, "hidden_gate_hash_mismatch")
    _require(gate_receipt.get("status") == "READY_FOR_HOLDOUT_LOCK" and
             gate_receipt.get("canaryEligible") is True and gate_receipt.get("formalLocksComplete") is True,
             "hidden_gate_not_ready")
    _require(all(isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value)
                 for value in (single_sha256, long_sha256)), "verified_hidden_file_hashes_required")
    _require(Path(single_path).name != Path(long_path).name, "duplicate_hidden_filename")
    singles = _read_json(single_path, expected_hash=single_sha256)
    longs = _read_json(long_path, expected_hash=long_sha256)
    _require(isinstance(singles, list) and len(singles) == 12, "hidden_single_count_mismatch")
    _require(isinstance(longs, list) and len(longs) == 2, "hidden_long_count_mismatch")
    result = _catalog({Path(single_path).name: singles}, {Path(long_path).name: longs},
                      suite="hidden", common_definitions=common_definitions)
    result["inputHashes"] = {Path(single_path).name: single_sha256, Path(long_path).name: long_sha256}
    result["gateReceiptSha256"] = expected_gate_sha256
    return result


class IdentityMap:
    """Deterministic full-only execution identities; no output text dependency."""
    def __init__(self):
        self._values = {}
        self._owners = {}

    def _put(self, key, value):
        owner = self._owners.setdefault(str(value), key)
        _require(owner == key, "execution_id_collision")
        self._values[key] = value
        return value

    def identity(self, case_key, version, turn_index):
        _require(version in VERSIONS and type(turn_index) is int and turn_index >= 0, "invalid_execution_identity")
        prefix = case_key + ":" + version
        session = int.from_bytes(hashlib.sha256((str(SEED) + ":session:" + prefix).encode()).digest()[:8], "big") & ((1 << 63) - 1)
        _require(session > 0, "zero_session_identity")
        request = str(uuid5(NAMESPACE, "request:" + prefix + ":" + str(turn_index)))
        return {"sessionId": self._put("session:" + prefix, session),
                "requestId": self._put("request:" + prefix + ":" + str(turn_index), request)}

    def full_only(self):
        return {"seed": SEED, "namespace": str(NAMESPACE), "originalToExecution": deepcopy(self._values)}


def ceilings(cases, *, per_request_seconds=180, cleanup_margin_seconds=30):
    _require(type(per_request_seconds) is int and per_request_seconds > 0, "request_deadline_required")
    _require(type(cleanup_margin_seconds) is int and cleanup_margin_seconds >= 0, "cleanup_margin_required")
    requests = sum(case["maxTurns"] for case in cases)
    # Q10's original A=S=2, SDK retries=2. Q11's separately verified maximum
    # three generic invocations / one Moderation per request is not imposed on Q10.
    return {"caseVersionRecords": len(cases) * 2, "productRequestsPerVersion": requests,
        "longSessionDeadlines": {case["caseKey"]: case["maxTurns"] * per_request_seconds + cleanup_margin_seconds
                                 for case in cases if case["kind"] == "long"},
        "versions": {"Q10": {"productRequests": requests, "genericLogicalInvocations": requests * 4,
                               "physicalGenericAttempts": requests * 12, "moderationCalls": 0},
                     "Q11": {"productRequests": requests, "genericLogicalInvocations": requests * 3,
                               "physicalGenericAttempts": requests * 3, "moderationCalls": requests}},
        "formula": "single_count + sum(long.maxTurns); Q10 generic4/physical12; Q11 generic3/physical3+moderation1",
        "perRequestDeadlineSeconds": per_request_seconds, "cleanupMarginSeconds": cleanup_margin_seconds,
        "maximumConcurrentProductRequests": 2, "maximumConcurrentPerVersion": 1}


def build_schedule(known, hidden=None, *, identities=None):
    identities = identities if identities is not None else IdentityMap()
    cases = deepcopy(known["cases"] + ([] if hidden is None else hidden["cases"]))
    _require(len({case["caseKey"] for case in cases}) == len(cases), "duplicate_schedule_case")
    # Hidden release only extends the locked known schedule; it never changes
    # known ordering/mapping as a side effect of a newly known case count.
    groups = [deepcopy(known["cases"])] + ([] if hidden is None else [deepcopy(hidden["cases"])])
    cases, baseline_a = [], set()
    for group in groups:
        group.sort(key=lambda case: sha({"seed": SEED, "purpose": "order", "caseKey": case["caseKey"]}))
        anonymous_rank = sorted(group, key=lambda case: sha({"seed": SEED, "purpose": "anonymous", "caseKey": case["caseKey"]}))
        baseline_a.update(case["caseKey"] for case in anonymous_rank[:(len(group) + 1) // 2])
        cases.extend(group)
    schedule = []
    for index, case in enumerate(cases):
        order = list(VERSIONS if index % 2 == 0 else reversed(VERSIONS))
        anonymous = {"Q10": "A" if case["caseKey"] in baseline_a else "B",
                     "Q11": "B" if case["caseKey"] in baseline_a else "A"}
        schedule.append({"caseKey": case["caseKey"], "kind": case["kind"], "suite": case["suite"],
                         "maxTurns": case["maxTurns"], "versionOrder": order, "anonymousVersions": anonymous,
                         "initialIdentities": {version: identities.identity(case["caseKey"], version, 0) for version in VERSIONS}})
    return {"formatVersion": FORMAT_VERSION, "fullOnly": True, "seed": SEED,
        "hiddenIncluded": hidden is not None, "schedule": schedule, "ceilings": ceilings(cases),
        "mapping": identities.full_only(), "scheduleSha256": sha(schedule)}


def initial_request(case, version, catalog, identities):
    row = case["original"]
    if case["kind"] == "single":
        source = row
        request_type = row["requestType"]
    elif row["flow"] == "TURN_READY_WITH_LATEST_SAFETY":
        source = deepcopy(catalog["sources"][row["freshSourceCaseId"]])
        source["questionAnswers"][-1]["answer"] = row["initialLatestAnswer"]
        request_type = "TURN"
    else:
        source = catalog["sources"][row["recordSourceCaseId"]]
        request_type = "START"
    request = {**identities.identity(case["caseKey"], version, 0), "record": deepcopy(source["record"])}
    if request_type == "TURN":
        request.update(currentStep=source["currentStep"], questionAnswers=deepcopy(source["questionAnswers"]),
                       beforeDistortions=deepcopy(source["beforeDistortions"]))
    return request


def remove_before_next(case, completed_turn_index):
    return (case["kind"] == "long" and completed_turn_index + 1 < case["maxTurns"] and
            case["original"].get("removeRuntimeAfterTurn") == completed_turn_index + 1)


def followup_request(case, version, previous_request, actual_response, next_turn_index, identities,
                     *, asked_at, answered_at):
    _require(case["kind"] == "long" and type(next_turn_index) is int and next_turn_index > 0, "long_followup_required")
    if next_turn_index >= case["maxTurns"]:
        return None
    _require(isinstance(actual_response, dict), "actual_response_required_no_fabricated_question")
    expected_previous = identities.identity(case["caseKey"], version, next_turn_index - 1)
    _require(all(previous_request.get(key) == value for key, value in expected_previous.items()), "previous_request_identity_mismatch")
    _require(actual_response.get("requestId") == previous_request["requestId"], "response_request_identity_mismatch")
    status = actual_response.get("status")
    _require(status in {"CONTINUE", "CONFIRM_REQUIRED", "SAFETY_STOP"}, "unsupported_external_status")
    if status != "CONTINUE":
        return None
    question = actual_response.get("nextQuestion")
    _require(isinstance(question, dict) and set(question) == QUESTION_OUTPUT_KEYS, "actual_generated_question_required")
    for name in QUESTION_OUTPUT_KEYS:
        _text(question[name], "generated_" + name)
    prior_history = deepcopy(previous_request.get("questionAnswers", []))
    _require(all(item["questionCode"] != question["questionCode"] for item in prior_history), "generated_question_code_collision")
    strategy = case["original"].get("answerStrategy")
    _require(isinstance(strategy, dict), "fixed_strategy_required")
    answer_index = next_turn_index - 1
    answer = (strategy["sequence"][answer_index] if answer_index < len(strategy["sequence"]) else strategy["default"])
    _time(asked_at)
    _time(answered_at)
    history = prior_history + [{**deepcopy(question), "answer": answer, "askedAt": asked_at, "answeredAt": answered_at}]
    return {**identities.identity(case["caseKey"], version, next_turn_index),
        "record": deepcopy(previous_request["record"]), "currentStep": question["questionCode"],
        "questionAnswers": history, "beforeDistortions": deepcopy(previous_request.get("beforeDistortions", []))}
