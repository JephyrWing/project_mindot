"""PRE_FREEZE: pure exporter; readiness is established by its separate test receipt.

Pure, implementation-neutral projection of already-collected execution facts.

No product imports, provider calls, source reanalysis, grades, or state mutations.
Schema/adapter changes require a new lock before the first live output. This
module is a PRE_FREEZE runner artifact until its own review/gate is recorded.

Collector envelope:
* public_request/public_response are exact aliased public DTO dictionaries.
* Q11 accepted_state is memory.state_data of the successful accepted bundle.
* Q10 accepted_state contains state (aliased CbtSessionState), history,
  pending_question/pending_plan/pending_assessment/pending_assessment_gap and
  unanswered_question_attempts, serialized only after the successful return.
* observations contains actual captured inputView, diagnostics, effectivePlan,
  terminalAssessment, coverageReview, safety, and currentEvidence/currentSupplemental.
  The last two must be the existing product's READ-ONLY accepted-state projection,
  never the historical atoms bag or proposed validation events.
* grading_context is exclusively fixed grading input. Never feed it to products.

Unsupported/missing scalars are null or UNKNOWN, collections are []. Full-only
sourcePresence distinguishes UNSUPPORTED, NOT_OBSERVED, NULL, EMPTY and PRESENT.
Evidence is grouped by source without merging or inventing semantic content.
"""
from copy import deepcopy
from hashlib import sha256
import json
import re


class ExportError(ValueError):
    pass


MISSING = object()
DOMAINS = ('evidenceFor', 'evidenceAgainst', 'alternativeViews', 'acknowledgement')
GAP_WAIT_MESSAGE = '앞서 확인하던 내용에 대한 답변은 아직 없는 상태로 남겨둘게요. 답변 내용을 적거나, 이 질문은 건너뛰겠다고 알려주세요.'
TEXT = {'type': ['string', 'null']}
NUMBER = {'type': ['integer', 'null']}
BOOL = {'type': ['boolean', 'null']}


def obj(properties):
    return {'type': 'object', 'properties': properties, 'required': list(properties),
            'additionalProperties': False}


def array(items):
    return {'type': 'array', 'items': items}


def enum(*values):
    return {'type': 'string', 'enum': list(values)}


POINTER = obj({'sourceRef': TEXT, 'questionRef': TEXT, 'start': NUMBER, 'length': NUMBER, 'excerpt': TEXT})
QUESTION = obj({'questionRef': TEXT, 'questionPurpose': TEXT, 'semanticRouteType': TEXT,
                'question': TEXT, 'answer': TEXT, 'askedAt': TEXT, 'answeredAt': TEXT})
COVERAGE_ITEM = obj({'status': enum('EVIDENCE_FOUND', 'EXPLICITLY_NONE', 'NOT_EXPLORED', 'UNKNOWN'),
                     'questionRefs': array(TEXT), 'grounding': POINTER, 'rejectionReason': TEXT})
COVERAGE = obj({domain: deepcopy(COVERAGE_ITEM) for domain in DOMAINS})
CONTRIBUTION = obj({'referenceId': TEXT, 'start': NUMBER, 'length': NUMBER, 'excerpt': TEXT,
                    'domain': TEXT, 'kind': enum('CONTENT', 'EXPLICIT_NONE', 'UNKNOWN'),
                    'role': TEXT, 'goalRef': TEXT, 'validity': enum('CURRENT', 'UNKNOWN')})
EVIDENCE_SOURCE = obj({'sourceRef': TEXT, 'questionRef': TEXT, 'contributions': array(CONTRIBUTION)})
PLAN = obj({'questionPurpose': TEXT, 'semanticRouteType': TEXT, 'semanticMove': TEXT,
            'questionGoal': TEXT, 'answerTarget': TEXT, 'answerTargetRef': TEXT,
            'answerSource': TEXT, 'prefaceGoal': TEXT, 'exampleOptions': array(TEXT),
            'groundingQuestionRefs': array(TEXT), 'avoidTopics': array(TEXT), 'goalRef': TEXT})
CONSIDERED = obj({'code': TEXT, 'definitionElementChecked': TEXT, 'missingDefinitionElement': TEXT,
                 'whyAutomaticThoughtDoesNotMatch': TEXT})
ASSESSMENT = obj({'assessmentType': TEXT, 'observedFact': TEXT, 'automaticThoughtExcerpt': TEXT,
                  'unsupportedExtension': TEXT, 'matchedDistortionCode': TEXT,
                  'matchedDefinitionElement': TEXT, 'definitionMatchReason': TEXT,
                  'withinFactBoundaryReason': TEXT, 'preservedFact': TEXT, 'calibratedThought': TEXT,
                  'boundaryExplanation': TEXT, 'groundingQuestionRefs': array(TEXT),
                  'usedReferenceIds': array(TEXT), 'consideredCandidates': array(CONSIDERED)})
BOUNDARY = obj({'observedFact': TEXT, 'automaticThoughtExcerpt': TEXT, 'unsupportedExtension': TEXT,
               'preservedFact': TEXT, 'boundaryExplanation': TEXT})
GAP = obj({'questionRef': TEXT, 'goalRef': TEXT, 'question': TEXT, 'missingFact': TEXT,
           'whyDecisionDependsOnIt': TEXT, 'distinctionNeeded': TEXT, 'groundingQuestionRefs': array(TEXT),
           'answerState': enum('NOT_ASKED', 'AWAITING_ANSWER', 'ANSWERED', 'UNKNOWN')})
SAFETY_ITEM = obj({'safetyRef': TEXT, 'concern': TEXT, 'reason': TEXT, 'level': TEXT, 'subject': TEXT,
                   'currentness': TEXT, 'intent': TEXT, 'immediacy': TEXT,
                   'state': enum('UNRESOLVED', 'RESOLVED', 'UNKNOWN'), 'origin': POINTER,
                   'resolutionEvidence': array(POINTER), 'reopeningEvidence': array(POINTER)})
SAFETY_REVIEW = obj({'decision': TEXT, 'clearance': TEXT, 'context': array(POINTER)})
ATTEMPT = obj({'questionRef': TEXT, 'questionPurpose': TEXT, 'semanticRouteType': TEXT,
               'answerTarget': TEXT, 'answerSource': TEXT, 'answerDisposition': TEXT,
               'semanticDimensions': array(obj({'dimension': TEXT, 'value': TEXT}))})
ROUTE_FACT = obj({'questionRef': TEXT, 'questionPurpose': TEXT, 'semanticRouteType': TEXT,
                  'semanticRouteFamily': TEXT, 'question': TEXT,
                  'semanticDimensions': array(obj({'dimension': TEXT, 'value': TEXT}))})
ROUTE_DEFINITION = obj({'route': TEXT, 'family': TEXT, 'definition': TEXT, 'informationSource': TEXT,
                        'forbiddenDirections': array(TEXT), 'allowedQuestionPurposes': array(TEXT),
                        'dimensions': array(obj({'dimension': TEXT, 'value': TEXT}))})
DISTORTION = obj({'code': TEXT, 'reviewStatus': TEXT, 'classifierConfidence': {'type': ['number', 'null']}})
OUTCOME = obj({'evidenceForText': TEXT, 'evidenceAgainstText': TEXT, 'alternativeThoughtText': TEXT,
               'afterDistortions': array(DISTORTION)})
FINAL_RESPONSE = obj({'status': TEXT, 'assessmentType': TEXT, 'nextQuestion': QUESTION,
                      'beforeDistortions': array(DISTORTION), 'outcomeDraft': OUTCOME,
                      'confirmationRequiredFields': array(TEXT), 'acknowledgementEvidence': TEXT,
                      'acknowledgementSourceQuestionRef': TEXT, 'proposalMessage': TEXT,
                      'risk': obj({'level': TEXT, 'reasonCode': TEXT})})
SCHEMA = obj({
    'identity': obj({'case_id': TEXT, 'response_id': TEXT, 'anonymous_version': enum('A', 'B'),
                     'turn_index': NUMBER, 'suite': TEXT, 'caseType': TEXT,
                     'syntheticContext': obj({'situation': TEXT, 'primaryEmotionCode': TEXT,
                                               'contextCategory': TEXT})}),
    'input': obj({'situation_family': TEXT, 'vulnerability_type': TEXT, 'automaticThought': TEXT,
                  'latestInteraction': QUESTION, 'fixedLatestUserIntentHint': TEXT,
                  'previousQuestions': array(QUESTION), 'questionAnswers': array(QUESTION),
                  'expectedDecision': TEXT, 'expectedAssessment': TEXT,
                  'expectedDistortions': array(TEXT), 'allowedQuestionMeanings': array(TEXT),
                  'forbiddenQuestionMeanings': array(TEXT),
                  'semanticRouteDefinitions': array(ROUTE_DEFINITION)}),
    'acceptedState': obj({'answerDisposition': TEXT, 'blockedRoutes': array(ROUTE_FACT),
        'blockedRouteFamilies': array(TEXT), 'blockedSemanticDimensions': array(obj({'dimension': TEXT, 'value': TEXT})),
        'unansweredQuestionAttempts': array(ATTEMPT), 'resolvedButIrrelevantTopics': array(ROUTE_FACT),
        'confirmationAllowed': BOOL, 'completionAssessmentAllowed': BOOL, 'coverageStateRole': TEXT,
        'coverageReviewRequired': BOOL, 'incompleteCoverageDomains': array(TEXT),
        'priorVerifiedCoverage': COVERAGE, 'inputExplorationCoverage': COVERAGE,
        'completionCandidates': array(ASSESSMENT), 'completionCandidateCoverage': COVERAGE,
        'coverageReview': COVERAGE, 'acceptedCoverage': COVERAGE, 'acceptedEvidenceAtoms': array(EVIDENCE_SOURCE)}),
    'boundary': obj({'supplementalBoundaryEvidence': array(EVIDENCE_SOURCE), 'effectiveSemanticMove': TEXT,
        'effectiveQuestionPlan': PLAN, 'terminalAssessment': ASSESSMENT, 'assessmentBoundary': BOUNDARY,
        'factBoundaryGap': GAP, 'factBoundaryQuestionCount': NUMBER,
        'consideredCandidates': array(CONSIDERED), 'missingDefinitionElements': array(TEXT)}),
    'final': obj({'renderedOutput': TEXT, 'actualDecision': enum('QUESTION', 'DISTORTION_PRESENT',
        'NO_CLEAR_DISTORTION', 'SAFETY_CLARIFICATION', 'SAFETY_STOP', 'STOP', 'TECHNICAL_NO_OUTPUT', 'UNKNOWN'),
        'actualAssessment': TEXT, 'actualDistortions': array(TEXT), 'questionPurpose': TEXT,
        'semanticRouteType': TEXT, 'questionGoal': TEXT, 'answerTarget': TEXT, 'answerSource': TEXT,
        'prefaceGoal': TEXT, 'exampleOptions': array(TEXT), 'groundingQuestionCodes': array(TEXT),
        'avoidTopics': array(TEXT), 'finalResponse': FINAL_RESPONSE,
        'confirmation': obj({'requiredFields': array(TEXT), 'acknowledgementEvidence': TEXT,
            'acknowledgementSourceQuestionRef': TEXT, 'proposalMessage': TEXT}),
        'actionClass': enum('UNCERTAIN', 'UNKNOWN')}),
    'safety': obj({'safetyCandidates': array(SAFETY_ITEM), 'safetyReview': array(SAFETY_REVIEW),
                    'safetyEvidence': array(POINTER), 'safetyReason': TEXT}),
    'dialogueControl': obj({'kind': enum('ANSWER_WAIT', 'NONE', 'UNKNOWN'),
                            'targetQuestionRef': TEXT, 'availableResponses': array(enum('ANSWER', 'SKIP'))}),
})


def canonical_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def schema_sha256():
    return sha256(canonical_bytes(SCHEMA)).hexdigest()


def empty(schema):
    if schema.get('type') == 'object':
        return {key: empty(value) for key, value in schema['properties'].items()}
    if schema.get('type') == 'array': return []
    if 'enum' in schema:
        return 'UNKNOWN' if 'UNKNOWN' in schema['enum'] else schema['enum'][0]
    return None


def validate(value, schema=SCHEMA, path='$'):
    kind = schema['type']; allowed = kind if isinstance(kind, list) else [kind]
    actual = ('null' if value is None else 'boolean' if type(value) is bool else
              'integer' if type(value) is int else 'number' if type(value) is float else
              'string' if isinstance(value, str) else 'array' if isinstance(value, list) else
              'object' if isinstance(value, dict) else 'INVALID')
    if actual not in allowed and not (actual == 'integer' and 'number' in allowed):
        raise ExportError('schema_type:' + path)
    if 'enum' in schema and value not in schema['enum']: raise ExportError('schema_enum:' + path)
    if actual == 'object':
        if set(value) != set(schema['properties']): raise ExportError('schema_keys:' + path)
        for key, item in value.items(): validate(item, schema['properties'][key], path + '.' + key)
    elif actual == 'array':
        for index, item in enumerate(value): validate(item, schema['items'], path + '[' + str(index) + ']')
    return value


class OpaqueMap:
    """Stateful reference map; persist records full-only for restart/replay."""
    def __init__(self, records=()):
        self._rows = deepcopy(list(records)); self._lookup = {}; self._counts = {}
        for row in self._rows:
            if set(row) != {'namespace', 'scope', 'original', 'opaque'} or row['namespace'] not in ('c', 'r', 'q', 's', 'g', 'e', 'v'):
                raise ExportError('invalid_mapping_shape')
            if not re.fullmatch(re.escape(row['namespace']) + r'_\d{8}', row['opaque']): raise ExportError('invalid_opaque_id')
            key = (row['namespace'], row['scope'], canonical_bytes(row['original']).decode('utf-8'))
            if key in self._lookup or row['opaque'] in self._lookup.values(): raise ExportError('duplicate_mapping')
            self._lookup[key] = row['opaque']
            self._counts[row['namespace']] = max(self._counts.get(row['namespace'], 0), int(row['opaque'].split('_')[-1]))

    def ref(self, namespace, original, scope=''):
        if original is None: return None
        if namespace not in ('c', 'r', 'q', 's', 'g', 'e', 'v') or type(original) not in (str, int): raise ExportError('invalid_reference')
        key = (namespace, scope, canonical_bytes(original).decode('utf-8'))
        if key not in self._lookup:
            self._counts[namespace] = self._counts.get(namespace, 0) + 1
            opaque = namespace + '_' + str(self._counts[namespace]).zfill(8)
            self._lookup[key] = opaque
            self._rows.append({'namespace': namespace, 'scope': scope, 'original': original, 'opaque': opaque})
        return self._lookup[key]

    def records(self): return deepcopy(self._rows)


def dictionary(value): return value if isinstance(value, dict) else {}
def sequence(value): return value if isinstance(value, list) else []


class Projection:
    def __init__(self, implementation, ids, scope, state, observations, request):
        self.implementation = implementation; self.ids = ids; self.scope = scope
        self.state = dictionary(state); self.observations = dictionary(observations); self.presence = {}
        self.source_aliases = {}; self.source_rows = {}; self.current_by_question = {}
        # These are address/revision equivalences, not new evidence. Prefer the
        # accepted history over current uncommitted edits of the public request.
        history = self.state.get('history', dictionary(request).get('questionAnswers', []))
        for question in sequence(history):
            code = question.get('questionCode'); text = question.get('answer')
            if code is None or text is None: continue
            key = 'saved-answer:' + code + '@' + sha256(text.encode('utf-8')).hexdigest()
            self.source_rows[key] = {'questionCode': code, 'text': text}
            self.current_by_question[code] = key
        for key, value in dictionary(self.state.get('sources')).items():
            item = dictionary(value.get('item')); code = item.get('questionCode'); address = value.get('source_id')
            self.source_rows[key] = {'questionCode': code, 'text': item.get('answer')}
            self.source_aliases[key] = key
            if address is not None and value.get('revision') is not None:
                self.source_aliases[address + '@' + value['revision']] = key
            if dictionary(self.state.get('current_sources')).get(code) == key:
                self.current_by_question[code] = key
                if address is not None: self.source_aliases[address] = key
        for key, value in dictionary(self.state.get('record_sources')).items():
            self.source_rows[key] = {'questionCode': None, 'text': value.get('text')}
            self.source_aliases[key] = key
        for row in sequence(dictionary(self.observations.get('inputView')).get('sources')):
            key = row.get('sourceKey')
            if key is None and row.get('address') is not None and row.get('revision') is not None:
                key = row['address'] + '@' + row['revision']
            if key is not None:
                self.source_rows.setdefault(key, {'questionCode': row.get('questionCode'), 'text': row.get('text')})
                for alias in (row.get('sourceId'), key):
                    if alias is not None: self.source_aliases[alias] = key

    def source(self, raw):
        raw = dictionary(raw); code = raw.get('sourceQuestionCode', raw.get('questionCode'))
        source = raw.get('sourceKey')
        address = raw.get('address', raw.get('sourceId'))
        revision = raw.get('revision', raw.get('sourceRevision'))
        if source is None and address is not None:
            specific = address + '@' + revision if revision is not None else address
            source = self.source_aliases.get(specific, specific)
        if source is None and code is not None: source = self.current_by_question.get(code)
        source = self.source_aliases.get(source, source)
        known = self.source_rows.get(source, {})
        if code is None: code = known.get('questionCode')
        if source is None and code is not None: source = 'unversioned-answer:' + code
        return source, code, known.get('text')

    def read(self, mapping, key, path, *, supported=True):
        value = dictionary(mapping).get(key, MISSING)
        status = ('UNSUPPORTED' if not supported else 'NOT_OBSERVED' if value is MISSING else
                  'NULL' if value is None else 'EMPTY' if value in ([], {}) else 'PRESENT')
        self.presence[path] = {'status': status, 'sourceField': key if supported else None}
        return None if value is MISSING or not supported else deepcopy(value)

    def ref(self, namespace, value): return self.ids.ref(namespace, value, self.scope)

    def pointer(self, raw):
        raw = dictionary(raw); result = empty(POINTER)
        source, code, text = self.source(raw)
        excerpt = raw.get('exactExcerpt', raw.get('excerpt'))
        if excerpt is None and type(raw.get('start')) is int and type(raw.get('length')) is int and text is not None:
            start, length = raw['start'], raw['length']
            if start < 0 or length <= 0 or start + length > len(text): raise ExportError('invalid_exact_pointer_range')
            excerpt = text[start:start + length]
        result.update(sourceRef=self.ref('s', source),
            questionRef=self.ref('q', code),
            start=raw.get('start'), length=raw.get('length'), excerpt=excerpt)
        return result

    def question(self, raw):
        raw = dictionary(raw); result = empty(QUESTION)
        for key in result:
            if key != 'questionRef': result[key] = deepcopy(raw.get(key))
        result['questionRef'] = self.ref('q', raw.get('questionCode'))
        return result

    def coverage(self, raw):
        if raw is not None and not isinstance(raw, dict):
            # Q11 per-source review diagnostics are not a domain coverage review.
            # Do not guess domains from an unlike diagnostic list.
            return empty(COVERAGE)
        raw = dictionary(raw); result = empty(COVERAGE)
        for domain in DOMAINS:
            item = dictionary(raw.get(domain)); row = result[domain]
            status = item.get('status')
            if status in COVERAGE_ITEM['properties']['status']['enum']: row['status'] = status
            grounding = dictionary(item.get('grounding'))
            codes = sequence(item.get('sourceQuestionCodes'))
            single = item.get('sourceQuestionCode', grounding.get('sourceQuestionCode'))
            if single is not None and single not in codes: codes = codes + [single]
            row['questionRefs'] = [self.ref('q', code) for code in codes]
            row['grounding'] = self.pointer(grounding)
            row['rejectionReason'] = item.get('rejectionReason')
        return result

    def plan(self, raw):
        raw = dictionary(raw); result = empty(PLAN)
        for key in ('questionPurpose', 'semanticRouteType', 'answerSource', 'prefaceGoal'):
            result[key] = raw.get(key)
        result['semanticMove'] = raw.get('move')
        result['questionGoal'] = raw.get('questionGoal', raw.get('focus'))
        result['answerTarget'] = raw.get('answerTarget')
        result['answerTargetRef'] = self.ref('q', raw.get('targetQuestionCode'))
        result['goalRef'] = self.ref('g', raw.get('goalId'))
        result['exampleOptions'] = deepcopy(sequence(raw.get('exampleOptions', raw.get('verifiedExampleOptions'))))
        result['avoidTopics'] = deepcopy(sequence(raw.get('avoidTopics')))
        result['groundingQuestionRefs'] = [self.ref('q', code) for code in
            sequence(raw.get('groundingQuestionCodes', raw.get('contextQuestionCodes')))]
        return result

    def route_facts(self, rows):
        result = []
        for raw in sequence(rows):
            raw = {'semanticRouteType': raw} if isinstance(raw, str) else dictionary(raw)
            row = empty(ROUTE_FACT)
            row.update(questionRef=self.ref('q', raw.get('sourceQuestionCode')),
                questionPurpose=raw.get('questionPurpose'), semanticRouteType=raw.get('semanticRouteType'),
                semanticRouteFamily=raw.get('semanticRouteFamily'), question=raw.get('rejectedQuestion'),
                semanticDimensions=_dimensions(raw.get('blockedSemanticValues', raw.get('semanticSignature'))))
            result.append(row)
        return result

    def assessment(self, raw):
        raw = dictionary(raw); result = empty(ASSESSMENT)
        for key in result:
            if key not in ('assessmentType', 'groundingQuestionRefs', 'usedReferenceIds', 'consideredCandidates'):
                result[key] = raw.get(key)
        result['assessmentType'] = raw.get('assessmentType', raw.get('type'))
        if result['assessmentType'] in ('NEEDS_MORE_EXPLORATION', 'FACT_BOUNDARY_REQUIRED'):
            result['assessmentType'] = 'BOUNDARY_INFORMATION_REQUIRED'
        result['groundingQuestionRefs'] = [self.ref('q', value) for value in sequence(raw.get('groundingQuestionCodes'))]
        result['usedReferenceIds'] = [self.ref('e', value) for value in sequence(raw.get('usedReferenceIds'))]
        result['consideredCandidates'] = [{key: dictionary(value).get(key) for key in CONSIDERED['properties']}
                                         for value in sequence(raw.get('consideredCandidates'))]
        return result

    def evidence(self, rows, *, legacy=False):
        if rows is not None and not isinstance(rows, list): raise ExportError('evidence_collection_shape')
        grouped = {}
        for index, raw in enumerate(sequence(rows)):
            if not isinstance(raw, dict): raise ExportError('evidence_row_shape')
            source, code, text = self.source(raw)
            source_ref = self.ref('s', source); question_ref = self.ref('q', code)
            if source_ref is None: raise ExportError('evidence_missing_source')
            row = empty(CONTRIBUTION)
            identity = raw.get('refId')
            if identity is None: identity = 'saved-evidence:' + canonical_bytes([source, raw.get('domain'), raw.get('excerpt'), index]).decode('utf-8')
            kind = {'GAP_CONTENT': 'CONTENT', 'GAP_NONE': 'EXPLICIT_NONE'}.get(raw.get('kind'), raw.get('kind'))
            pointer = self.pointer(raw)
            row.update(referenceId=self.ref('e', identity), start=pointer['start'], length=pointer['length'],
                excerpt=pointer['excerpt'], domain=raw.get('domain'),
                kind=kind if kind in ('CONTENT', 'EXPLICIT_NONE') else 'UNKNOWN',
                role=raw.get('reviewerRole', raw.get('role')), goalRef=self.ref('g', raw.get('goalId', dictionary(raw.get('gapBinding')).get('goalId'))),
                validity='CURRENT' if not legacy else 'UNKNOWN')
            key = (source_ref, question_ref)
            grouped.setdefault(key, {'sourceRef': source_ref, 'questionRef': question_ref, 'contributions': []})['contributions'].append(row)
        return list(grouped.values())


def _distortions(raw):
    return [{key: dictionary(row).get(key) for key in DISTORTION['properties']} for row in sequence(raw)]


def _dimensions(raw):
    if raw is None: return []
    if isinstance(raw, dict):
        result = []
        for key, value in raw.items():
            values = value if isinstance(value, list) else [value]
            for item in values:
                if item is not None and not isinstance(item, str): raise ExportError('dimension_value_shape')
                result.append({'dimension': key, 'value': item})
        return result
    result = []
    for row in sequence(raw):
        if not isinstance(row, dict) or set(row) != {'dimension', 'value'}: raise ExportError('dimension_row_shape')
        result.append(deepcopy(row))
    return result


def _final_response(project, raw):
    raw = dictionary(raw); result = empty(FINAL_RESPONSE)
    for key in ('status', 'assessmentType', 'acknowledgementEvidence', 'proposalMessage'):
        result[key] = raw.get(key)
    result['nextQuestion'] = project.question(raw.get('nextQuestion'))
    result['beforeDistortions'] = _distortions(raw.get('beforeDistortions'))
    outcome = dictionary(raw.get('outcomeDraft'))
    result['outcomeDraft'] = {key: outcome.get(key) for key in OUTCOME['properties'] if key != 'afterDistortions'}
    result['outcomeDraft']['afterDistortions'] = _distortions(outcome.get('afterDistortions'))
    result['confirmationRequiredFields'] = deepcopy(sequence(raw.get('confirmationRequiredFields')))
    result['acknowledgementSourceQuestionRef'] = project.ref('q', raw.get('acknowledgementSourceQuestionCode'))
    result['risk'] = {key: dictionary(raw.get('risk')).get(key) for key in ('level', 'reasonCode')}
    return result


def _gap(project, raw, pending, *, known_absent=False):
    result = empty(GAP); raw = dictionary(raw); pending = dictionary(pending)
    if not raw:
        if known_absent: result['answerState'] = 'NOT_ASKED'
        return result
    result.update(questionRef=project.ref('q', raw.get('rootQuestionCode', pending.get('questionCode'))),
        goalRef=project.ref('g', raw.get('goalId')), question=raw.get('question', pending.get('question')),
        missingFact=raw.get('missingFact', raw.get('missingFactBoundaryInformation')),
        whyDecisionDependsOnIt=raw.get('whyDecisionDependsOnIt', raw.get('whyExistingAnswersAreInsufficient')),
        distinctionNeeded=raw.get('distinctionNeeded'))
    candidate = dictionary(dictionary(raw.get('acceptedCandidate')).get('result'))
    for key in ('missingFact', 'whyDecisionDependsOnIt'):
        if result[key] is None: result[key] = candidate.get(key)
    result['groundingQuestionRefs'] = [project.ref('q', code) for code in sequence(raw.get('groundingQuestionCodes'))]
    result['answerState'] = {'ANSWER_WAIT': 'AWAITING_ANSWER', 'AWAITING_ANSWER': 'AWAITING_ANSWER',
                             'REVIEWED': 'ANSWERED'}.get(raw.get('resolution'), 'UNKNOWN')
    # A current baseline pending_assessment_gap explicitly denotes its unanswered
    # emitted gap. No inference is made from an absent field or model branch.
    if project.implementation == 'Q10' and pending.get('questionCode'): result['answerState'] = 'AWAITING_ANSWER'
    return result


def export_turn(*, implementation, anonymous_version, case_key, response_key, turn_index,
                public_request, public_response, accepted_state, grading_context,
                observations=None, id_map=None):
    if implementation not in ('Q10', 'Q11'): raise ExportError('unsupported_adapter')
    if anonymous_version not in ('A', 'B'): raise ExportError('anonymous_version_required')
    if type(turn_index) is not int or turn_index < 0: raise ExportError('invalid_turn_index')
    for value in (public_request, accepted_state, grading_context, observations):
        if value is not None and not isinstance(value, dict): raise ExportError('collector_dict_required')
    if public_response is not None and not isinstance(public_response, dict): raise ExportError('public_response_dict_required')
    ids = id_map if id_map is not None else OpaqueMap()
    case_ref = ids.ref('c', case_key); scope = case_ref + ':' + anonymous_version
    p = Projection(implementation, ids, scope, accepted_state, observations, public_request)
    req = dictionary(public_request); response = dictionary(public_response); context = dictionary(grading_context)
    obs = dictionary(observations); state = dictionary(accepted_state); core = dictionary(state.get('state')) if implementation == 'Q10' else state
    diag = dictionary(obs.get('diagnostics')); input_view = dictionary(obs.get('inputView'))
    payload = empty(SCHEMA); identity = payload['identity']; inp = payload['input']; accepted = payload['acceptedState']; boundary = payload['boundary']; final = payload['final']
    record = dictionary(req.get('record')); questions = sequence(req.get('questionAnswers'))
    identity.update(case_id=case_ref, response_id=p.ref('r', response_key), anonymous_version=anonymous_version,
        turn_index=turn_index, suite=context.get('suite'), caseType=context.get('caseType'))
    identity['syntheticContext'] = {key: record.get(key) for key in identity['syntheticContext']}
    inp.update(situation_family=context.get('situation_family'), vulnerability_type=context.get('vulnerability_type'),
        automaticThought=record.get('automaticThought'), latestInteraction=p.question(questions[-1] if questions else None),
        fixedLatestUserIntentHint=context.get('latestUserIntentHint'), previousQuestions=[p.question(q) for q in questions[:-1]],
        questionAnswers=[p.question(q) for q in questions], expectedDecision=context.get('expectedDecision'),
        expectedAssessment=context.get('expectedAssessment'))
    for key in ('expectedDistortions', 'allowedQuestionMeanings', 'forbiddenQuestionMeanings'):
        inp[key] = deepcopy(sequence(context.get(key)))
    definitions = context.get('semanticRouteDefinitions')
    if isinstance(definitions, dict):
        definitions = [{'route': key, **({'definition': value} if isinstance(value, str) else dictionary(value))} for key, value in definitions.items()]
    inp['semanticRouteDefinitions'] = [{
        'route': dictionary(row).get('route', dictionary(row).get('semanticRouteType')),
        'family': dictionary(row).get('family', dictionary(row).get('semanticRouteFamily')),
        'definition': dictionary(row).get('definition', dictionary(row).get('meaning')),
        'informationSource': dictionary(row).get('informationSource'),
        'forbiddenDirections': sequence(dictionary(row).get('forbiddenDirections')),
        'allowedQuestionPurposes': sequence(dictionary(row).get('allowedQuestionPurposes')),
        'dimensions': _dimensions(dictionary(row).get('dimensions', dictionary(row).get('semanticSignature')))} for row in sequence(definitions)]
    for key in ('answerDisposition', 'confirmationAllowed', 'completionAssessmentAllowed', 'coverageStateRole', 'coverageReviewRequired'):
        accepted[key] = p.read(input_view, key, 'acceptedState.' + key)
    for key in ('blockedRouteFamilies', 'incompleteCoverageDomains'):
        accepted[key] = sequence(p.read(input_view, key, 'acceptedState.' + key))
    for key in ('blockedRoutes', 'resolvedButIrrelevantTopics'):
        accepted[key] = p.route_facts(p.read(input_view, key, 'acceptedState.' + key))
    if implementation == 'Q10' and 'blockedRoutes' not in input_view:
        accepted['blockedRoutes'] = p.route_facts(p.read(core, 'blockedRoutes', 'acceptedState.blockedRoutes'))
    accepted['blockedSemanticDimensions'] = _dimensions(p.read(input_view, 'blockedSemanticDimensions', 'acceptedState.blockedSemanticDimensions'))
    attempts = p.read(state, 'unanswered_question_attempts', 'acceptedState.unansweredQuestionAttempts', supported=implementation == 'Q10')
    accepted['unansweredQuestionAttempts'] = [{
        'questionRef': p.ref('q', dictionary(row).get('sourceQuestionCode')),
        **{key: dictionary(row).get(key) for key in ('questionPurpose', 'semanticRouteType', 'answerTarget', 'answerSource', 'answerDisposition')},
        'semanticDimensions': _dimensions(dictionary(row).get('semanticSignature'))} for row in sequence(attempts)]
    for key in ('priorVerifiedCoverage', 'inputExplorationCoverage', 'completionCandidateCoverage'):
        accepted[key] = p.coverage(p.read(input_view, key, 'acceptedState.' + key))
    coverage = p.read(core, 'explorationCoverage' if implementation == 'Q10' else 'coverage', 'acceptedState.acceptedCoverage')
    accepted['acceptedCoverage'] = p.coverage(coverage)
    review = p.read(obs, 'coverageReview', 'acceptedState.coverageReview')
    # proposed_exploration_coverage is assigned before validation in the legacy
    # product. Keep it full-only unless the collector provides an accepted review.
    if review is not None and not isinstance(review, dict):
        p.presence['acceptedState.coverageReview']['status'] = 'UNSUPPORTED_SHAPE'
    accepted['coverageReview'] = p.coverage(review)
    accepted['completionCandidates'] = [p.assessment(row) for row in sequence(p.read(obs, 'completionCandidates', 'acceptedState.completionCandidates'))]
    evidence = p.read(obs, 'currentEvidence', 'acceptedState.acceptedEvidenceAtoms')
    if 'currentEvidence' not in obs and implementation == 'Q10':
        evidence = []
        for domain in DOMAINS:
            values = core.get(domain); values = [values] if isinstance(values, dict) else sequence(values)
            evidence.extend({**row, 'domain': domain} for row in values)
        p.presence['acceptedState.acceptedEvidenceAtoms'] = {'status': 'PRESENT' if evidence else 'EMPTY' if all(d in core for d in DOMAINS) else 'NOT_OBSERVED',
            'sourceField': 'state.evidenceFor/evidenceAgainst/alternativeViews/acknowledgement'}
    accepted['acceptedEvidenceAtoms'] = p.evidence(evidence, legacy=implementation == 'Q10')
    boundary['supplementalBoundaryEvidence'] = p.evidence(p.read(obs, 'currentSupplemental', 'boundary.supplementalBoundaryEvidence', supported=implementation == 'Q11'))
    plan = p.read(obs, 'effectivePlan', 'boundary.effectiveQuestionPlan')
    if 'effectivePlan' not in obs and implementation == 'Q10': plan = p.read(diag, 'effective_question_plan', 'boundary.effectiveQuestionPlan')
    # Failed attempts have no current committed plan even if a draft compiler ran.
    if public_response is None: plan = None
    boundary['effectiveQuestionPlan'] = p.plan(plan); boundary['effectiveSemanticMove'] = boundary['effectiveQuestionPlan']['semanticMove']
    assessment = p.read(obs, 'terminalAssessment', 'boundary.terminalAssessment')
    if 'terminalAssessment' not in obs and implementation == 'Q10': assessment = p.read(diag, 'assessor_assessment', 'boundary.terminalAssessment')
    boundary['terminalAssessment'] = p.assessment(assessment if public_response is not None else None)
    boundary['assessmentBoundary'] = {key: dictionary(assessment).get(key) for key in BOUNDARY['properties']} if public_response is not None else empty(BOUNDARY)
    pending = dictionary(state.get('pending_question')); gap_key = 'pending_assessment_gap' if implementation == 'Q10' else 'pending_fact_boundary'
    gap_raw = p.read(state, gap_key, 'boundary.factBoundaryGap')
    # Absence of the current gap does not prove no gap was ever asked.
    known_no_gap = implementation == 'Q11' and state.get('gap_usage') == {} and gap_key in state and gap_raw is None
    boundary['factBoundaryGap'] = _gap(p, gap_raw, pending, known_absent=known_no_gap)
    boundary['factBoundaryQuestionCount'] = p.read(obs, 'factBoundaryQuestionCount', 'boundary.factBoundaryQuestionCount')
    if 'factBoundaryQuestionCount' not in obs and implementation == 'Q11' and isinstance(state.get('gap_registry'), dict):
        roots = [row.get('rootQuestionCode') for row in state['gap_registry'].values()]
        if all(root is not None for root in roots):
            boundary['factBoundaryQuestionCount'] = len(set(roots))
            p.presence['boundary.factBoundaryQuestionCount'] = {'status': 'PRESENT', 'sourceField': 'gap_registry.*.rootQuestionCode'}
    boundary['consideredCandidates'] = deepcopy(boundary['terminalAssessment']['consideredCandidates'])
    boundary['missingDefinitionElements'] = [row['missingDefinitionElement'] for row in boundary['consideredCandidates'] if row['missingDefinitionElement'] is not None]
    final['finalResponse'] = _final_response(p, public_response)
    next_question = dictionary(response.get('nextQuestion')); assessment_type = response.get('assessmentType'); risk = dictionary(response.get('risk'))
    decision = 'UNKNOWN'
    if public_response is None: decision = 'TECHNICAL_NO_OUTPUT'
    elif response.get('status') == 'SAFETY_STOP': decision = 'SAFETY_STOP'
    elif next_question:
        current_scope = pending.get('scope') if pending.get('questionCode') == next_question.get('questionCode') else None
        decision = 'SAFETY_CLARIFICATION' if current_scope == 'SAFETY' else 'STOP' if current_scope == 'STOP' else 'QUESTION'
    elif response.get('status') == 'CONFIRM_REQUIRED' and assessment_type in ('DISTORTION_PRESENT', 'NO_CLEAR_DISTORTION'): decision = assessment_type
    final.update(actualDecision=decision, actualAssessment=assessment_type,
        actualDistortions=[row.get('code') for row in sequence(response.get('beforeDistortions')) if row.get('code') is not None],
        renderedOutput=next_question.get('question', response.get('proposalMessage')),
        questionPurpose=next_question.get('questionPurpose'), semanticRouteType=next_question.get('semanticRouteType'))
    for key in ('questionGoal', 'answerTarget', 'answerSource', 'prefaceGoal', 'exampleOptions', 'avoidTopics'):
        final[key] = deepcopy(boundary['effectiveQuestionPlan'][key])
    final['groundingQuestionCodes'] = deepcopy(boundary['effectiveQuestionPlan']['groundingQuestionRefs'])
    final['confirmation'] = {'requiredFields': deepcopy(final['finalResponse']['confirmationRequiredFields']),
        'acknowledgementEvidence': response.get('acknowledgementEvidence'),
        'acknowledgementSourceQuestionRef': final['finalResponse']['acknowledgementSourceQuestionRef'],
        'proposalMessage': response.get('proposalMessage')}
    control = payload['dialogueControl']
    if public_response is not None and next_question and pending.get('questionCode') == next_question.get('questionCode'):
        if implementation == 'Q11' and pending.get('controlPurpose') == 'GAP_ANSWER_WAIT':
            # Choice labels describe actually offered text, not inferred intent.
            # An unlike body is UNKNOWN, never silently repaired to a valid wait.
            if next_question.get('question') == GAP_WAIT_MESSAGE and pending.get('rootGapQuestionCode') is not None:
                control.update(kind='ANSWER_WAIT', targetQuestionRef=p.ref('q', pending.get('rootGapQuestionCode')), availableResponses=['ANSWER', 'SKIP'])
                final['actionClass'] = 'UNCERTAIN'
        elif implementation == 'Q10' or 'controlPurpose' in pending:
            control['kind'] = 'NONE'
    elif public_response is not None and not next_question: control['kind'] = 'NONE'
    payload['safety']['safetyReason'] = risk.get('reasonCode')
    episodes = p.read(state, 'episodes', 'safety.safetyCandidates', supported=implementation == 'Q11')
    for episode in dictionary(episodes).values():
        row = empty(SAFETY_ITEM)
        row.update(safetyRef=p.ref('v', episode.get('episodeId')), concern=episode.get('concern'), level=episode.get('level'),
            state={'ACTIVE': 'UNRESOLVED', 'RESOLVED': 'RESOLVED'}.get(episode.get('status'), 'UNKNOWN'),
            origin=p.pointer(episode.get('primaryTrigger')),
            resolutionEvidence=[p.pointer(value) for value in sequence(episode.get('resolutionEvidence'))])
        for key in ('subject', 'currentness', 'intent', 'immediacy'): row[key] = episode.get(key)
        payload['safety']['safetyCandidates'].append(row)
    if implementation == 'Q10':
        candidates = p.read(input_view, 'safetyCandidates', 'safety.safetyCandidates')
        for index, candidate in enumerate(sequence(candidates)):
            row = empty(SAFETY_ITEM); row['reason'] = candidate.get('reason')
            row['safetyRef'] = p.ref('v', 'observed-safety-candidate:' + str(index))
            # sourceType is the actual product-provided address, not a meaning
            # inferred from the risk phrase. Do not guess subject/currentness.
            code = questions[-1].get('questionCode') if questions and candidate.get('sourceType') == 'LATEST_INTERACTION' else None
            row['origin'] = p.pointer({'sourceQuestionCode': code, 'excerpt': candidate.get('evidence')})
            if candidate.get('sourceType') in ('AUTOMATIC_THOUGHT', 'SITUATION'):
                row['origin']['sourceRef'] = p.ref('s', 'public-record:' + candidate['sourceType'])
            payload['safety']['safetyCandidates'].append(row)
    safety = dictionary(p.read(obs, 'safety', 'safety.safetyReview'))
    if safety:
        payload['safety']['safetyReview'] = [{'decision': safety.get('action', safety.get('decision')),
            'clearance': safety.get('clearance'), 'context': [p.pointer(value) for value in sequence(safety.get('contextReferences'))]}]
        payload['safety']['safetyEvidence'] = [p.pointer(value) for value in sequence(safety.get('evidence')) if isinstance(value, dict)]
    # Fill field-level provenance for direct public/grading projections as well
    # as explicit unsupported/observed internal mappings. A present UNKNOWN/null
    # placeholder must never be misreported as observed model meaning.
    def presence_tree(value, path):
        if path in p.presence: return
        if isinstance(value, dict):
            for key, item in value.items(): presence_tree(item, path + '.' + key if path else key)
        else:
            source = 'public_request' if path.startswith('input.') or path.startswith('identity.syntheticContext') else 'public_response' if path.startswith('final.') else None
            if path.startswith('input.') and path.split('.')[1] not in ('automaticThought', 'latestInteraction', 'previousQuestions', 'questionAnswers'):
                source = 'grading_context'
            status = 'NOT_OBSERVED' if value is None or value == 'UNKNOWN' else 'EMPTY' if value == [] else 'PRESENT'
            p.presence[path] = {'status': status, 'sourceField': source}
    presence_tree(payload, '')
    validate(payload)
    check_metadata_leaks(payload)
    digest = sha256(canonical_bytes(payload)).hexdigest()
    return {'blindPayload': payload, 'blindPayloadSha256': digest,
            'fullOnly': {'referenceMap': ids.records(), 'sourcePresence': p.presence,
                         'sourceAliases': deepcopy(p.source_aliases),
                         'adapterImplementation': implementation, 'schemaSha256': schema_sha256()}}


# User/assistant natural-language fields are exact, not regex-redacted. All
# structured metadata is checked, including input context labels and references.
NATURAL_LANGUAGE_KEYS = frozenset({'situation', 'automaticThought', 'question', 'answer', 'excerpt',
    'renderedOutput', 'proposalMessage', 'acknowledgementEvidence', 'evidenceForText', 'evidenceAgainstText',
    'alternativeThoughtText', 'askedAt', 'answeredAt'})
LEAK = re.compile(r'(?i)(?<![a-z0-9])q(?:[5-9]|1[0-9])(?:\b|_|[a-z]\d)|gpt[-_ ]|chatopenai|langgraph|checkpoint|'
                  r'\b(?:agent|writer|assessor|sdk)\b|cbt_q11|cbt_session_agent|agent-whole-flow|whole-flow-review|'
                  r'(?:^|[/\\])frozen-source(?:[/\\]|$)|[a-z]:[/\\]')


def check_metadata_leaks(payload):
    def walk(value, path='$', key=None):
        if isinstance(value, dict):
            for name, item in value.items():
                if LEAK.search(name): raise ExportError('metadata_key_leak:' + path)
                walk(item, path + '.' + name, name)
        elif isinstance(value, list):
            for index, item in enumerate(value): walk(item, path + '[' + str(index) + ']', key)
        elif isinstance(value, str) and key not in NATURAL_LANGUAGE_KEYS and LEAK.search(value):
            raise ExportError('metadata_value_leak:' + path)
    walk(payload)


def verify_pair(blind_record, full_record):
    blind = blind_record['blindPayload']; full = full_record['blindPayload']
    validate(blind); validate(full); check_metadata_leaks(blind)
    digest = sha256(canonical_bytes(blind)).hexdigest()
    if canonical_bytes(blind) != canonical_bytes(full) or any(
        row.get('blindPayloadSha256') != digest for row in (blind_record, full_record)):
        raise ExportError('blind_full_payload_hash_mismatch')
    return digest


def verify_unique(records):
    seen = set()
    for record in records:
        verify_pair(record, record)
        value = record['blindPayload']['identity']
        key = (value['case_id'], value['anonymous_version'], value['response_id'], value['turn_index'])
        if key in seen: raise ExportError('duplicate_case_version_response_turn')
        seen.add(key)
    return len(seen)


def blind_record(exported):
    """The only record wrapper to place in blind packages (no fullOnly fields)."""
    verify_pair(exported, exported)
    return {key: deepcopy(exported[key]) for key in ('blindPayload', 'blindPayloadSha256')}
