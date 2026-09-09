"""Active CBT insight facade. Historical engines are not runtime fallbacks."""
from cbt_simple.contracts import Start,Turn,Result,ProtocolError
from cbt_simple.service import start as generate_agent_cbt_start,turn as generate_agent_cbt_turn,close as close_agent_cbt_session
CBT_AGENT_PROMPT_VERSION='cbt-insight-1'
