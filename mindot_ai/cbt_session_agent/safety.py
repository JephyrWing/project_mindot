"""Narrow deterministic detection for explicit current emergencies."""
from dataclasses import asdict,dataclass
import re

@dataclass(frozen=True)
class SafetyDecision:
    action:str
    reason:str|None=None
    level:str='NONE'
    evidence:tuple=()
    unresolved:str|None=None
    primary_trigger:dict|None=None
    clarification_goal:str|None=None
    episode_id:str|None=None
    concern:str='SELF_HARM'
    def dump(self):return asdict(self)

def detector(text):
    for clause in re.split(r'[.!?\n]|하지만|그런데',text or ''):
        if re.search(r'아니|않|없|예전|과거|어제|가정|만약|라면|라고|라는|인용|소설|영화|수업|친구|동료가|그가|그녀|["“”]',clause):
            continue
        if not re.search(r'지금|당장',clause):
            continue
        for reason,pattern in [
            ('IMMEDIATE_DANGER',r'(?:약을|약물).*(?:많이\s*삼켰|한꺼번에\s*먹었)|피가\s*멈추지'),
            ('SUICIDE',r'(?:옥상|난간).*(?:뛰어내리려|뛰어내리고)|목을\s*매고\s*있'),
            ('SELF_HARM',r'(?:칼|면도날).*(?:손목|팔).*(?:긋고\s*있|베고\s*있)'),
            ('HARM_TO_OTHERS',r'(?:칼|총).*(?:찌르고\s*있|쏘려)')]:
            if re.search(pattern,clause):
                return SafetyDecision('STOP',reason,'CRISIS',primary_trigger={
                    'start':text.index(clause),'length':len(clause),'exactExcerpt':clause})
    return None
