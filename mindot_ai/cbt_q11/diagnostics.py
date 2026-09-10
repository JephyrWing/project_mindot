"""Per-request opt-in raw observations; never log user text to application logs."""
from dataclasses import dataclass, field
from time import perf_counter
from typing import Any, Callable
import hashlib
import json
from contextvars import ContextVar

# Opt-in, request-scoped diagnostics through the unchanged public facade.
diagnostic_sink = ContextVar('cbt_diagnostic_sink', default=None)


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def sha(value: str) -> str:
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


@dataclass
class Diagnostics:
    sink: Callable[[dict], None] | None = field(default_factory=lambda: diagnostic_sink.get())
    events: list[dict] = field(default_factory=list)
    counters: dict[str, int] = field(default_factory=dict)
    started: float = field(default_factory=perf_counter)
    accepted_coverage: dict = field(default_factory=dict)
    effective_plan: dict | None = None
    terminal_assessment: dict | None = None
    safety: dict | None = None
    fact_boundary_gap: dict | None = None
    coverage_review: list[dict] = field(default_factory=list)

    def count(self, key: str) -> None:
        self.counters[key] = self.counters.get(key, 0) + 1

    def emit(self, event: str, **data: Any) -> None:
        row = {'event': event, **data}
        self.events.append(row)
        if self.sink:
            try:
                self.sink(row)
            except Exception:
                # Observability is not the budget gate or accepted-state owner.
                self.count('audit_sink_failure')

    def invalid(self, component: str, field: str, reason: str) -> None:
        self.count('semantic_validation_failure')
        self.emit('validation', component=component, field=field, reason=reason, status='SEMANTIC_INVALID')

    def fallback(self, component: str, reason: str) -> None:
        self.count('fallback')
        self.emit('fallback', component=component, reason=reason)
