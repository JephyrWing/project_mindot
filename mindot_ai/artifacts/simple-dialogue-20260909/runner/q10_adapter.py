"""Exact Q10 facade, instrumented below real SDK parsing. Not a policy shim.

Run in a Q10-only worker with assets/Q10/mindot_ai first on sys.path. The
launcher owns credentials, environment locks, deadlines and durable observer.
No dotenv suppression, network fallback, output cap or retry override is added
here. Offline launchers MUST suppress dotenv and prohibit socket connections.
"""
from __future__ import annotations

import asyncio
import base64
from contextvars import ContextVar
from copy import deepcopy
from dataclasses import asdict, is_dataclass
from enum import Enum
import hashlib
import importlib
import inspect
import json
from pathlib import Path
import sys
import time
import traceback
from uuid import UUID, uuid4


SOURCE = Path(__file__).resolve().parent.parent / "baseline"
SOURCE_HASHES = {
    "cbt_session_agent.py": "e359c881ac8a544955800372e6db3b84e657ea5d0ebd55b81cb0118ef31e6d5d",
    "cbt_agent.py": "ee8f742d7ae90a0a23846f867382fc04991ba4244075b6a460d8a3fd42b2b613",
}
_ACTIVE = ContextVar("q10_raw_observer", default=None)
_LOGICAL = ContextVar("q10_logical_invocation", default=None)
_HOOKED = False
_DEFAULT = None
_HEADER_ALLOWLIST = {"content-type", "x-request-id", "request-id", "retry-after", "retry-after-ms"}


class AdapterIntegrityError(RuntimeError):
    """An observer/source failure: never an ordinary retryable product error."""


class _ObservationAbort(BaseException):
    """Bypass SDK Exception retry handling if durable capture/budget fails."""


def _json(value):
    if inspect.isclass(value):
        return {"pythonSchema": value.__name__}
    if hasattr(value, "model_dump"):
        return value.model_dump(by_alias=True, mode="json")
    if is_dataclass(value):
        return _json(asdict(value))
    if isinstance(value, dict):
        return {str(key): _json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json(item) for item in value]
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, UUID):
        return str(value)
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    # SDK sentinels and schema classes are metadata, not inferred output.
    return {"pythonType": type(value).__name__}


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _safe_kwargs(kwargs):
    # Actual SDK kwargs can carry extra_headers or token secrets. Never export
    # those containers; exact generation kwargs remain, actual body is separate.
    return {key: _json(value) for key, value in kwargs.items()
            if key not in {"extra_headers", "api_key", "authorization", "headers"}}


def _role(kwargs):
    names = [item.get("name") for item in kwargs.get("tools", []) if isinstance(item, dict)]
    if "ask_question" in names:
        return "Agent"
    schema = kwargs.get("text_format") or kwargs.get("response_format")
    name = getattr(schema, "__name__", None)
    if name is None:
        name = kwargs.get("text", {}).get("format", {}).get("name")
    return {"QuestionWordingDraft": "Writer", "CompletionAssessmentEnvelope": "Assessor"}.get(name, "UNKNOWN")


class _Capture:
    def __init__(self, observer, fake_transport):
        self.observer = observer
        self.fake_transport = fake_transport
        self.events = []
        self.failed = None
        self.logical_count = 0
        self.physical_count = 0
        self.received_count = 0
        self.model_responses = 0
        self.receipt_unknown = False
        self.input_view = None
        self.run_id = uuid4().hex

    async def emit(self, kind, payload):
        if self.failed is not None:
            raise _ObservationAbort()
        try:
            result = self.observer(kind, deepcopy(payload))
            if inspect.isawaitable(result):
                await result
        except BaseException as exc:
            self.failed = exc
            raise _ObservationAbort() from exc
        self.events.append({"kind": kind, "payload": deepcopy(payload)})


def _input_view(body):
    # Only the view actually sent; do not rerun a product view builder later.
    for message in reversed(body.get("input", [])):
        if not isinstance(message, dict) or message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, list):
            content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
        if isinstance(content, str):
            try:
                value = json.loads(content)
                return value if isinstance(value, dict) else None
            except ValueError:
                return None
    return None


def _install_hooks():
    global _HOOKED
    if _HOOKED:
        return
    import httpx
    from openai.resources.responses.responses import AsyncResponses

    original_send = httpx.AsyncClient.send
    original_create = AsyncResponses.create
    original_parse = AsyncResponses.parse

    def logical_wrapper(original, method):
        async def wrapped(resource, *args, **kwargs):
            capture = _ACTIVE.get()
            if capture is None or _LOGICAL.get() is not None:
                return await original(resource, *args, **kwargs)
            capture.logical_count += 1
            logical = {"logicalInvocationId": f"{capture.run_id}-L{capture.logical_count}",
                       "component": _role(kwargs), "sdkMethod": method,
                       "sdkKwargs": _safe_kwargs(kwargs),
                       "sdkMaxRetries": resource._client.max_retries}
            token = _LOGICAL.set(logical)
            try:
                await capture.emit("logical_dispatch", logical)
                value = await original(resource, *args, **kwargs)
                await capture.emit("logical_resource_return", logical)
                return value
            except Exception as exc:
                await capture.emit("logical_error", {**logical, "errorType": type(exc).__name__})
                raise
            finally:
                _LOGICAL.reset(token)
        return wrapped

    async def observed_send(client, request, *args, **kwargs):
        capture = _ACTIVE.get()
        if capture is None:
            return await original_send(client, request, *args, **kwargs)
        if capture.failed is not None:
            raise _ObservationAbort()
        logical = _LOGICAL.get()
        if logical is None or not request.url.path.endswith("/responses"):
            capture.failed = AdapterIntegrityError("unrecognized_provider_boundary")
            raise _ObservationAbort()
        capture.physical_count += 1
        body_bytes = await request.aread()
        try:
            body = json.loads(body_bytes)
        except (ValueError, UnicodeError):
            body = None
        invocation_id = f"{logical['logicalInvocationId']}-P{capture.physical_count}"
        linkage = {"invocationId": invocation_id,
                   "logicalInvocationId": logical["logicalInvocationId"],
                   "component": logical["component"],
                   "sdkRetryCount": request.headers.get("x-stainless-retry-count"),
                   "fake": capture.fake_transport is not None}
        dispatch = {**linkage, "method": request.method, "path": request.url.path,
                    "requestBodyBase64": base64.b64encode(body_bytes).decode("ascii"),
                    "requestBodySha256": _sha(body_bytes), "requestBody": body,
                    "timeout": _json(request.extensions.get("timeout"))}
        if logical["component"] == "Agent" and isinstance(body, dict):
            capture.input_view = _input_view(body)
        await capture.emit("provider_dispatch", dispatch)
        started = time.monotonic()
        response = None
        try:
            if capture.fake_transport is None:
                response = await original_send(client, request, *args, **kwargs)
            else:
                # The request was built by the real SDK. Only transport IO is fake.
                transport = capture.fake_transport
                if hasattr(transport, "handle_async_request"):
                    response = await transport.handle_async_request(request)
                else:
                    response = transport(request)
                    if inspect.isawaitable(response):
                        response = await response
                response.request = request
            raw = await response.aread()
        except Exception as exc:
            capture.receipt_unknown = True
            await capture.emit("provider_transport_error", {**linkage,
                "errorType": type(exc).__name__, "responseReceived": response is not None,
                "statusCode": response.status_code if response is not None else None,
                "elapsedSeconds": time.monotonic() - started,
                "responseReceipt": "UNKNOWN"})
            raise
        capture.received_count += 1
        if 200 <= response.status_code < 300:
            capture.model_responses += 1
        # First durable observation is bytes. Parsing/usage extraction follows it.
        await capture.emit("provider_raw", {**linkage, "statusCode": response.status_code,
            "headers": {key: value for key, value in response.headers.items()
                        if key.lower() in _HEADER_ALLOWLIST},
            "responseBodyBase64": base64.b64encode(raw).decode("ascii"),
            "responseBodySha256": _sha(raw), "elapsedSeconds": time.monotonic() - started})
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeError):
            parsed = None
        await capture.emit("provider_received", {**linkage,
            "usage": parsed.get("usage") if isinstance(parsed, dict) else None,
            "bodyIsJson": isinstance(parsed, (dict, list)),
            "responseStatus": parsed.get("status") if isinstance(parsed, dict) else None})
        return response

    # Hooks remain process-local and inert outside the ContextVar scope. This
    # accommodates SDK cached raw-response wrappers without cross-request closure.
    AsyncResponses.create = logical_wrapper(original_create, "responses.create")
    AsyncResponses.parse = logical_wrapper(original_parse, "responses.parse")
    httpx.AsyncClient.send = observed_send
    _HOOKED = True


def _load_product():
    for filename, expected in SOURCE_HASHES.items():
        if _sha((SOURCE / filename).read_bytes()) != expected:
            raise AdapterIntegrityError("q10_source_hash_mismatch")
    # Reject contaminated or misconfigured workers before executing any module
    # from an unintended implementation (post-import checking alone is too late).
    for name in ("cbt_session_agent", "cbt_agent"):
        existing = sys.modules.get(name)
        if existing is not None:
            origin = getattr(existing, "__file__", None)
        else:
            spec = importlib.util.find_spec(name)
            origin = spec.origin if spec is not None else None
        if origin is None or Path(origin).resolve().parent != SOURCE.resolve():
            raise AdapterIntegrityError("q10_source_import_root_mismatch")
    module = importlib.import_module("cbt_session_agent")
    shared = importlib.import_module("cbt_agent")
    for imported in (module, shared):
        if Path(imported.__file__).resolve().parent != SOURCE.resolve():
            raise AdapterIntegrityError("q10_source_import_root_mismatch")
    return module, shared


def _snapshot(runtime):
    if runtime is None:
        return None
    return {"state": _json(runtime.state), "history": _json(runtime.history),
        "pending_question": _json(runtime.pending_question),
        "pending_plan": _json(runtime.pending_plan),
        "pending_assessment": _json(runtime.pending_assessment),
        "pending_assessment_gap": _json(runtime.pending_assessment_gap),
        "unanswered_question_attempts": _json(runtime.unanswered_question_attempts)}


class Q10Adapter:
    def __init__(self):
        self.product, self.shared = _load_product()
        _install_hooks()
        self.registry = self.product.CbtAgentSessionRegistry(
            ttl_seconds=self.product.CBT_AGENT_SESSION_TTL_SECONDS)
        self.lock = asyncio.Lock()
        self.integrity_failure = None
        self.accepted = {}

    async def invoke(self, public_request, *, observer, fake_transport=None):
        if not callable(observer):
            raise TypeError("durable observer is required")
        async with self.lock:
            if self.integrity_failure is not None:
                raise AdapterIntegrityError("q10_observer_failure_latched") from self.integrity_failure
            capture = _Capture(observer, fake_transport)
            token = _ACTIVE.set(capture)
            response = None
            error = None
            session_id = None
            try:
                # DTO parsing, source route and all model factories are unchanged.
                request_type = (self.shared.CbtTurnRequest if "questionAnswers" in public_request
                                else self.shared.CbtStartRequest)
                request = request_type.model_validate(public_request)
                session_id = request.session_id
                entry = (self.product.generate_agent_cbt_turn if request_type is self.shared.CbtTurnRequest
                         else self.product.generate_agent_cbt_start)
                response = await entry(request, registry=self.registry)
                self.accepted[session_id] = _snapshot(self.registry._sessions.get(session_id))
            except _ObservationAbort:
                self.integrity_failure = capture.failed
                raise AdapterIntegrityError("q10_observer_failure") from capture.failed
            except Exception as exc:
                error = {"type": type(exc).__name__, "stage": "PRODUCT_OR_PROVIDER",
                         "responseReceipt": ("UNKNOWN" if capture.receipt_unknown else
                                             "OBSERVED" if capture.received_count else "NOT_DISPATCHED"),
                         "traceback": [{"file": frame.filename, "line": frame.lineno,
                                        "function": frame.name}
                                       for frame in traceback.extract_tb(exc.__traceback__)]}
            finally:
                _ACTIVE.reset(token)
            if capture.failed is not None:
                self.integrity_failure = capture.failed
                raise AdapterIntegrityError("q10_observer_failure") from capture.failed
            runtime = self.registry._sessions.get(session_id)
            diagnostics = _json(runtime.last_diagnostics) if runtime is not None else None
            accepted = deepcopy(self.accepted.get(session_id))
            observations = {"events": capture.events, "diagnostics": diagnostics,
                "inputView": capture.input_view,
                "runtimeAfterFailure": _snapshot(runtime) if error is not None else None,
                "acceptedCoverage": (accepted or {}).get("state", {}).get("explorationCoverage"),
                "effectivePlan": diagnostics.get("effective_question_plan") if diagnostics else None,
                "terminalAssessment": diagnostics.get("assessor_assessment") if diagnostics else None}
            return {"publicResponse": _json(response), "acceptedState": accepted,
                    "observations": observations, "error": error,
                    "actualModelResponses": capture.model_responses,
                    "actualHttpResponses": capture.received_count,
                    "actualPhysicalAttempts": capture.physical_count,
                    "actualLogicalInvocations": capture.logical_count,
                    "responseReceiptUnknown": capture.receipt_unknown,
                    "executionKind": "SDK_TRANSPORT_FAKE" if fake_transport is not None else "PROVIDER"}

    async def close(self, session_id):
        await self.product.close_agent_cbt_session(session_id, registry=self.registry)
        self.accepted.pop(session_id, None)

    async def close_all(self):
        for session_id in list(self.registry._sessions):
            await self.close(session_id)


async def invoke(public_request, *, observer, fake_transport=None):
    global _DEFAULT
    if _DEFAULT is None:
        _DEFAULT = Q10Adapter()
    return await _DEFAULT.invoke(public_request, observer=observer, fake_transport=fake_transport)


async def close(session_id):
    if _DEFAULT is not None:
        await _DEFAULT.close(session_id)
