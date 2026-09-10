"""Compatibility facade for the Q11 StateGraph Agent runtime.

Public FastAPI entry points are unchanged. Historical implementations are
read-only snapshots outside the repository, never runtime fallbacks.
"""
from typing import Any

from cbt_agent import CbtStartRequest, CbtTurnRequest, CbtTurnResponse
from cbt_q11.contracts import CbtAgentIdempotencyError, REVIEWER_VERSION
from cbt_q11.service import close, generate, registry as _registry
from cbt_q11.state import SessionRegistry

CBT_AGENT_PROMPT_VERSION = REVIEWER_VERSION
CbtAgentSessionRegistry = SessionRegistry


async def generate_agent_cbt_start(
    request: CbtStartRequest,
    *,
    agent_model: Any | None = None,
    assessor_model: Any | None = None,
    writer_model: Any | None = None,
    moderation_client: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    return await generate(
        request, agent_model=agent_model, assessor_model=assessor_model,
        writer_model=writer_model, moderation_client=moderation_client,
        registry=registry,
    )


async def generate_agent_cbt_turn(
    request: CbtTurnRequest,
    *,
    agent_model: Any | None = None,
    assessor_model: Any | None = None,
    writer_model: Any | None = None,
    moderation_client: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    return await generate(
        request, agent_model=agent_model, assessor_model=assessor_model,
        writer_model=writer_model, moderation_client=moderation_client,
        registry=registry,
    )


async def close_agent_cbt_session(
    session_id: int,
    *,
    registry: CbtAgentSessionRegistry = _registry,
) -> None:
    await close(session_id, registry=registry)
