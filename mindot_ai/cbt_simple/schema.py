"""Supplied contract, mechanically wrapped for Chat Completions."""
import json
from pathlib import Path
CONTRACT=json.loads((Path(__file__).parent/'model-contracts.json').read_text(encoding='utf-8'))
DEFINITIONS=json.loads((Path(__file__).parent/'distortion-definitions.json').read_text(encoding='utf-8'))
def select_tools():return [dict(type='function',function=dict(t,strict=True)) for t in CONTRACT['tools']]
def select_schemas():return {t['name']:t['parameters'] for t in CONTRACT['tools']}
def assessor_schema():return CONTRACT['assessorOutput']
def review_schema():return CONTRACT['assessmentReviewOutput']
def response_format(name,value):return dict(type='json_schema',json_schema=dict(name=name,strict=True,schema=value))

def validate(value,shape):
    if 'anyOf' in shape:
        for branch in shape['anyOf']:
            try:validate(value,branch);return
            except ValueError:pass
        raise ValueError('schema_union')
    kinds=shape.get('type');kinds=kinds if isinstance(kinds,list) else [kinds]
    types={'object':isinstance(value,dict),'array':isinstance(value,list),'string':isinstance(value,str),
           'integer':type(value)is int,'boolean':type(value)is bool,'null':value is None}
    if not any(types.get(k,False) for k in kinds):raise ValueError('schema_type')
    if 'enum' in shape and value not in shape['enum']:raise ValueError('schema_enum')
    if isinstance(value,str):
        if len(value)<shape.get('minLength',0):raise ValueError('schema_min_length')
        if 'maxLength' in shape and len(value)>shape['maxLength']:raise ValueError('schema_max_length')
    if isinstance(value,dict):
        if set(value)!=set(shape['required']):raise ValueError('schema_fields')
        for k,v in value.items():validate(v,shape['properties'][k])
    if isinstance(value,list):
        for item in value:validate(item,shape['items'])
