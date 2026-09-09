"""Initial estimated bounds, not an assertion about all Unicode/token combinations."""
import json
import math
from . import llm
from .contracts import CompletionTechnicalError


def largest_argument(schema,root,texts):
    """Max array/field counts and longest enum; one branch, never four tool outputs.

    Excerpts use complete windows from readable source text. Free Korean fields
    use full-length neutral prose plus a 25%/64-token reserve at the admission
    boundary. This estimate is deliberately distinct from a universal token
    upper bound; a provider truncation is still an uncommittable technical error.
    """
    if '$ref' in schema:
        return largest_argument(root['$defs'][schema['$ref'].split('/')[-1]],root,texts)
    if 'anyOf' in schema:
        values=[largest_argument(branch,root,texts) for branch in schema['anyOf']]
        return max(values,key=lambda v:llm.token_count(json.dumps(v,ensure_ascii=False)))
    if 'enum' in schema:
        return max(schema['enum'],key=llm.token_count)
    kind=schema['type']
    if kind=='object':
        result={key:largest_argument(value,root,texts) for key,value in schema['properties'].items()}
        if 'exactExcerpt' in result:
            # Full sliding windows keep negation intact; never actual input truncation.
            candidates=[text[i:i+96] for text in texts for i in range(max(1,len(text)-95))]
            result['exactExcerpt']=max(candidates,key=llm.token_count,default='원문')
        return result
    if kind=='array':
        return [largest_argument(schema['items'],root,texts) for _ in range(schema['maxItems'])]
    if kind=='string':
        size=schema.get('maxLength',160)
        prose='현재 원문에서 확인한 내용과 아직 알 수 없는 범위를 구분하여 검토합니다. '
        return (prose*(size//len(prose)+1))[:size]
    if kind=='integer':
        return 9999  # Public answer max 4000; four digits conservatively cover occurrence.
    if kind=='boolean':
        return True
    return None


def agent_admission(messages,tools,sources,diagnostics,phase='SELECT'):
    # ChatOpenAI's exact kwargs are checked again by CaptureCompletions at SDK entry.
    from .provider import agent_wire
    wire=agent_wire(messages,tools,phase)
    measured=llm.measure(wire)
    diagnostics.emit('capacity_input_preflight',phase=phase,input=measured)
    if measured['serializedInputTokensWithFramingReserve']>llm.INPUT_TOKEN_LIMIT or measured['serializedRequestBytes']>llm.REQUEST_BYTE_LIMIT:
        raise CompletionTechnicalError('agent_required_context_capacity')
    # Scan source windows once; subsequent schema references reuse the maximum.
    windows=[s['text'][i:i+96] for s in sources for i in range(max(1,len(s['text'])-95))]
    texts=[max(windows,key=llm.token_count,default='원문')]
    examples=[]
    for tool in wire['tools']:
        schema=tool['function']['parameters']
        value=largest_argument(schema,schema,texts)
        tokens=llm.token_count(json.dumps(value,ensure_ascii=False))
        examples.append({'tool':tool['function']['name'],'maximumCountArgumentEstimate':tokens,
                         'withReserve':math.ceil(tokens*1.25)+64})
    output=max(x['withReserve'] for x in examples)
    diagnostics.emit('capacity_preflight',input=measured,outputBranches=examples,
        maximumSingleBranchOutputEstimate=output,universalUnicodeBound=False)
    if output>llm.PHASES[phase][1] or measured['contextReservation']>128000:
        raise CompletionTechnicalError('agent_tool_argument_output_capacity')
    return measured
