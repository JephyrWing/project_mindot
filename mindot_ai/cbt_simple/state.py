"""Ephemeral execution memory; Spring is the durable authority."""
import asyncio
from copy import deepcopy
from dataclasses import dataclass,field
from time import monotonic
from .contracts import ProtocolError

@dataclass
class Runtime:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    snapshot: dict | None = None
    successes: dict = field(default_factory=dict)
    fingerprints: dict = field(default_factory=dict)
    failures: dict = field(default_factory=dict)
    expires: float = 0
    closed: bool = False

class Registry:
    def __init__(self,ttl=600):self.ttl=ttl;self.sessions={}
    def acquire(self,sid):
        now=monotonic()
        for key,r in list(self.sessions.items()):
            if not r.lock.locked() and r.expires<now:self.sessions.pop(key,None)
        r=self.sessions.setdefault(sid,Runtime());r.expires=now+self.ttl;return r
    def remove(self,sid):
        r=self.sessions.pop(sid,None)
        if r:r.closed=True
    def touch(self,r):r.expires=monotonic()+self.ttl

def require(value,code):
    if not value:raise ProtocolError(code)

def candidate_boundary(candidate,snapshot,diagnostics):
    """Copy a schema-validated Assessor result; return the processed candidate and eligibility.

    graph.assess_completion validates the raw schema once before this boundary.
    Only an exact no-op BEFORE correction is removed, before USER citation checks.
    """
    candidate=deepcopy(candidate)
    correction=candidate['beforeCorrection']
    if correction is not None and correction['text']==snapshot['record']['automaticThought']:
        candidate['beforeCorrection']=None
        diagnostics.emit('candidate_normalized',normalizedFields=['beforeCorrection'])
    after=candidate['afterText'];evidence=candidate['afterEvidence'];suggestions=candidate['suggestions']
    require(after is None or bool(after.strip()),'blank_after')
    if after is None:
        require(not evidence and not suggestions and candidate['assessmentType']=='UNDETERMINED','null_after_shape')
        return candidate,False
    require(bool(evidence),'missing_user_evidence')
    codes=[s['code'] for s in suggestions]
    require(len(codes)==len(set(codes)),'duplicate_suggestion')
    require(bool(codes)==(candidate['assessmentType']=='DISTORTION_SUGGESTED'),'suggestion_assessment_shape')
    citations=list(evidence)
    if candidate['beforeCorrection']:
        require(bool(candidate['beforeCorrection']['text'].strip()),'blank_before_correction')
        citations.append(candidate['beforeCorrection']['evidence'])
    messages={m['messageNumber']:m for m in snapshot['messages']}
    for p in citations:
        m=messages.get(p['messageNumber'])
        require(m and m['role']=='USER' and p['quote'].strip() and p['quote'] in m['content'],'invalid_user_quote')
    return candidate,True

def render(proposal):
    text=f"처음 생각: {proposal['beforeText']}\n알아차리고 수정한 생각: {proposal['afterText']}\n{proposal['comparisonExplanation']}"
    if proposal.get('beforeCorrection'):text=f"처음 기록한 원문: {proposal['originalBeforeText']}\n"+text
    for s in proposal['suggestions']:text+='\n'+s['code']+': '+s['explanation']
    if not proposal['suggestions']:
        text+='\n'+('뚜렷한 인지왜곡 유형을 제안하지 않습니다.' if proposal['assessmentType']=='NO_CLEAR_DISTORTION' else '현재 근거로 특정 인지왜곡 유형을 판단하기 어렵습니다.')
    return text+'\n수정한 생각이 자신의 뜻에 맞는지 확인하고, 유형별 수락 또는 거부를 선택해 저장해 주세요.'
