"""Strict JSON and token helpers for the active CBT provider boundary."""
from dataclasses import dataclass
import json
import tiktoken

_encoding=None

def token_count(value):
    global _encoding
    if _encoding is None:_encoding=tiktoken.get_encoding('o200k_base')
    text=value if isinstance(value,str) else json.dumps(value,ensure_ascii=False)
    return len(_encoding.encode(text,disallowed_special=()))

@dataclass(frozen=True)
class ComponentResult:
    status:str
    output:dict|None=None
    error:str|None=None

def unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('duplicate_json_key')
        result[key]=value
    return result

def parse(raw):
    try:
        if len(raw['choices'])!=1:raise ValueError('choice_count')
        choice=raw['choices'][0];message=choice['message']
        if message.get('refusal'):return ComponentResult('REFUSAL')
        if choice.get('finish_reason')!='stop':return ComponentResult('INCOMPLETE')
        content=message['content']
        if not isinstance(content,str):raise ValueError('content_not_string')
        value=json.loads(content,object_pairs_hook=unique_object)
        if not isinstance(value,dict):raise ValueError('root_not_object')
        return ComponentResult('USABLE',value)
    except (KeyError,ValueError,TypeError,IndexError):
        return ComponentResult('PARSE_ERROR')
