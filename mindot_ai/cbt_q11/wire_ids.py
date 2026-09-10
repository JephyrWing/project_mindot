"""Lossless, request-local provider aliases; no clinical text transformation.

Canonical state and received raw model bytes are outside this codec. Callers
retain the original schema and validate decoded arguments against it. Aliases
are transport identities, never evidence, semantic normalization, or a retry.
This module deliberately depends only on the Python standard library.
"""
from copy import deepcopy
import json
import re


class IdentityCodecError(ValueError):
    pass


# Only typed metadata positions are eligible. In particular, ``Code`` alone
# does not mean identity: distortion, emotion, move, and status codes are facts
# about the contract, not opaque addresses.
_IDENTITY_FIELDS = frozenset({
    'id', 'address', 'revision', 'revisions', 'hash', 'digest',
    'questionCode', 'questionCodes', 'publicQuestionCode', 'publicQuestionCodes',
    'sourceQuestionCode', 'sourceQuestionCodes', 'parentQuestionCode',
    'rootQuestionCode', 'rootGapQuestionCode', 'targetQuestionCode',
    'semanticPredecessor', 'eventDependency', 'outputRefs', 'analyses',
})
_IDENTITY_SUFFIXES = ('Id', 'Ids', 'Key', 'Keys', 'Revision', 'Revisions',
                      'Hash', 'Hashes', 'Digest', 'Digests', 'Sha256')
_CONTRACT_FIELDS = frozenset({'analysisContractRevision', 'contractRevision',
    'schemaRevision', 'promptVersion', 'modelVersion'})
_TEXT_FIELDS = frozenset({'text', 'exactExcerpt', 'question', 'answer',
    'situation', 'automaticThought', 'focus', 'reason', 'description', 'title',
    'preface', 'optionA', 'optionB', 'explanation', 'content', 'rawArguments',
    'arguments_json', 'wireArguments'})
# Object keys in these catalogs are canonical identities, not field names.
# Fixed slot/episode keys and semantic category keys are deliberately excluded.
_KEYED_CATALOGS = frozenset({'goals', 'questionTargets', 'pendingInteractions', 'priorRequests',
    'requestClarifications', 'semanticAnalyses', 'reviewRequiredReasons',
    'presentationRequests', 'presentationReceipts', 'historyRecovery',
    'gapUsage', 'outputLineages', 'events'})
_VALUE_CATALOGS = frozenset({'textSources'})
_STABLE_SLOT = re.compile(r'^(?:s[0-9]{3}|slot_[0-9]{3}(?::signal_[0-9]+)?|episode_[0-9]{3})$')


def _identity_field(name):
    return (isinstance(name, str) and name not in _CONTRACT_FIELDS and
            name not in _TEXT_FIELDS and
            (name in _IDENTITY_FIELDS or name.endswith(_IDENTITY_SUFFIXES)))


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise IdentityCodecError('duplicate_json_key')
        result[key] = value
    return result


class IdentityCodec:
    """One accumulator per product request, reused across its finite phases.

    Inputs to encode methods are canonical, except received assistant messages
    which are copied byte-for-byte. Unknown output values are not guessed or
    coerced; the caller's original-schema validator still decides validity.
    """
    def __init__(self):
        self._aliases = {}
        self._canonical = {}
        self._seen = set()
        self._observed = []
        self._counter = 0

    def mapping(self):
        """Full-only diagnostic export: alias -> exact original identity."""
        return dict(self._canonical)

    def _observe(self, value):
        if not isinstance(value, str):
            return
        # A later canonical identity must not masquerade as an alias already
        # emitted in an earlier phase. Raw USER text is never observed here.
        if value in self._canonical and value not in self._aliases:
            raise IdentityCodecError('identity_alias_namespace_collision')
        if value not in self._seen:
            self._seen.add(value)
            self._observed.append(value)

    def _allocate_seen(self):
        # Complete the discovery pass before transforming either object keys
        # or values; identity-bearing catalogs may precede their definitions.
        for value in self._observed:
            self._alias(value)

    def _alias(self, value):
        if not isinstance(value, str):
            return deepcopy(value)
        if value in self._aliases:
            return self._aliases[value]
        if len(value) <= 8 or _STABLE_SLOT.fullmatch(value):
            return value
        while True:
            index = self._counter
            self._counter += 1
            digits = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'
            suffix = ''
            while True:
                suffix = digits[index % 36] + suffix
                index //= 36
                if not index:
                    break
            alias = 'WI' + suffix
            if alias not in self._seen and alias not in self._canonical:
                break
        self._aliases[value] = alias
        self._canonical[alias] = value
        return alias

    def _scan_value(self, value, field=None, *, identity=False):
        if field in _TEXT_FIELDS or field in _CONTRACT_FIELDS:
            return
        identity = identity or _identity_field(field)
        if isinstance(value, str):
            if identity:
                self._observe(value)
        elif isinstance(value, list):
            for item in value:
                self._scan_value(item, None if field in _KEYED_CATALOGS else field, identity=identity)
        elif isinstance(value, dict):
            for key, item in value.items():
                if field in _KEYED_CATALOGS:
                    self._observe(key)
                    self._scan_value(item)
                elif field in _VALUE_CATALOGS:
                    self._scan_value(item, identity=True)
                else:
                    self._scan_value(item, key)

    def _value(self, value, field=None, *, identity=False):
        if field in _TEXT_FIELDS or field in _CONTRACT_FIELDS:
            return deepcopy(value)
        identity = identity or _identity_field(field)
        if isinstance(value, str):
            return self._alias(value) if identity else value
        if isinstance(value, list):
            return [self._value(item, None if field in _KEYED_CATALOGS else field, identity=identity) for item in value]
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                mapped = self._alias(key) if field in _KEYED_CATALOGS or key in self._aliases else key
                if mapped in result:
                    raise IdentityCodecError('identity_object_key_collision')
                result[mapped] = (self._value(item) if field in _KEYED_CATALOGS else
                    self._value(item, identity=True) if field in _VALUE_CATALOGS else
                    self._value(item, key))
            return result
        return deepcopy(value)

    def encode_value(self, payload):
        self._scan_value(payload)
        self._allocate_seen()
        return self._value(payload)

    def decode_value(self, payload):
        """Typed inverse for a provider view; never use as output validation."""
        def restore(value, field=None, *, identity=False):
            if field in _TEXT_FIELDS or field in _CONTRACT_FIELDS:
                return deepcopy(value)
            identity = identity or _identity_field(field)
            if isinstance(value, str):
                return self._canonical.get(value, value) if identity else value
            if isinstance(value, list):
                return [restore(item, None if field in _KEYED_CATALOGS else field, identity=identity) for item in value]
            if isinstance(value, dict):
                result = {}
                for key, item in value.items():
                    actual = self._canonical.get(key, key)
                    if actual in result:
                        raise IdentityCodecError('identity_object_key_collision')
                    result[actual] = (restore(item) if field in _KEYED_CATALOGS else
                        restore(item, identity=True) if field in _VALUE_CATALOGS else
                        restore(item, actual))
                return result
            return deepcopy(value)
        return restore(payload)

    def encode_messages(self, converted):
        """Encode structured USER/TOOL envelopes, never free text or raw AI."""
        result = deepcopy(converted)
        parsed = {}
        for index, message in enumerate(result):
            if message.get('role') not in ('user', 'tool'):
                continue
            content = message.get('content')
            if not isinstance(content, str):
                continue
            try:
                value = json.loads(content, object_pairs_hook=_unique_object)
            except json.JSONDecodeError:
                continue
            # A plain string or a free USER JSON scalar is not a product view.
            if not isinstance(value, dict):
                continue
            if not any(key in value for key in ('phase', 'sources', 'record',
                    'effectivePlan', 'phaseView', 'sourceValidity')):
                continue
            self._scan_value(value)
            parsed[index] = value
        self._allocate_seen()
        for index, value in parsed.items():
            encoded = self._value(value)
            if encoded != value:
                result[index]['content'] = json.dumps(encoded, ensure_ascii=False,
                    sort_keys=True, separators=(',', ':'))
        return result

    @staticmethod
    def _reference(schema, root):
        ref = schema['$ref']
        if not isinstance(ref, str) or not ref.startswith('#/$defs/'):
            raise IdentityCodecError('unsupported_identity_schema_reference')
        name = ref[len('#/$defs/'):]
        if '/' in name or name not in root.get('$defs', {}):
            raise IdentityCodecError('missing_identity_schema_reference')
        return name, root['$defs'][name]

    def _schema_contexts(self, schema):
        contexts = {}
        visited = set()
        def visit(node, identity=False):
            if not isinstance(node, dict):
                return
            if '$ref' in node:
                name, definition = self._reference(node, schema)
                contexts.setdefault(name, set()).add(identity)
                if (name, identity) not in visited:
                    visited.add((name, identity))
                    visit(definition, identity)
            if identity:
                for value in node.get('enum', []):
                    self._observe(value)
                if 'const' in node:
                    self._observe(node['const'])
            for key, child in node.get('properties', {}).items():
                visit(child, _identity_field(key))
            for keyword in ('anyOf', 'oneOf', 'allOf'):
                for child in node.get(keyword, []):
                    visit(child, identity)
            if isinstance(node.get('items'), dict):
                visit(node['items'], identity)
        visit(schema)
        # Retain and safely transform even unreachable caller definitions.
        # They are not an opportunity to prune or alter the source contract.
        for name, definition in schema.get('$defs', {}).items():
            if name not in contexts:
                contexts[name] = {False}
                visited.add((name, False))
                visit(definition)
        return contexts

    def encode_schema(self, schema):
        """Preserve fields, semantic enums, bounds and structural sharing.

        A shared nullable enum can be used as both an ID and semantic text.
        Specialize that definition by context instead of changing text enums
        or expanding every reference back into a large inline schema.
        """
        contexts = self._schema_contexts(schema)
        self._allocate_seen()
        definitions = schema.get('$defs', {})
        names = {}
        occupied = set(definitions)
        for name in definitions:
            uses = contexts.get(name, {False})
            for identity in sorted(uses):
                chosen = name
                if identity and False in uses:
                    ordinal = 0
                    while 'wire_identity_' + str(ordinal) in occupied:
                        ordinal += 1
                    chosen = 'wire_identity_' + str(ordinal)
                    occupied.add(chosen)
                names[name, identity] = chosen

        def transform(node, identity=False):
            if not isinstance(node, dict):
                return deepcopy(node)
            result = {}
            for key, value in node.items():
                if key == '$defs':
                    continue
                if key == '$ref':
                    name, _ = self._reference(node, schema)
                    result[key] = '#/$defs/' + names[name, identity]
                elif key == 'properties':
                    result[key] = {field: transform(child, _identity_field(field))
                                   for field, child in value.items()}
                elif key in ('anyOf', 'oneOf', 'allOf'):
                    result[key] = [transform(child, identity) for child in value]
                elif key == 'items' and isinstance(value, dict):
                    result[key] = transform(value, identity)
                elif identity and key == 'enum':
                    result[key] = [self._alias(item) for item in value]
                elif identity and key == 'const':
                    result[key] = self._alias(value)
                else:
                    result[key] = deepcopy(value)
            if identity:
                for value in result.get('enum', []):
                    if isinstance(value, str) and (len(value) < result.get('minLength', 0) or
                            len(value) > result.get('maxLength', float('inf')) or
                            ('pattern' in result and not re.search(result['pattern'], value))):
                        raise IdentityCodecError('identity_alias_violates_schema')
            return result

        result = transform(schema)
        if definitions:
            result['$defs'] = {names[name, identity]: transform(definition, identity)
                for name, definition in definitions.items()
                for identity in sorted(contexts.get(name, {False}))}
        return result

    def encode_tools(self, tools):
        # Observe all schemas first so a short canonical code in a later tool
        # cannot collide with an alias allocated to an earlier tool's enum.
        for tool in tools:
            self._schema_contexts(tool['function']['parameters'])
        result = deepcopy(tools)
        for tool in result:
            tool['function']['parameters'] = self.encode_schema(tool['function']['parameters'])
        return result

    def _shape_matches(self, value, schema, root, identity=False, seen=None):
        seen = set() if seen is None else set(seen)
        if '$ref' in schema:
            name, definition = self._reference(schema, root)
            if name in seen:
                return True
            return self._shape_matches(value, definition, root, identity, seen | {name})
        if 'anyOf' in schema:
            return any(self._shape_matches(value, branch, root, identity, seen)
                       for branch in schema['anyOf'])
        kind = schema.get('type')
        kinds = {'object': isinstance(value, dict), 'array': isinstance(value, list),
            'string': isinstance(value, str), 'null': value is None,
            'integer': type(value) is int, 'number': type(value) in (int, float),
            'boolean': type(value) is bool}
        if kind and not kinds.get(kind, False):
            return False
        if not identity and ('enum' in schema and value not in schema['enum'] or
                             'const' in schema and value != schema['const']):
            return False
        if isinstance(value, dict):
            if not set(schema.get('required', ())) <= value.keys():
                return False
            return all(self._shape_matches(value[key], child, root,
                _identity_field(key), seen) for key, child in schema.get('properties', {}).items()
                if key in value)
        return True

    def decode_arguments(self, parsed, original_schema):
        """Decode schema-typed ID positions only; do not validate/coerce output.

        In particular, an unknown requestIds alias stays unknown so the
        existing, narrowly permitted binding-repair validator sees the error.
        """
        def decode(value, schema, identity=False, stack=()):
            if '$ref' in schema:
                name, definition = self._reference(schema, original_schema)
                # Recursion is structural: a repeated reference at a child
                # value is legal, but an unproductive direct reference is not.
                marker = (name, id(value))
                if marker in stack:
                    raise IdentityCodecError('cyclic_identity_schema_reference')
                return decode(value, definition, identity, (*stack, marker))
            for union in ('anyOf', 'oneOf'):
                if union in schema:
                    branches = [branch for branch in schema[union]
                        if self._shape_matches(value, branch, original_schema, identity)]
                    if not branches:
                        return deepcopy(value)  # The original validator rejects the shape.
                    results = [decode(value, branch, identity, stack) for branch in branches]
                    if any(result != results[0] for result in results[1:]):
                        raise IdentityCodecError('ambiguous_identity_schema_branch')
                    return results[0]
            if isinstance(value, str):
                return self._canonical.get(value, value) if identity else value
            if isinstance(value, list) and isinstance(schema.get('items'), dict):
                return [decode(item, schema['items'], identity, stack) for item in value]
            if isinstance(value, dict):
                properties = schema.get('properties', {})
                return {key: decode(item, properties[key], _identity_field(key), stack)
                    if key in properties else deepcopy(item) for key, item in value.items()}
            return deepcopy(value)
        return decode(parsed, original_schema)
