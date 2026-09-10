# Implementation review before live execution

This is a self-review of newly written code, not GPT approval or a claim of live model quality. Active contract: supplied simple-dialogue-1 package. The supplied complete five prompts and schema.py are applied verbatim (prompt loading removes one terminal LF).

## Scope and preservation

The original REPAIR-13 worktree was preserved in starting-worktree.zip, starting-worktree.diff and starting-state.json before implementation. All 50 implementation-base-manifest files matched. Product edits are the new cbt_simple core and the facade binding; existing app.py/.gitignore and prior cbt_q11/cbt_q5 user changes remain. No backend/frontend edits, reset, clean, commit, push or deployment.

The actual Google Drive iteration log 1SEXzKZPNTJzuzjrcjp8hiyBnMHU4oetu was read-only fetched before implementation; provider modification timestamp 2026-09-09T03:34:22.351Z. Its newest first-delivery contract was consistent with this package. Historical log text is not active instructions. Grader file was handled only as bytes for its supplied length/SHA256; no rubric scoring or tuning. It is excluded from submission content, with its hash lock retained.

## Reviewed execution boundary

Public facade -> real StateGraph -> ChatOpenAI function-call selection -> one of five actual async callables -> actual ToolMessage -> public DTO -> completed checkpoint/CommitBundle publication. Assessment review reuses the same ChatOpenAI object and the actual Agent/ToolMessage pair. There is no old semantic policy fallback in this path; old imports provide storage, public contracts, pure move mappings, emergency recognizer and JSON/token utilities.

Sources occur as full raw text in a compact current view; schemas do not enumerate source IDs. Sparse events record only actual proposed changes. Source validation checks exact citations, current revision and withdrawn intervals. Corrections-of-corrections are resolved in chronology; requests, goals, gap answers and safety resolution have invalidation dependencies. Archived originals and unknown legacy snapshot fields remain stored. Failed semantic transactions do not publish state or delivery receipts; raw received source ledger survives. Legacy migration translates actual receipts, not historical all-source classifications. Cold public-only restoration is explicitly distinct from precise persisted checkpoint lineage.

Coverage is absent/null on normal turns. Final evidence is extracted only in the Assessor. Genuine no-clear, limited fact assessment, unresolved CONTINUE guidance and user STOP guidance remain distinct. Absent evidence is null; proposed alternatives are not user acknowledgement. The original thought is never silently replaced by a later answer. Gap allowance remains consumed after withdrawal. Writer format repair is limited to one same-plan repair and the global third call; no semantic repair or fourth call. Public message validation checks only nonblank/500 characters, not punctuation or endings.

Measured wire reservation uses compact SDK kwargs, o200k_base tokens +64 framing, input x1.75 plus phase output cap. This is a local conservative estimate, not guaranteed provider accounting. Actual usage is recorded and unknown usage keeps its reservation. Default 48000 tokens/196608 bytes remains unchanged. First canary fixes all source/runner/schema/prompt/input/expected/config hashes.

## Functional offline evidence

tests/test_simple_dialogue.py executes twelve relationship tests with SDK resource fakes and a network-fail guard. They cover normal selection and replay/conflict; no-coverage completion with actual Agent review; unresolved control; mixed answer/help/correction/reaffirmation; Writer format repair and failed atomic commit; stop/resume and safety; one-use gap explanation/answer/skip/withdraw/wait; request cancellation/target changes and original thought withdrawal; safety resolution withdrawal; cancellation and unknown citation rejection; long raw memory and cold restoration; legacy raw/unknown-state preservation. Offline exports run through the common neutral schema, including failed output and Q10's actual original adapter with fake SDK transport.

These scripted semantic outputs verify plumbing and state relationships, not whether the live model chooses correctly. Existing old-core test files are preserved. Old all-source-review/coverage/slot-count/word-ending requirements are not applied as gates to the replacement core; their still-relevant source/atomicity/dialogue requirements are exercised above. No failure is reclassified as a model success.

Public OpenAPI was compared byte-semantically through isolated original/current app imports. Backend ReflectionSessionTurnTransactionService branches on CONFIRM_REQUIRED/SAFETY_STOP and stores nullable evidence in ReflectionSessions.applyOutcomeDraft. Frontend CBT.jsx uses null-coalescing for missing evidence, preserves the confirmation branch, and has the actual cancelReflection call plus '성찰 완전히 중단' action. This is source inspection, not a running end-to-end browser/backend test. No required API consumer change was found.

## Limitations to carry into live acceptance

Live semantic selection, citation exactness, requested help delivery and adaptive questions are not established by these fake tests. Agent review rejection remains an honest technical failure. A successful canary must demonstrate its own predefined functional invariants; internal reference phase arrays are diagnostic only. Common neutral keys and nulls remove explicit version metadata but missing internal observations may remain identifying. Full raw records preserve this limitation for blind-first review.
