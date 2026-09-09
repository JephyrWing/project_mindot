"""Formal collection state-machine kernel, tested with injected fake workers.

No CLI, credentials, product imports or real process launcher are provided here.
The factory contract is a connection point, not evidence of live readiness.
"""
import asyncio
import base64
from copy import deepcopy
import inspect
import json
from pathlib import Path
import time
from uuid import uuid5

import input_plan
from journal import BudgetExceeded, IntegrityError, Journal, TokenBudget, verify_lock
import neutral_export


def _check(condition, reason):
    if not condition:
        raise IntegrityError(reason)


def _consume_task(task):
    if not task.cancelled():
        task.exception()


class FormalCollector:
    """At most one independent case worker per version, two total.

    Required factory: async create(version, source_root, case_key, worker_id);
    async reap(worker_id)->bool. Worker: invoke(request, observer=sync_callback),
    remove(session_id), shutdown()->{'terminated': True, 'workerId': same_id}.
    Its version/source_root/worker_id/process_isolated attributes are verified.
    """
    def __init__(self, *, journal_path, factory, policy, schedule, catalogs):
        self.journal = Journal(journal_path)
        self.factory, self.policy = factory, deepcopy(policy)
        self.schedule, self.catalogs = deepcopy(schedule), catalogs
        self.cases = {case["caseKey"]: (case, catalog) for catalog in catalogs
                      for case in catalog["cases"]}
        self.identities = input_plan.IdentityMap()
        self.export_ids = neutral_export.OpaqueMap()
        self.budget = TokenBudget(generic_cap=policy["physicalGenericAttemptCap"],
                                  moderation_cap=policy["moderationCap"], token_cap=policy["tokenCap"])
        self.halted = None
        self.failure = None
        self._request_counts = {version: 0 for version in input_plan.VERSIONS}
        self._logical_counts = {version: 0 for version in input_plan.VERSIONS}
        self._terminated = set()
        self._last_progress = time.monotonic()
        self._restore_accounting()

    def _append(self, kind, payload):
        try:
            result = self.journal.append(kind, payload)
            self._last_progress = time.monotonic()
            return result
        except BaseException as exc:
            self.failure = exc
            self.halted = "EVALUATION_INVALID"
            raise

    def _restore_accounting(self):
        for event in self.journal.rows:
            kind, payload = event["kind"], event["payload"]
            if kind == "budget_reserved":
                self.budget.reserve(payload["invocationId"], payload["inputEstimate"], payload["outputReservation"])
            elif kind == "budget_usage":
                self.budget.settle(payload["invocationId"], payload["usage"])
            elif kind == "budget_moderation":
                self.budget.moderation()
            elif kind == "attempt_start":
                self._request_counts[payload["version"]] += 1
            elif kind == "logical_count":
                self._logical_counts[payload["version"]] += 1
            elif kind == "export_mapping":
                self.export_ids = neutral_export.OpaqueMap(payload["records"])
            elif kind == "turn_result":
                self.export_ids = neutral_export.OpaqueMap(payload["exported"]["fullOnly"]["referenceMap"])
            elif kind == "worker_terminated":
                self._terminated.add(payload["workerId"])

    def _validate_start(self):
        mode = self.policy.get("mode")
        factory_mode = ("LIVE" if getattr(self.factory, "execution_kind", None) == "LIVE_LOCKED_PROVIDER"
                        else getattr(self.factory, "mode", None))
        _check(mode in {"SDK_FAKE", "LIVE"} and factory_mode == mode, "worker_mode_mismatch")
        if mode == "LIVE":
            self.validate_live_gate(self.policy.get("gateReceipt", {}), self.policy.get("gateReceiptSha256"))
            _check(getattr(self.factory, "execution_kind", None) == "LIVE_LOCKED_PROVIDER" and self.policy.get("fileLocks"),
                   "LIVE_LAUNCHER_NOT_IMPLEMENTED_OR_VERIFIED")
        _check(self.policy.get("seed") == input_plan.SEED, "schedule_seed_mismatch")
        _check(self.policy.get("scheduleSha256") == input_plan.sha(self.schedule["schedule"]), "schedule_lock_mismatch")
        _check(set(self.policy["sourceRoots"]) == set(input_plan.VERSIONS), "source_roots_required")
        _check(self.policy["q10OutputReservation"] == 16384, "q10_reservation_policy_unlocked")
        _check(self.policy.get("q10InputEstimateMethod") == "UTF8_BYTES_CONSERVATIVE", "q10_estimate_method_unlocked")
        _check(self.policy.get("noProgressDeadlineSeconds", 0) > 0, "no_progress_deadline_required")
        _check(self.policy["maxConcurrency"] == 2 and self.policy["maxConcurrencyPerVersion"] == 1, "concurrency_policy_mismatch")
        verify_lock(self.policy.get("fileLocks", []))
        run_config = {"policy": self.policy, "scheduleSha256": self.schedule["scheduleSha256"]}
        prior = [row["payload"] for row in self.journal.rows if row["kind"] == "collection_start"]
        if prior:
            _check(prior == [run_config], "collection_restart_lock_changed")
        else:
            self._append("collection_start", run_config)

    @staticmethod
    def validate_live_gate(gate_receipt, expected_sha256):
        """Read-only future launcher gate: no key, environment or worker access."""
        _check(input_plan.sha(gate_receipt) == expected_sha256, "formal_gate_hash_mismatch")
        _check(gate_receipt.get("status") == "HOLDOUT_VERIFIED" and
               gate_receipt.get("formalLocksComplete") is True and gate_receipt.get("holdoutVerified") is True,
               "formal_gate_not_verified")

    def _case_events(self, case_key, version):
        return [row for row in self.journal.rows if row["payload"].get("caseKey") == case_key and row["payload"].get("version") == version]

    async def _recover(self, case_key, version):
        rows = self._case_events(case_key, version)
        terminal = [row for row in rows if row["kind"] == "case_end"]
        if terminal:
            return True
        if not rows:
            return False
        terminated = {row["payload"]["workerId"] for row in rows if row["kind"] == "worker_terminated"}
        for row in rows:
            if row["kind"] == "worker_start" and row["payload"]["workerId"] not in terminated:
                worker_id = row["payload"]["workerId"]
                _check(await self._reap(worker_id), "orphan_worker_termination_unconfirmed")
                self._append("worker_terminated", {"caseKey": case_key, "version": version,
                             "workerId": worker_id, "reason": "RESTART_REAP_CONFIRMED"})
        turns = [row for row in rows if row["kind"] == "turn_result"]
        has_public = any(row["payload"]["result"].get("publicResponse") is not None for row in turns)
        # No portable exact Q10/Q11 process-state restoration is promised.
        self._append("case_end", {"caseKey": case_key, "version": version,
            "status": "PARTIAL" if has_public else "FAILED", "reason": "INTERRUPTED_NO_REPLAY",
            "selectedCaseAttempt": turns[-1]["payload"]["caseAttempt"] if turns else 0})
        return True

    async def _reap(self, worker_id):
        task = asyncio.create_task(self.factory.reap(worker_id))
        task.add_done_callback(_consume_task)
        done, _ = await asyncio.wait({task}, timeout=self.policy["terminationGraceSeconds"])
        if not done:
            task.cancel()
            return False
        return task.result() is True

    async def _shutdown(self, worker, linkage):
        if worker.worker_id in self._terminated:
            return
        task = asyncio.create_task(worker.shutdown())
        task.add_done_callback(_consume_task)
        done, _ = await asyncio.wait({task}, timeout=self.policy["terminationGraceSeconds"])
        if not done:
            task.cancel()
            raise IntegrityError("worker_shutdown_timeout_no_next_dispatch")
        receipt = task.result()
        _check(isinstance(receipt, dict) and receipt.get("terminated") is True and
               receipt.get("workerId") == worker.worker_id, "worker_termination_unconfirmed")
        self._append("worker_terminated", {**linkage, "workerId": worker.worker_id, "reason": "SHUTDOWN_CONFIRMED"})
        self._terminated.add(worker.worker_id)

    def _observer(self, linkage, version):
        facts = {"dispatch": {}, "raw": set(), "modelResponses": 0, "unknown": False,
                 "logicalIds": set(), "moderationIds": set()}

        def observe(kind, original):
            _check(self.halted is None and self.failure is None, "collection_already_halted")
            payload = {**deepcopy(original), **linkage}
            identity = payload.get("invocationId")
            moderation = payload.get("moderation") is True or payload.get("component") == "moderation"
            try:
                if kind == "logical_dispatch" or (kind == "provider_dispatch" and version == "Q11" and not moderation):
                    logical = payload.get("logicalInvocationId", identity)
                    _check(logical is not None and logical not in facts["logicalIds"], "duplicate_logical_dispatch")
                    facts["logicalIds"].add(logical)
                    self._logical_counts[version] += 1
                    _check(self._logical_counts[version] <= self.policy["logicalGenericCaps"][version], "logical_run_cap_exceeded")
                    self._append("logical_count", {**linkage, "logicalInvocationId": logical})
                if kind == "provider_dispatch":
                    _check(identity is not None and identity not in facts["dispatch"], "duplicate_physical_dispatch")
                    facts["dispatch"][identity] = deepcopy(payload)
                    if moderation:
                        _check(version == "Q11" and not facts["moderationIds"], "unexpected_moderation_attempt")
                        self.budget.moderation()
                        facts["moderationIds"].add(identity)
                        self._append("budget_moderation", {**linkage, "invocationId": identity})
                    else:
                        body = payload.get("requestBody") if version == "Q10" else payload.get("providerRequest")
                        _check(isinstance(body, dict), "actual_provider_body_missing")
                        if version == "Q10":
                            estimate = len(input_plan.canonical(body))  # Conservative estimate, never an input cap.
                        else:
                            capacity = payload.get("capacity") or {}
                            estimate = capacity.get("serializedInputTokensWithFramingReserve")
                            _check(type(estimate) is int and estimate > 0, "q11_measured_input_estimate_missing")
                        output = self.policy["q10OutputReservation"] if version == "Q10" else body.get("max_tokens", body.get("max_completion_tokens"))
                        _check(type(output) is int and output > 0, "output_reservation_unknown")
                        self.budget.reserve(identity, estimate, output)
                        self._append("budget_reserved", {**linkage, "invocationId": identity,
                                     "inputEstimate": estimate, "outputReservation": output})
                if kind == "provider_raw":
                    _check(identity in facts["dispatch"] and identity not in facts["raw"], "unbound_or_duplicate_provider_raw")
                # The durable raw append MUST happen before decoding/usage.
                self._append(kind, payload)
                if kind == "provider_raw":
                    facts["raw"].add(identity)
                    status = payload.get("statusCode", payload.get("httpStatus"))
                    if type(status) is int and 200 <= status < 300 and identity not in facts["moderationIds"]:
                        facts["modelResponses"] += 1
                    encoded = payload.get("responseBodyBase64", payload.get("bodyBase64"))
                    _check(isinstance(encoded, str), "provider_raw_bytes_missing")
                    try:
                        raw = base64.b64decode(encoded, validate=True)
                    except ValueError as exc:
                        raise IntegrityError("provider_raw_base64_invalid") from exc
                    try:
                        def unique_pairs(items):
                            value = {}
                            for key, item in items:
                                if key in value:
                                    raise ValueError("duplicate_json_key")
                                value[key] = item
                            return value
                        parsed = json.loads(raw, object_pairs_hook=unique_pairs,
                            parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite_json")))
                    except (ValueError, UnicodeError):
                        parsed = None
                    usage = parsed.get("usage") if isinstance(parsed, dict) else None
                    if identity not in facts["moderationIds"]:
                        self.budget.settle(identity, usage)
                        self._append("budget_usage", {**linkage, "invocationId": identity, "usage": usage})
                if kind in {"provider_error", "provider_transport_error"}:
                    if payload.get("providerExecution") == "UNKNOWN" or payload.get("responseReceipt") == "UNKNOWN":
                        facts["unknown"] = True
            except BaseException as exc:
                self.failure = exc
                self.halted = "COLLECTION_BUDGET_EXHAUSTED" if isinstance(exc, BudgetExceeded) else "EVALUATION_INVALID"
                raise
        return observe, facts

    async def _invoke(self, worker, request, linkage, timeout):
        version = linkage["version"]
        _check(self._request_counts[version] < self.policy["productRequestCaps"][version], "product_request_run_cap_exceeded")
        self._request_counts[version] += 1
        self._append("attempt_start", {**linkage, "publicRequest": deepcopy(request)})
        observe, facts = self._observer(linkage, version)
        task = asyncio.create_task(worker.invoke(deepcopy(request), observer=observe))
        task.add_done_callback(_consume_task)
        try:
            done, _ = await asyncio.wait({task}, timeout=max(0, timeout))
        except asyncio.CancelledError:
            task.cancel()
            raise
        if not done:
            task.cancel()
            await self._shutdown(worker, linkage)
            # Termination confirmation, not cancellation alone, permits progress.
            result = {"publicResponse": None, "acceptedState": None, "observations": {},
                      "error": {"type": "RequestDeadline", "retryClass": "NONE"},
                      "actualModelResponses": facts["modelResponses"], "responseReceiptUnknown": True}
        else:
            try:
                result = task.result()
            except BaseException as exc:
                if self.failure is not None:
                    raise self.failure
                result = {"publicResponse": None, "acceptedState": None, "observations": {},
                          "error": {"type": type(exc).__name__, "retryClass": "NONE"},
                          "actualModelResponses": facts["modelResponses"], "responseReceiptUnknown": True}
        if self.failure is not None:
            raise self.failure
        _check(isinstance(result, dict), "worker_result_missing")
        _check(type(result.get("actualModelResponses")) is int and
               result["actualModelResponses"] == facts["modelResponses"], "unrecorded_or_miscounted_model_response")
        uncertain = (facts["unknown"] or bool(set(facts["dispatch"]) - facts["raw"]) or
                     result.get("responseReceiptUnknown") is True)
        if result.get("error") is not None and result.get("responseReceiptUnknown") is not False and not facts["dispatch"]:
            uncertain = True
        result["collectorReceiptUnknown"] = uncertain
        self._append("attempt_end", {**linkage, "error": result.get("error"),
                     "actualModelResponses": result["actualModelResponses"], "uncertain": uncertain})
        return result

    @staticmethod
    def retry_allowed(result, *, case_attempt, turn_index, prior_public_count):
        error = result.get("error") or {}
        return (case_attempt == 0 and turn_index == 0 and prior_public_count == 0 and
                result.get("publicResponse") is None and result.get("actualModelResponses") == 0 and
                result.get("acceptedState") is None and
                result.get("collectorReceiptUnknown") is False and
                error.get("retryClass") in {"TRANSPORT_NO_MODEL_RESPONSE", "COLLECTION_NO_RESPONSE"})

    async def _case(self, scheduled, version):
        case_key = scheduled["caseKey"]
        linkage = {"caseKey": case_key, "version": version}
        if await self._recover(case_key, version):
            return
        case, catalog = self.cases[case_key]
        self._append("case_start", linkage)
        for case_attempt in range(2):
            if self.halted is not None:
                return
            worker_id = str(uuid5(input_plan.NAMESPACE, case_key + ":" + version + ":worker:" + str(case_attempt)))
            source = str(Path(self.policy["sourceRoots"][version]).resolve())
            worker_link = {**linkage, "caseAttempt": case_attempt, "workerId": worker_id}
            self._append("worker_start", {**worker_link, "sourceRoot": source})
            startup = asyncio.create_task(self.factory.create(version, source, case_key, worker_id))
            startup.add_done_callback(_consume_task)
            try:
                done, _ = await asyncio.wait({startup}, timeout=self.policy["workerStartTimeoutSeconds"])
            except asyncio.CancelledError:
                startup.cancel()
                raise
            if not done:
                startup.cancel()
                _check(await self._reap(worker_id), "startup_timeout_orphan_unconfirmed")
                raise IntegrityError("worker_startup_timeout")
            worker = startup.result()
            _check(worker.version == version and str(Path(worker.source_root).resolve()) == source and
                   worker.worker_id == worker_id and worker.process_isolated is True, "worker_source_or_isolation_mismatch")
            request = input_plan.initial_request(case, version, catalog, self.identities)
            started = time.monotonic()
            long_deadline = case["maxTurns"] * self.policy["requestDeadlineSeconds"] + self.policy["cleanupMarginSeconds"]
            prior_public = 0
            retry = False
            reason, status = "MAX_TURNS_REACHED", "COMPLETE"
            try:
                for turn_index in range(case["maxTurns"]):
                    attempt_id = str(uuid5(input_plan.NAMESPACE, case_key + ":" + version + ":" + str(case_attempt) + ":" + str(turn_index)))
                    turn_link = {**worker_link, "turnIndex": turn_index, "attemptId": attempt_id}
                    remaining = long_deadline - (time.monotonic() - started)
                    result = await self._invoke(worker, request, turn_link,
                                                min(self.policy["requestDeadlineSeconds"], remaining))
                    exported = neutral_export.export_turn(implementation=version,
                        anonymous_version=scheduled["anonymousVersions"][version], case_key=case_key,
                        response_key=request["requestId"], turn_index=turn_index, public_request=request,
                        public_response=result.get("publicResponse"), accepted_state=result.get("acceptedState"),
                        observations=result.get("observations"), grading_context=case["gradingContext"], id_map=self.export_ids)
                    self._append("turn_result", {**turn_link, "result": result, "exported": exported})
                    self._append("export_mapping", {"records": self.export_ids.records()})
                    public = result.get("publicResponse")
                    if result.get("error") is not None or public is None:
                        retry = self.retry_allowed(result, case_attempt=case_attempt,
                                                   turn_index=turn_index, prior_public_count=prior_public)
                        status = "PARTIAL" if prior_public else "FAILED"
                        reason = "PRODUCT_OR_TRANSPORT_ERROR"
                        break
                    prior_public += 1
                    if case["kind"] == "single":
                        reason = "SINGLE_RESPONSE_COLLECTED"
                        break
                    try:
                        next_request = input_plan.followup_request(case, version, request, public, turn_index + 1,
                            self.identities, asked_at=worker.timestamp(), answered_at=worker.timestamp())
                    except input_plan.InputPlanError:
                        status, reason = "PARTIAL", "UNUSABLE_PRODUCT_QUESTION_NO_REPLAY"
                        break
                    if next_request is None:
                        reason = "TERMINAL_OR_MAX_TURNS"
                        break
                    if input_plan.remove_before_next(case, turn_index):
                        await worker.remove(request["sessionId"])
                        self._append("runtime_removed", {**turn_link, "sessionId": request["sessionId"]})
                    request = next_request
            finally:
                await self._shutdown(worker, worker_link)
            if retry:
                self._append("case_retry", {**linkage, "fromCaseAttempt": case_attempt,
                                           "toCaseAttempt": case_attempt + 1, "reason": "CERTAIN_NO_MODEL_RESPONSE_FIRST_REQUEST"})
                continue
            self._append("case_end", {**linkage, "status": status, "reason": reason,
                                      "selectedCaseAttempt": case_attempt})
            return

    async def run(self):
        self._validate_start()
        started = self._last_progress = time.monotonic()
        try:
            for scheduled in self.schedule["schedule"]:
                if self.halted is not None:
                    break
                _check(time.monotonic() - started < self.policy["wholeRunDeadlineSeconds"], "whole_run_watchdog")
                # Pair-local independent workers preserve the planned enqueue
                # order while bounding concurrency without a hidden task backlog.
                tasks = [asyncio.create_task(self._case(scheduled, version)) for version in scheduled["versionOrder"]]
                pending = set(tasks)
                try:
                    while pending:
                        whole_remaining = self.policy["wholeRunDeadlineSeconds"] - (time.monotonic() - started)
                        progress_remaining = self.policy["noProgressDeadlineSeconds"] - (time.monotonic() - self._last_progress)
                        _check(whole_remaining > 0, "whole_run_watchdog")
                        _check(progress_remaining > 0, "no_progress_watchdog")
                        done, pending = await asyncio.wait(pending,
                            timeout=min(1.0, whole_remaining, progress_remaining), return_when=asyncio.FIRST_COMPLETED)
                        errors = [task.exception() for task in done if not task.cancelled() and task.exception() is not None]
                        if errors:
                            raise errors[0]
                        if any(task.cancelled() for task in done):
                            raise asyncio.CancelledError()
                except BaseException:
                    self.halted = self.halted or "EVALUATION_INVALID"
                    for task in pending:
                        task.cancel()
                    remaining = set()
                    if pending:
                        completed, remaining = await asyncio.wait(pending, timeout=self.policy["terminationGraceSeconds"] + 1)
                        for task in completed:
                            if not task.cancelled():
                                task.exception()  # Consume errors after the original failure is retained.
                    # Includes startup/source-check failures whose task has
                    # already ended: a done Python task is not process exit.
                    for event in list(self.journal.rows):
                        payload = event["payload"]
                        if event["kind"] == "worker_start" and payload["workerId"] not in self._terminated:
                            _check(await self._reap(payload["workerId"]), "watchdog_worker_termination_unconfirmed")
                            self._append("worker_terminated", {**payload, "reason": "WATCHDOG_REAP_CONFIRMED"})
                            self._terminated.add(payload["workerId"])
                    for task in remaining:
                        task.cancel()
                    raise
        except BaseException as exc:
            self.halted = self.halted or "EVALUATION_INVALID"
            self.failure = self.failure or exc
        return self.summary()

    def summary(self):
        rows = []
        for scheduled in self.schedule["schedule"]:
            for version in input_plan.VERSIONS:
                events = self._case_events(scheduled["caseKey"], version)
                terminal = [event["payload"] for event in events if event["kind"] == "case_end"]
                rows.append(terminal[-1] if terminal else {"caseKey": scheduled["caseKey"], "version": version,
                    "status": ("PARTIAL" if any(event["kind"] == "turn_result" for event in events) else
                               "FAILED" if any(event["kind"] == "attempt_start" for event in events) else "NOT_RUN"),
                    "reason": self.halted or "NOT_DISPATCHED"})
        return {"collectionState": self.halted or "PLANNED_CASE_RECORDS_COLLECTED", "mode": self.policy["mode"],
                "caseVersionRecords": rows, "productRequests": deepcopy(self._request_counts),
                "logicalInvocations": deepcopy(self._logical_counts), "physicalReservations": len(self.budget.tickets),
                "moderationCalls": self.budget.moderations, "tokenChargeObservedOrReserved": self.budget.charged(),
                "errorType": type(self.failure).__name__ if self.failure else None,
                "liveLauncherOwnedByCaller": self.policy["mode"] == "LIVE"}
