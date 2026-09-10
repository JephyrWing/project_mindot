"""Normative, pure schema reference: agent-whole-flow-1 plus whole-flow-review-fixes-2.

Port into the product's existing provider_schemas module; do not import docs
at runtime. This file has no network, framework, product or model imports.
Bindings come from the server view. Semantic validation is specified in the
execution contract; JSON Schema alone does not establish semantic validity.
"""
from copy import deepcopy
import json
from collections import Counter

DOMAINS = ['evidenceFor', 'evidenceAgainst', 'alternativeViews', 'acknowledgement']
ROLES = ['OBSERVED_FACT', 'REPORTED_FACT', 'USER_INFERENCE',
         'ALTERNATIVE_HYPOTHESIS', 'BALANCED_SYNTHESIS']
MOVES = ['OBSERVABLE_DETAIL', 'DIRECT_SUPPORT', 'COUNTEREVIDENCE',
         'ALTERNATIVE_HYPOTHESIS', 'BALANCED_SYNTHESIS']
CLEARANCES = ['NEGATED', 'PAST_RESOLVED', 'QUOTED_OR_THIRD_PARTY',
              'HYPOTHETICAL', 'NON_HARM_MEANING']

# Frozen question punctuation from Unicode character names (Unicode 15.0),
# including combined/inverted marks and the two question-mark ornaments.
# https://www.unicode.org/charts/collation/chart_Punctuation.html
# This is a finite format rule, not semantic question detection or a runtime
# Unicode-property scan. Only ASCII '?' is permitted at the question's end.
WRITER_QUESTION_MARKS = ''.join(chr(code) for code in (
    0x003F, 0x00BF, 0x037E, 0x055E, 0x061F, 0x1367, 0x1945,
    0x203D, 0x2047, 0x2048, 0x2049, 0x2753, 0x2754, 0x2CFA,
    0x2CFB, 0x2E18, 0x2E2E, 0x2E54, 0xA60F, 0xA6F7, 0xFE16,
    0xFE56, 0xFF1F, 0x11143, 0x1E95F))
WRITER_NON_ASCII_QUESTION_MARKS = WRITER_QUESTION_MARKS.replace('?', '')
WRITER_STATEMENT_FORBIDDEN = WRITER_QUESTION_MARKS + '\r\n'
WRITER_STATEMENT_PATTERN = '^[^' + WRITER_QUESTION_MARKS + r'\r\n]*$'
WRITER_QUESTION_PATTERN = '^[^' + WRITER_QUESTION_MARKS + r'\r\n]*[요까]\?$'

def obj(properties):
    return {'type': 'object', 'properties': properties,
            'required': list(properties), 'additionalProperties': False}

def string(limit):
    return {'type': 'string', 'minLength': 1, 'maxLength': limit}

def enum(values):
    values = list(values)
    if not values or len(values) != len(set(values)):
        raise ValueError('empty_or_duplicate_enum')
    return {'type': 'string', 'enum': values}

def lit(value):
    return enum([value])

def nullable(schema):
    return {'anyOf': [schema, {'type': 'null'}]}

def optional_id(values):
    return nullable(enum(values)) if values else {'type': 'null'}

def array(schema, maximum, minimum=0):
    return {'type': 'array', 'items': schema, 'minItems': minimum, 'maxItems': maximum}

def ids(values, maximum):
    return array(enum(values), maximum) if values else array({'type': 'string'}, 0)

def span():
    return obj({'exactExcerpt': string(96),
                'occurrence': nullable({'type': 'integer', 'minimum': 0})})

def pointer(source_ids):
    return obj({'sourceId': enum(source_ids), 'span': span()})

def contribution(goal_ids):
    branches = [
        obj({'kind': lit('CONTENT'), 'domain': enum(DOMAINS), 'span': span(), 'role': enum(ROLES)}),
        obj({'kind': lit('EXPLICIT_NONE'), 'domain': enum(DOMAINS), 'span': span(),
             'goalId': optional_id(goal_ids)})]
    if goal_ids:
        branches.extend([
            obj({'kind': lit('GAP_CONTENT'), 'goalId': enum(goal_ids),
                 'span': span(), 'role': enum(ROLES)}),
            obj({'kind': lit('GAP_NONE'), 'goalId': enum(goal_ids), 'span': span()})])
    return {'anyOf': branches}

def review_definition(source_ids, goal_ids, question_target_ids, prior_request_ids, prior_correction_ids):
    c = contribution(goal_ids)
    correction = obj({'priorCorrectionId': optional_id(prior_correction_ids),
        'action': enum(['RETRACT', 'REAFFIRM']),
        'target': pointer(source_ids), 'correctionSpan': span(),
        'replacementContributions': nullable(array(deepcopy(c), 4))})
    signal = {'anyOf': [
        obj({'type': enum(['REQUEST_EXAMPLE', 'REQUEST_EXPLANATION']), 'span': span(),
             'targetPendingId': optional_id(question_target_ids),
             'priorRequestId': optional_id(prior_request_ids)}),
        obj({'type': enum(['SKIP', 'FEEDBACK', 'UNCLEAR',
                          'REPETITION_OBJECTION', 'REQUEST_STOP']), 'span': span()})]}
    gap_answer = (nullable(obj({'targetPendingId': enum(question_target_ids),
                                'span': span()})) if question_target_ids else {'type': 'null'})
    normal = obj({
        'contributions': array(c, 4), 'signals': array(signal, 2),
        'safetyOnlySpans': array(span(), 4), 'corrections': array(correction, 1),
        'gapAnswer': gap_answer})
    normal['description'] = (
        'contributions/signals/safetyOnlySpans/correctionSpan/gapAnswer.span은 이 slot에 연결된 '
        'source.text의 실제 구절만 인용합니다. corrections.target과 replacementContributions는 '
        '별도로 지정한 target 원문에 연결합니다. 예약된 slot:signal_i는 실제 작성한 signals[i]가 '
        'REQUEST_EXAMPLE 또는 REQUEST_EXPLANATION일 때만 사용합니다. 기여를 요청으로 바꾸지 않습니다.')
    return {'anyOf': [normal,
        obj({'limit': lit('OUTPUT_CAPACITY'), 'reason': string(160)})]}

def fixed(keys, prefix, definition):
    keys = list(keys)
    if keys != [f'{prefix}_{i:03d}' for i in range(len(keys))]:
        raise ValueError('invalid_fixed_slots')
    return obj({key: deepcopy(definition) for key in keys})

def bound_review_slots(slots, source_ids, definition):
    """Annotations from actual view bindings, never source text or inferred roles.

    Legacy list callers retain their unannotated slot contract. The production
    caller passes its immutable slot mapping. Expand the same shared definition
    before annotating so no unsupported $ref sibling or wrapper is introduced;
    the existing compactor still factors identical review branches losslessly.
    """
    if not isinstance(slots, dict):
        return fixed(slots, 'slot', {'$ref': '#/$defs/review'})
    reviews = fixed(slots, 'slot', definition)
    for slot, binding in slots.items():
        source_id = binding.get('sourceId') if isinstance(binding, dict) else None
        if not isinstance(source_id, str) or source_id not in source_ids:
            raise ValueError('invalid_review_slot_source_binding')
        reviews['properties'][slot]['description'] = (
            slot + '의 USER 원문은 sources[sourceId=' + json.dumps(source_id, ensure_ascii=False)
            + '].text 하나입니다. 다른 slot의 원문을 옮겨 쓰지 않습니다.')
    return reviews

def safety_review(source_ids, episode_slots):
    ref = pointer(source_ids)
    resolution = obj({'clearance': enum(CLEARANCES), 'contextReferences': array(ref, 4, 1)})
    current = {'anyOf': [obj({'basis': lit('NO_NEW_SIGNAL')}), obj({
        'basis': lit('CONTEXT_CLEARED'), 'primaryTrigger': deepcopy(ref),
        'clearance': enum(CLEARANCES), 'contextReferences': array(deepcopy(ref), 4, 1)})]}
    return obj({'current': current, 'episodes': fixed(episode_slots, 'episode', resolution)})

def safety_directive(source_ids, episode_ids):
    ref = pointer(source_ids)
    origins = [obj({'newTrigger': ref})]
    if episode_ids:
        origins.insert(0, obj({'existingEpisodeId': enum(episode_ids)}))
    common = {'origin': {'anyOf': origins}, 'contextReferences': array(deepcopy(ref), 4),
              'concern': enum(['SELF_HARM', 'HARM_TO_OTHERS'])}
    return {'anyOf': [
        obj({'type': lit('CLARIFY'), **deepcopy(common),
             'clarificationGoal': enum(['subject', 'currentness', 'intent', 'immediacy'])}),
        obj({'type': lit('STOP'), **deepcopy(common),
             'urgency': enum(['IMMEDIATE', 'REVIEW']), 'reason': string(160)})]}

def tool(name, parameters, descriptions, definitions=None):
    p = deepcopy(parameters)
    if definitions:
        p['$defs'] = deepcopy(definitions)
    p = compact_schema(p)
    return {'type': 'function', 'function': {'name': name,
        'description': descriptions[name], 'strict': True, 'parameters': p}}

def compact_schema(schema):
    """Lossless local references, including repeated bounded enums.

    Only schema positions are shared: a properties mapping is not a schema.
    Expand reachable local references, factor repeated nodes, then inline
    single-use/cost-negative definitions. No field, branch or bound is removed.
    Byte-cost optimization is pure and does not change admission token limits.
    """
    definitions = deepcopy(schema.get('$defs', {}))
    def key(node):
        return json.dumps(node, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    def children(node, transform):
        result = dict(node)
        if 'properties' in result:
            result['properties'] = {name: transform(value) for name, value in result['properties'].items()}
        if isinstance(result.get('items'), dict):
            result['items'] = transform(result['items'])
        for name in ('anyOf', 'allOf', 'oneOf', 'prefixItems'):
            if name in result:
                result[name] = [transform(value) for value in result[name]]
        for name in ('not', 'if', 'then', 'else', 'contains', 'additionalProperties'):
            if isinstance(result.get(name), dict):
                result[name] = transform(result[name])
        return result
    def expand(node, seen=()):
        if '$ref' in node:
            reference = node['$ref']
            if set(node) != {'$ref'} or not reference.startswith('#/$defs/'):
                raise ValueError('unsupported_schema_reference')
            name = reference[len('#/$defs/'):]
            if name not in definitions:
                raise ValueError('unbound_schema_reference')
            if name in seen:
                raise ValueError('cyclic_schema_reference')
            return expand(definitions[name], (*seen, name))
        return children({k: v for k, v in node.items() if k != '$defs'}, lambda child: expand(child, seen))
    original = expand(deepcopy(schema))
    def short_name(number):
        name = ''
        while True:
            name = chr(97 + number % 26) + name
            number = number // 26 - 1
            if number < 0:
                return name
    counts = Counter()
    def count(node):
        counts[key(node)] += 1
        children(node, count)
        return node
    count(original)
    names, shared = {}, {}
    def pack(node, root=False):
        identity = key(node)
        repeated = not root and counts[identity] > 1
        if repeated and identity in names:
            return {'$ref': '#/$defs/' + names[identity]}
        result = children(node, pack)
        if repeated:
            name = short_name(len(names))
            names[identity] = name
            shared[name] = result
            return {'$ref': '#/$defs/' + name}
        return result
    root = pack(original, root=True)
    def reference_counts(root, shared):
        references = Counter()
        def visit(node):
            if '$ref' in node:
                references[node['$ref'][len('#/$defs/'):]] += 1
            children(node, visit)
            return node
        visit(root)
        for value in shared.values():
            visit(value)
        return references
    def inline(root, shared, name):
        def visit(node):
            if node == {'$ref': '#/$defs/' + name}:
                return deepcopy(shared[name])
            return children(node, visit)
        return visit(root), {k: visit(v) for k, v in shared.items() if k != name}
    def assemble(root, shared):
        local_names = {name: short_name(i) for i, name in enumerate(shared)}
        def rename(node):
            if '$ref' in node:
                return {'$ref': '#/$defs/' + local_names[node['$ref'][len('#/$defs/'):]]}
            return children(node, rename)
        result = rename(root)
        if shared:
            result['$defs'] = {local_names[name]: rename(value) for name, value in shared.items()}
        return result
    while shared:
        references = reference_counts(root, shared)
        single = next((name for name in shared if references[name] <= 1), None)
        if single is None:
            break
        root, shared = inline(root, shared, single)
    def cost(root, shared):
        return len(json.dumps(assemble(root, shared), ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    while shared:
        baseline = cost(root, shared)
        best = None
        for name in shared:
            trial_root, trial_shared = inline(root, shared, name)
            trial_cost = cost(trial_root, trial_shared)
            if trial_cost < baseline and (best is None or trial_cost < best[0]):
                best = trial_cost, trial_root, trial_shared
        if best is None:
            break
        _, root, shared = best
    return assemble(root, shared)

def request_updates(source_ids, request_ids, question_target_ids, clarification_ids):
    if not request_ids:
        return array(obj({}), 0)
    branches = [obj({'action': lit('CANCEL'), 'requestId': enum(request_ids),
        'kinds': array(enum(['REQUEST_EXAMPLE', 'REQUEST_EXPLANATION']), 2, 1),
        'source': pointer(source_ids)})]
    if question_target_ids and clarification_ids:
        branches.append(obj({'action': lit('RESOLVE_TARGET'), 'requestId': enum(request_ids),
            'targetPendingId': enum(question_target_ids),
            'clarificationId': enum(clarification_ids), 'source': pointer(source_ids)}))
    return array({'anyOf': branches}, 4)

def select_tools(*, review_slots, source_ids, goal_ids, pending_groups,
                 request_ids, question_target_ids, prior_request_ids, prior_correction_ids,
                 clarification_ids, episode_slots, episode_ids, stop_pending, descriptions):
    """All IDs and slot bindings are supplied by the immutable current view.

    pending_groups keys: CBT, DIRECTION, STOP, CONTROL, SAFETY. No hidden aliases.
    request_ids include existing active and current per-signal reserved aliases.
    question_target_ids include readable historical receipt targets, not only executable pending.
    prior_request_ids bind readable existing requests/receipts; server checks same source/revision.
    episode_ids is the readable actionable catalog, not all historical IDs.
    prior_correction_ids and clarification_ids bind fully readable canonical records.
    At least one readable USER source is required by the input contract.
    """
    ref = pointer(source_ids)
    definitions = {'review': review_definition(source_ids, goal_ids, question_target_ids, prior_request_ids, prior_correction_ids)}
    reviews = bound_review_slots(review_slots, source_ids, definitions['review'])
    sr = safety_review(source_ids, episode_slots)
    resume = nullable(deepcopy(ref)) if stop_pending else {'type': 'null'}
    updates = request_updates(source_ids, request_ids, question_target_ids, clarification_ids)
    common = {'safetyReview': sr, 'reviews': reviews, 'resume': resume, 'requestUpdates': updates}
    goals = [obj({'kind': lit('NEW'), 'move': enum(MOVES), 'focus': string(160),
                  'targetSourceId': optional_id(source_ids), 'relatedGoalId': optional_id(goal_ids),
                  'contextSourceIds': ids(source_ids, 4)})]
    if goal_ids:
        goals.extend([
            obj({'kind': lit('CONTINUE'), 'goalId': enum(goal_ids)}),
            obj({'kind': lit('REVISIT'), 'goalId': enum(goal_ids),
                 'newEvidence': array(deepcopy(ref), 4, 1)})])
    result = [
        tool('write_turn', obj({**deepcopy(common), 'goal': {'anyOf': goals}}), descriptions, definitions),
        tool('assess_completion', obj(deepcopy(common)), descriptions, definitions)]
    presentations = []
    regular = [p for scope in ('CBT', 'DIRECTION', 'STOP', 'CONTROL') for p in pending_groups.get(scope, [])]
    if regular and request_ids:
        presentations.append(obj({**deepcopy(common), 'targetPendingId': enum(regular),
            'mode': enum(['EXAMPLE', 'EXPLANATION']), 'requestIds': array(enum(request_ids), 2, 1),
                             'stage': enum(['ASK_TARGET', 'WAIT_FOR_DECISION'])}))
    if pending_groups.get('SAFETY'):
        presentations.append(obj({
            'targetPendingId': enum(pending_groups['SAFETY']), 'mode': lit('EXPLANATION'),
            'requestType': enum(['REQUEST_EXAMPLE', 'REQUEST_EXPLANATION']), 'requestSource': deepcopy(ref),
            'requestUpdates': deepcopy(updates),
            'safety': obj({'basis': lit('PRESERVE_PENDING'), 'contextReferences': array(deepcopy(ref), 4)})}))
    if presentations:
        result.append(tool('present_pending_question', obj({'presentation': {'anyOf': presentations}}),
                           descriptions, definitions if regular and request_ids else None))
    result.append(tool('respond_safety', obj({'safety': safety_directive(source_ids, episode_ids)}), descriptions))
    controls = [
        obj({'type': lit('STOP'), 'safetyReview': deepcopy(sr), 'source': deepcopy(ref)}),
        obj({'type': lit('USER_DIRECTION'), **deepcopy(common),
             'reason': string(160), 'contextReferences': array(deepcopy(ref), 4)})]
    if request_ids:
        controls.append(obj({'type': lit('REQUEST_TARGET'), **deepcopy(common),
                             'requestIds': array(enum(request_ids), 2, 1),
                             'stage': enum(['ASK_TARGET', 'WAIT_FOR_DECISION'])}))
    controls.append(obj({'type': lit('ASSESSMENT_TARGET'), **deepcopy(common),
        'decision': enum(['ASK_CONFIRMATION', 'WAIT_FOR_CONFIRMATION', 'RECORD_CHANGE_REQUIRED']),
        'source': nullable(deepcopy(ref))}))
    if question_target_ids:
        controls.append(obj({'type': lit('GAP_ANSWER_WAIT'), **deepcopy(common),
                             'targetPendingId': enum(question_target_ids)}))
    result.append(tool('respond_control', obj({'control': {'anyOf': controls}}), descriptions, definitions))
    return result

def assessment_review_tools(*, candidate_id, source_ids, episode_ids, descriptions):
    return [
        tool('accept_assessment', obj({'candidateId': lit(candidate_id)}), descriptions),
        tool('reject_assessment', obj({'candidateId': lit(candidate_id), 'reason': string(240)}), descriptions),
        tool('respond_safety', obj({'safety': safety_directive(source_ids, episode_ids)}), descriptions)]

def argument_repair_tools(*, request_ids, descriptions):
    result = []
    if request_ids:
        result.append(tool('repair_presentation_binding',
                      obj({'requestIds': array(enum(request_ids), 2, 1)}), descriptions))
    result.append(tool('decline_action', obj({'reason': string(240)}), descriptions))
    return result

def safety_recheck_tools(*, source_ids, episode_slots, episode_ids, continuation_allowed, descriptions):
    result = []
    if continuation_allowed:
        result.append(tool('continue_selected_tool',
            obj({'safetyReview': safety_review(source_ids, episode_slots)}), descriptions))
    result.extend([
        tool('respond_safety', obj({'safety': safety_directive(source_ids, episode_ids)}), descriptions),
        tool('decline_action', obj({'reason': string(240)}), descriptions)])
    return result

def assessor_schema(reference_ids, allowed_distortion_codes, gap_allowed):
    refs = ids(reference_ids, 8)
    common = {'automaticThoughtExcerpt': string(240), 'usedReferenceIds': refs,
              'calibratedThought': string(800)}
    branches = []
    if allowed_distortion_codes:
        branches.append(obj({'type': lit('DISTORTION_PRESENT'), **deepcopy(common),
            'unsupportedExtension': string(240), 'matchedDistortionCode': enum(allowed_distortion_codes),
            'definitionMatchReason': string(400), 'boundaryExplanation': string(400)}))
    branches.append(obj({'type': lit('NO_CLEAR_DISTORTION'), **deepcopy(common),
                         'withinFactBoundaryReason': string(500)}))
    if gap_allowed:
        branches.append(obj({'type': lit('FACT_BOUNDARY_REQUIRED'), 'usedReferenceIds': deepcopy(refs),
            'missingFact': string(180), 'whyDecisionDependsOnIt': string(300), 'question': string(220)}))
    return obj({'result': {'anyOf': branches}})

def writer_schema(mode):
    # Mirror the existing Writer format contract at the provider boundary.
    # Local independent checks remain authoritative (including trailing LF,
    # which some regex engines allow immediately before the final $ anchor).
    def statement(limit):
        return {**string(limit), 'pattern': WRITER_STATEMENT_PATTERN}
    question = {**string(220), 'pattern': WRITER_QUESTION_PATTERN}
    if mode == 'NORMAL':
        result = obj({'preface': nullable(statement(100)), 'question': question})
    elif mode == 'EXAMPLE':
        option = {**statement(130), 'description':
            '기존 질문에 어떻게 답할 수 있는지 보여 주는 가상의 예시 답변입니다. '
            '다른 보기와 상황이나 관점이 달라야 합니다. 가정임을 문장 안에서 분명히 하며 '
            '사용자 실제 사실을 추가하지 않습니다. 사용자 원문을 실제 경험처럼 재서술하지 않습니다.'}
        question['description'] = ('두 예시 중 가까운 쪽 또는 둘 다 아님을 선택하는 질문 하나입니다. '
            'existingQuestion이나 focus의 원래 탐색 질문을 그대로 반복하지 않습니다. '
            '원래 질문에 대한 새로운 근거를 요구하는 대신 방금 제시한 두 가상 보기의 선택을 묻습니다.')
        result = obj({'optionA': deepcopy(option), 'optionB': deepcopy(option), 'question': question})
        result['description'] = ('EXAMPLE 표현 작업입니다. effectivePlan의 정보 목표는 기존 질문이 '
            '무엇을 알아보려 했는지를 설명하며 사용자 경험을 새로 확정하라는 뜻이 아닙니다. '
            '그 질문의 뜻을 두 개의 서로 다른 가상 답변으로 설명하고, 마지막에는 보기 선택만 묻습니다.')
    elif mode == 'EXPLANATION':
        result = obj({'explanation': statement(220), 'question': question})
    else:
        raise ValueError('unknown_presentation_mode')
    return obj({'result': result})

def response_format(name, schema):
    if not name or len(name) > 64 or any(not (c.isascii() and (c.isalnum() or c in '_-')) for c in name):
        raise ValueError('invalid_provider_schema_name')
    return {'type': 'json_schema', 'json_schema': {
        'name': name, 'strict': True, 'schema': deepcopy(schema)}}
# Runtime wrapper: exact normative review definition with current view bindings.
def review_schema(slot_keys, source_ids, goal_ids, question_target_ids, prior_request_ids, prior_correction_ids):
    return fixed(slot_keys, 'slot', review_definition(source_ids, goal_ids,
        question_target_ids, prior_request_ids, prior_correction_ids))
