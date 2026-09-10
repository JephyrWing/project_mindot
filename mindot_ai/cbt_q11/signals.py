"""Mechanical quote/negation guard for Agent-selected controls, not a semantic router."""
import re


def eligible_control(text, excerpt, start=None):
    """Mechanical quote/negation exclusion; not a semantic classifier."""
    start = text.find(excerpt) if start is None else start
    if start < 0:
        return False
    quoted = [m.span() for m in re.finditer(r'["“「『].*?["”」』]|\x27[^\x27]*\x27', text)]
    if any(a <= start < b for a,b in quoted):
        return False
    return not re.match(r'\s*(?:라는|라고|는\s*말|가\s*아니|는\s*뜻이\s*아니|하지\s*않)', text[start+len(excerpt):])
