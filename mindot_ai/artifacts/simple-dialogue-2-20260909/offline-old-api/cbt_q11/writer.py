"""Full Korean sentence rendering, bound to one validated goal and mode."""
import re
import unicodedata
from copy import deepcopy
from .contracts import CompletionTechnicalError
from .llm import call, validate_schema, budget, request_kwargs
from .provider_schemas import (writer_schema, response_format, WRITER_QUESTION_PATTERN,
    WRITER_NON_ASCII_QUESTION_MARKS, WRITER_STATEMENT_FORBIDDEN)
from .prompts import QUESTION_WRITER_SYSTEM_PROMPT, PHASE_PROMPTS
from .diagnostics import sha

def valid_question(value):
    return isinstance(value,str) and 1<=len(value)<=220 and question_shape(value)

def question_shape(value):
    return (value.count('?')==1
            and bool(re.search(WRITER_QUESTION_PATTERN,value)) and not any(c in value for c in '\n\r')
            and bool(re.search('[가-힣]',value)))

def render(raw,plan,diagnostics):
    mode=plan.presentation_mode
    validate_schema(raw,writer_schema(mode))
    value=raw['result']
    if not valid_question(value['question']):
        diagnostics.invalid('writer','question','invalid_question')
        raise CompletionTechnicalError('no_verified_same_goal_mode_fallback')
    if mode=='NORMAL':
        if value['preface'] is not None and any(c in value['preface'] for c in WRITER_STATEMENT_FORBIDDEN):
            raise CompletionTechnicalError('invalid_preface')
        parts=[v for v in (value['preface'],value['question']) if v]
    elif mode=='EXAMPLE':
        if normalized(value['optionA'])==normalized(value['optionB']) or any(c in value[k] for k in ('optionA','optionB') for c in WRITER_STATEMENT_FORBIDDEN):
            raise CompletionTechnicalError('invalid_example_options')
        parts=[value['optionA'],value['optionB'],value['question']]
    else:
        if any(c in value['explanation'] for c in WRITER_STATEMENT_FORBIDDEN):
            raise CompletionTechnicalError('invalid_explanation')
        parts=[value['explanation'],value['question']]
    message=' '.join(parts)
    if len(message)>500:
        raise CompletionTechnicalError('question_dto_length')
    diagnostics.emit('wording_render',effectivePlan=plan.model_dump(by_alias=True,mode='json'),
                     mode=mode,finalQuestion=message,fields=value)
    return message


def normalized(text):
    return ' '.join(unicodedata.normalize('NFKC',text).split()).casefold()

def repair_issue(raw,plan):
    # Classify shape before length repair. Provider format patterns duplicate
    # the independent checks below, so omit them here only: an empty question
    # remains repairable, but length never excuses another format defect.
    # The provider schema and final render retain all constraints unchanged.
    schema=writer_schema(plan.presentation_mode)
    def shape(node):
        if isinstance(node,dict):
            return {k:shape(v) for k,v in node.items() if k not in ('minLength','maxLength','pattern')}
        if isinstance(node,list):
            return [shape(v) for v in node]
        return node
    validate_schema(raw,shape(schema))
    value=raw['result']
    limits={'question':220,'preface':100,'optionA':130,'optionB':130,'explanation':220}
    issues=[k+':EMPTY_OR_OVERLENGTH' for k,v in value.items() if v is not None and
            (len(v)>limits[k] or (k!='preface' and not v.strip()))]
    if plan.presentation_mode=='EXAMPLE' and normalized(value['optionA'])==normalized(value['optionB']):
        issues.append('EXACT_NORMALIZED_DUPLICATE_EXAMPLES')
    # A second non-allowlisted defect cannot be rehabilitated by a length repair.
    defects=[]
    for key,text in value.items():
        if text is None:
            continue
        # Length never suppresses independent format constraints. The genuinely
        # empty question has no ending to inspect; other fields still do.
        if key=='question' and text.strip():
            if text.count('?')!=1: defects.append('question:QUESTION_MARK_COUNT')
            if not re.search(r'(요|까)\?$',text): defects.append('question:ENDING')
            if not re.search('[가-힣]',text): defects.append('question:KOREAN')
        if any(c in text for c in '\n\r'): defects.append(key+':NEWLINE')
        if '？' in text: defects.append(key+':FULLWIDTH_QUESTION_MARK')
        if any(c in text for c in WRITER_NON_ASCII_QUESTION_MARKS if c!='？'):
            defects.append(key+':NON_ASCII_QUESTION_MARK')
        if key!='question' and '?' in text: defects.append(key+':QUESTION_MARK')
    if defects: raise ValueError('non_allowlisted:'+','.join(defects))
    return issues

def payload(request,state,plan,view=None):
    from .policy import select_sources
    from . import views, validity, safety
    table=deepcopy(view['sources'] if view else select_sources(request,state))
    # A Writer receives an expression task, not the Agent's decision workspace.
    # The accepted state and complete Agent view stay unchanged. Preserve source
    # bodies/validity and goal provenance while excluding lifecycle decisions.
    raw_plan=plan.model_dump(by_alias=True,mode='json')
    binding_fields=('scope','presentationMode','goalId','pendingId','targetId','targetQuestionCode',
        'parentQuestionCode','rootGapQuestionCode','rootControlId','clarificationId','controlPurpose',
        'controlStage','episodeId','targetSourceBinding','contextSourceBindings','sourceBindingStatus',
        'presentationQuestionBinding','presentationRequestIds','presentationRequestKind',
        'assessmentTargetDecision','thoughtRevision','semanticKey','contextQuestionCodes','prefaceRequired')
    roles={
        'NORMAL':{'preface':'OPTIONAL_CONTEXT_STATEMENT_OR_NULL','question':'ONE_CURRENT_INFORMATION_GOAL_QUESTION'},
        'EXAMPLE':{'optionA':'EXPLICITLY_FICTIONAL_ILLUSTRATIVE_ANSWER_A',
            'optionB':'DISTINCT_EXPLICITLY_FICTIONAL_ILLUSTRATIVE_ANSWER_B',
            'question':'CHOOSE_BETWEEN_THE_TWO_ILLUSTRATIONS_OR_NEITHER'},
        'EXPLANATION':{'explanation':'EXPLAIN_THE_EXISTING_QUESTION',
            'question':'ONE_SAME_PURPOSE_CONFIRMATION_QUESTION'}}
    # existingQuestion owns the presentation target's text. A source table's
    # historical question is authoritative data and is never edited to dedupe it.
    focus=plan.focus; focus_reference=None
    if plan.existing_question is not None and focus==plan.existing_question:
        focus=None; focus_reference={'field':'existingQuestion'}
    else:
        matched=next((source for source in table if source['text']==focus),None)
        if matched is not None:
            focus=None; focus_reference={'sourceId':matched['sourceId'],'field':'text'}
    context={key:raw_plan[key] for key in ('move','questionPurpose','semanticRouteType',
        'answerSource','evidencePolarity','targetDomain')}
    context.update(focus=focus,focusReference=focus_reference,focusSha256=sha(plan.focus))
    codes={*plan.context_question_codes,plan.target_question_code}
    related=[]
    for question in state.prior_questions:
        if not (plan.goal_id is not None and question.get('goalId')==plan.goal_id or
                question.get('questionCode') in codes):
            continue
        ref={key:deepcopy(question[key]) for key in (*binding_fields,'questionCode',
            'questionPurpose','semanticRouteType','publicHistoryQuestionBinding') if key in question}
        for key in ('question','focus'):
            if isinstance(question.get(key),str): ref[key+'Sha256']=sha(question[key])
        related.append(views.pointers(ref))
    active=views.evidence(state)
    visible={source.get('sourceKey') for source in table}
    value={'writerContract':'EXPRESSION_ONLY_V1','mode':plan.presentation_mode,
        'outputFieldRoles':deepcopy(roles[plan.presentation_mode]),
        'existingQuestion':plan.existing_question,'underlyingGoalContext':context,
        'effectivePlan':views.pointers({key:raw_plan[key] for key in binding_fields}),
        'relatedQuestions':related,'requestIds':list(plan.presentation_request_ids),
        'record':{k:v for k,v in request.record.model_dump(by_alias=True,mode='json').items()
                  if k not in ('situation','automaticThought')},
        'sources':table,'sourceValidity':validity.source_validity(state,table),
        'activeContributions':active,
        'inactiveContributions':[item for item in views.evidence(state,inactive=True) if item['sourceKey'] in visible],
        'supplementalEvidence':[item for item in active if item['evidenceKind']=='GAP'],
        'correctionProjection':deepcopy(state.correction_projection),
        'priorCorrections':views.corrections(state),'historyRecovery':views.history_recovery(state),
        **views.pointers(safety.catalog(state,table))}
    if plan.scope=='SAFETY':
        value['episode']=views.pointers(state.episodes[plan.episode_id])
    return value

async def write(request,state,plan,diagnostics,model=None,*,view=None):
    if state.complete and plan.presentation_mode=='NORMAL' and plan.scope=='CBT':
        raise CompletionTechnicalError('complete_cannot_normal_explore')
    return await write_payload(payload(request,state,plan,view),plan,diagnostics,model)

async def write_payload(data,plan,diagnostics,model=None):
    frozen=deepcopy(data)
    fmt=response_format('question_'+plan.presentation_mode.lower(),writer_schema(plan.presentation_mode))
    wire=request_kwargs('writer',QUESTION_WRITER_SYSTEM_PROMPT,fmt,frozen)
    budget().reserve_path([('WRITER',wire)])
    result=await call('writer',QUESTION_WRITER_SYSTEM_PROMPT,fmt,frozen,diagnostics,model,phase='WRITER')
    if result.status!='USABLE':
        raise CompletionTechnicalError('writer_'+result.status)
    raw=deepcopy(result.output)
    # Empty optional preface is a permitted mechanical null, not model repair.
    normalize_preface(raw,diagnostics)
    try:
        issues=repair_issue(raw,plan)
    except (ValueError,KeyError,TypeError):
        raise CompletionTechnicalError('writer_nonrepairable_shape') from None
    if issues:
        scope=budget(); scope.reserve_extra('WRITER_REPAIR')
        repair={'frozenInput':frozen,'failedResult':raw,'allowlistIssues':issues,'budget':scope.snapshot()}
        prompt=PHASE_PROMPTS['WRITER_REPAIR']
        scope.reserve_path([('WRITER_REPAIR',request_kwargs('writer',prompt,fmt,repair,'WRITER_REPAIR'))])
        response=await call('writer',prompt,fmt,repair,diagnostics,model,phase='WRITER_REPAIR')
        if response.status!='USABLE':
            raise CompletionTechnicalError('writer_repair_'+response.status)
        raw=response.output
        raw=deepcopy(raw); normalize_preface(raw,diagnostics)
    try:
        return render(raw,plan,diagnostics)
    except (ValueError,TypeError,KeyError):
        diagnostics.invalid('writer','result','invalid_writer_result')
        raise CompletionTechnicalError('writer_result_invalid') from None

def normalize_preface(raw,diagnostics):
    value=raw.get('result')
    if isinstance(value,dict) and value.get('preface')=='':
        value['preface']=None
        diagnostics.emit('writer_mechanical_normalization',field='preface',operation='EMPTY_STRING_TO_NULL')
