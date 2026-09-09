"""Durable raw-first evaluation plumbing. No product decisions or retries."""
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import threading


class IntegrityError(RuntimeError):
    pass


class BudgetExceeded(RuntimeError):
    pass


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False)


def sha(value):
    return hashlib.sha256(value if isinstance(value, bytes) else value.encode('utf-8')).hexdigest()


class Journal:
    """Append-only SHA chain. Torn writes are never silently repaired/replayed."""
    def __init__(self, path):
        self.path = Path(path).resolve()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self.broken = False
        self.rows = self.read(self.path)

    @staticmethod
    def read(path):
        path = Path(path)
        if not path.exists():
            return []
        raw = path.read_bytes()
        if raw and not raw.endswith(b'\n'):
            raise IntegrityError('journal_torn_tail')
        rows = []
        previous = None
        for line in raw.splitlines():
            try:
                row = json.loads(line)
                body = {key: value for key, value in row.items() if key != 'sha256'}
                if (set(row) != {'sequence', 'previousSha256', 'kind', 'payload', 'sha256'} or
                        row['sequence'] != len(rows) or row['previousSha256'] != previous or
                        row['sha256'] != sha(canonical(body))):
                    raise IntegrityError('journal_chain_mismatch')
            except (ValueError, TypeError, KeyError) as exc:
                raise IntegrityError('journal_invalid_row') from exc
            rows.append(row)
            previous = row['sha256']
        return rows

    def append(self, kind, payload):
        with self._lock:
            if self.broken:
                raise IntegrityError('journal_write_failure_latched')
            row = {'sequence': len(self.rows), 'previousSha256': self.rows[-1]['sha256'] if self.rows else None,
                   'kind': kind, 'payload': deepcopy(payload)}
            row['sha256'] = sha(canonical(row))
            try:
                with self.path.open('ab') as output:
                    output.write((canonical(row) + '\n').encode('utf-8'))
                    output.flush()
                    os.fsync(output.fileno())
            except BaseException:
                self.broken = True
                raise
            self.rows.append(row)
            return deepcopy(row)

    def attempts(self):
        """Unknown dispatches are non-repeatable, not zero-response successes."""
        states = {}
        for row in self.rows:
            payload = row['payload']
            identity = payload.get('attemptId')
            if identity is None:
                continue
            current = states.setdefault(identity, {'started': False, 'completed': False,
                'dispatches': [], 'responses': [], 'uncertain': False})
            if row['kind'] == 'attempt_start':
                if current['started']:
                    raise IntegrityError('duplicate_attempt_start')
                current['started'] = True
            elif row['kind'] == 'provider_dispatch':
                current['dispatches'].append(payload['invocationId'])
            elif row['kind'] == 'provider_raw':
                current['responses'].append(payload['invocationId'])
            elif row['kind'] == 'attempt_end':
                if current['completed']:
                    raise IntegrityError('duplicate_attempt_end')
                current['completed'] = True
        for current in states.values():
            current['uncertain'] = bool(set(current['dispatches']) - set(current['responses']))
            current['repeatAllowed'] = False  # Canary has zero retries; formal policy is separate.
        return states


class TokenBudget:
    """Reserve before dispatch; unknown usage remains charged at reservation."""
    def __init__(self, *, generic_cap, moderation_cap, token_cap):
        self.generic_cap = generic_cap
        self.moderation_cap = moderation_cap
        self.token_cap = token_cap
        self.tickets = {}
        self.moderations = 0

    def charged(self):
        return sum(row['charged'] for row in self.tickets.values())

    def reserve(self, identity, estimate, output_cap):
        if identity in self.tickets:
            raise IntegrityError('duplicate_invocation_identity')
        if any(type(value) is not int or value < 0 for value in (estimate, output_cap)):
            raise IntegrityError('invalid_token_reservation')
        reservation = estimate + output_cap
        if len(self.tickets) >= self.generic_cap or self.charged() + reservation > self.token_cap:
            raise BudgetExceeded('run_generation_or_token_cap')
        self.tickets[identity] = {'reservation': reservation, 'charged': reservation,
                                  'usage': None, 'settled': False}
        return reservation

    def moderation(self):
        if self.moderations >= self.moderation_cap:
            raise BudgetExceeded('run_moderation_cap')
        self.moderations += 1

    def settle(self, identity, usage):
        ticket = self.tickets[identity]
        if ticket['settled']:
            raise IntegrityError('duplicate_usage_settlement')
        ticket['settled'] = True
        ticket['usage'] = deepcopy(usage)
        if isinstance(usage, dict):
            keys = ('prompt_tokens', 'completion_tokens') if 'prompt_tokens' in usage else ('input_tokens', 'output_tokens')
            if all(type(usage.get(key)) is int and usage[key] >= 0 for key in keys):
                ticket['charged'] = sum(usage[key] for key in keys)
        if self.charged() > self.token_cap:
            raise BudgetExceeded('observed_usage_exceeds_run_cap')


def verify_lock(rows):
    """Check fixed explicit paths; never enumerate secrets or broaden a lock."""
    for row in rows:
        data = Path(row['path']).read_bytes()
        if len(data) != row['bytes'] or sha(data) != row['sha256']:
            raise IntegrityError('locked_file_changed:' + Path(row['path']).name)
