"""Opaque new question IDs; the legacy R2-R5 frames below are READ-ONLY decoders.

Layout: R5 + kind(Q/E/X/B/S/C) + 27 base36 digits + '_' + 16 hex.
R5_WIDTHS describes move, target, preceding signals, gap flag, safety kind/count,
origin/position, origin codepoint span, origin/thought revision tags, and the
preceding answer revision tag for presentation fulfilment. No raw evidence,
review JSON or natural-language memory is embedded in question codes.
Indices 0..1021 are history; origin 1022=situation, 1023=thought.
Legacy fields are not reused to encode new Agent memory.
Digest binds record identity, immutable ordered question/code/purpose structure
and the new question, NOT answer text/timestamps or mutable record contents.
It detects corruption, not malicious forgery; authoritative Spring history is
the trust boundary. Compact revision tags are not exact durable state storage.
"""
import re
from .contracts import Move, SignalType, CompletionTechnicalError
from .diagnostics import canonical, sha

KINDS='QEBS'
SAFETY_KINDS=[None,'subject','currentness','intent','immediacy']
WIDTHS=[3,10,5,1,3,2,10,10]
CODE_LENGTH=47
R5_WIDTHS=[3,10,7,1,3,1,10,10,12,7,24,24,16]
def base36(n):
    out=''
    while n:
        n,d=divmod(n,36); out='0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'[d]+out
    return out or '0'
def structure(history):
    return [[x.question_code,x.question,x.question_purpose.value] for x in history]
def binding(request,history,body,question):
    return sha(canonical([request.record.record_id,structure(history),body,question]))[:16].upper()
def legacy_binding(request,history,body,question):
    old=[{'code':x.question_code,'question':x.question,'purpose':x.question_purpose.value,'answer':x.answer} for x in history]
    return sha(canonical([request.record.model_dump(mode='json'),old,body,question]))[:8].upper()
def encode(state,request,move,kind,question,**kwargs):
    """Opaque question identity only. Full controls are in the committed saver head."""
    from uuid import uuid4
    if kind not in 'QEXBSCU':
        raise CompletionTechnicalError('unknown_question_kind')
    return 'AG'+kind+'_'+uuid4().hex.upper()
def decode(request,history,index,diagnostics):
    item=history[index]; code=item.question_code
    status='PUBLIC_HISTORY'; control=None
    try:
        if code.startswith('R5'):
            status='CURRENT_CORRUPT'
            if not re.fullmatch(r'R5[QEXBSC][0-9A-Z]{27}_[0-9A-F]{16}',code):
                raise ValueError('frame_format')
            body,digest=code.split('_')
            if binding(request,history[:index],body,item.question)!=digest:
                raise ValueError('frame_binding')
            value=int(body[3:],36); rev=[]
            for width in reversed(R5_WIDTHS):
                rev.append(value & ((1<<width)-1)); value>>=width
            move,target,mask,gap,kind,count,origin,position,start,length,origin_rev,thought_rev,previous_rev=reversed(rev)
            if value!=1 or position!=index or target>index or move>=len(Move) or kind>=len(SAFETY_KINDS):
                raise ValueError('frame_bounds')
            if code[2]=='S' and (not kind or count!=1 or not 1<=length<=96 or (origin>=index and origin not in (1022,1023))):
                raise ValueError('safety_control_invalid')
            if code[2]=='B' and (not gap or list(Move)[move]!=Move.FACT_CERTAINTY_CHECK):
                raise ValueError('boundary_control_invalid')
            control={'kind':code[2],'move':list(Move)[move].value,'targetIndex':target,
                'previousSignals':[s.value for i,s in enumerate(SignalType) if mask & (1<<i)],
                'gapUsed':bool(gap),'safetyKind':SAFETY_KINDS[kind],'safetyCount':count,'safetyOrigin':origin,
                'originStart':start,'originLength':length,'originRevisionTag':origin_rev,
                'thoughtRevisionTag':thought_rev,'previousRevisionTag':previous_rev}
            status='CURRENT_VALID'
        elif code.startswith('R4'):
            status='CURRENT_CORRUPT'
            if not re.fullmatch(r'R4[QEBS][0-9A-Z]{10}_[0-9A-F]{16}',code):
                raise ValueError('frame_format')
            body,digest=code.split('_')
            if binding(request,history[:index],body,item.question)!=digest:
                raise ValueError('frame_binding')
            value=int(body[3:],36); rev=[]
            for width in reversed(WIDTHS):
                rev.append(value & ((1<<width)-1)); value>>=width
            move,target,mask,gap,kind,count,origin,position=reversed(rev)
            if value!=1 or position!=index or target>index or move>=len(Move) or kind>=len(SAFETY_KINDS) or count>1:
                raise ValueError('frame_bounds')
            if code[2]=='S' and (not kind or count!=1 or (origin>=index and origin not in (1022,1023))):
                raise ValueError('safety_control_invalid')
            if code[2]=='B' and (not gap or list(Move)[move]!=Move.FACT_CERTAINTY_CHECK):
                raise ValueError('boundary_control_invalid')
            control={'kind':code[2],'move':list(Move)[move].value,'targetIndex':target,
                'previousSignals':[s.value for i,s in enumerate(SignalType) if mask & (1<<i)],
                'gapUsed':bool(gap),'safetyKind':SAFETY_KINDS[kind],'safetyCount':count,'safetyOrigin':origin}
            status='LEGACY_VALID'
        elif code.startswith(('R2','R3')):
            status='CURRENT_CORRUPT'
            old_moves=list(Move)+[Move.USER_DIRECTION,Move.USER_DIRECTION]
            if code.startswith('R3'):
                if not re.fullmatch(r'R3[QES][0-9A-Z]+',code):
                    raise ValueError('legacy_format')
                packed=int(code[3:],36); value,digest=packed>>32,packed & 0xFFFFFFFF
                body=code[:3]+base36(value)
                if int(legacy_binding(request,history[:index],body,item.question),16)!=digest:
                    raise ValueError('legacy_binding')
                bits=bin(value)[3:]
                if len(bits)<44:
                    raise ValueError('legacy_truncated_header')
                move=int(bits[:4],2); gap=bool(int(bits[9:10],2)); count=int(bits[10:12],2); kind=code[2]
            else:
                match=re.fullmatch(r'R2([0-7])([QES])([01])([0-2])([0-8])([0-9A-F]{8})_([UCN][0-9A-Z]+)_([UCN][0-9A-Z]+)_([UCN][0-9A-Z]+)_([UCN][0-9A-Z]+)_([0-9A-F]{8})',code)
                if not match or match[11]!=legacy_binding(request,history[:index],code.rsplit('_',1)[0],item.question):
                    raise ValueError('legacy_binding')
                move=int(match[1]); kind=match[2]; gap=match[3]=='1'; count=int(match[4])
            if move>=len(old_moves) or count>1:
                raise ValueError('legacy_control_bounds')
            actual=old_moves[move]
            if actual==Move.FACT_CERTAINTY_CHECK:
                kind='B'
            control={'kind':kind,'move':actual.value,'targetIndex':index,
                'previousSignals':[],'gapUsed':gap,'safetyKind':'intent' if kind=='S' else None,
                'safetyCount':count,'safetyOrigin':index-1 if index else 1023}
            status='LEGACY_VALID'
        elif re.match(r'R[0-9]',code):
            status='UNSUPPORTED_VERSION'
    except (ValueError,IndexError,TypeError):
        control=None
    result={'status':status,'control':control}
    diagnostics.emit('checkpoint_decode',questionCode=code,**result)
    return result
