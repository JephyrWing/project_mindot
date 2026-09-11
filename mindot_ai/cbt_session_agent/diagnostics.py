"""Request-scoped diagnostics; observation failures never own product state."""
from contextvars import ContextVar
from dataclasses import dataclass,field
import hashlib
import json
from typing import Any,Callable

diagnostic_sink=ContextVar('cbt_session_agent_diagnostic_sink',default=None)

def canonical(value:Any)->str:
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))

def sha(value:str)->str:
    return hashlib.sha256(value.encode('utf-8')).hexdigest()

@dataclass
class Diagnostics:
    sink:Callable[[dict],None]|None=field(default_factory=lambda:diagnostic_sink.get())
    events:list[dict]=field(default_factory=list)
    counters:dict[str,int]=field(default_factory=dict)

    def count(self,key:str)->None:
        self.counters[key]=self.counters.get(key,0)+1

    def emit(self,event:str,**data:Any)->None:
        row={'event':event,**data}
        self.events.append(row)
        if self.sink:
            try:self.sink(row)
            except Exception:
                self.count('audit_sink_failure')
