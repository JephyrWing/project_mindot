# Final source-local reservation revision: observed verification and terminal outcome

The current final candidate REPAIR-13 is stopped under the latest user's explicit boundary. This is a development canary failure, not a claim of irrecoverable source corruption. No further product fix/retry or formal evaluation follows it.

## Frozen implementation

Only primary Agent prompt and SELECT-only provider projection changed relative to REPAIR-12. Reserved, not-yet-existing request addresses moved verbatim from the global presentationRequests catalog to their matching source.reviewSignalReservations. Existing ACTIVE requests remain global. Exact slot/source/revision/index/alias checks are mechanical; no model meaning or target is reassigned. Original source bodies and all catalog entries are recoverable; canonical state, other phases, schema/enums, validators and call policy stay unchanged.

- Primary Agent6831 characters SHA cb4b3c4ff2c0408f985753a0f4a2a95dd0da3fe52a7a4608ed2c1b07bf3d6343.
- prompts.py SHA5f1d9a0ea3cf6fe9766108620f3fa8cb9f0485d0a188b8e9c521973ac18b5f9d; provider.py SHAe2867dc5958528de6914e669036f7bec0ff3f9a8b14e15d018b980941e57c3e4.
- Snapshot50 files/47 Python, source-setf4fa31387855250597e68a04b6e09d3bcade680ad406dcbfa0d5e54c1278e927; snapshot receipt SHAc968397b266cee65664871d896d9026900fb7301f30899e0be954c64c0a0287d. Independent read-only review verified snapshot/repository100 total file checks with no differences and no concrete contract regression. This is not proof of semantic model performance.

## Actual offline execution

- Full suite121/121 PASS,516 subtests,47/47 compile, zero errors/failures/skips. Product network/secret attempts0 (guard self-tests separately recorded), source bytes unchanged, worker74040 exit0,143.019 seconds. Receipt offline/mindot_ai/runs/repair-13/results/offline-result.json SHA384ba6e3a1325cc8524b4026e37fed731627a1a59b963377d2df29eeb24d07d1.
- Q11 adapter10/10 PASS, exit0, source/runtime unchanged; receipt offline/mindot_ai/runs/q11-adapter-verified-13/results/offline-result.json SHAf0dbba71829716e1ee0b632e98a83f39d694aa48e95d66a48d041d3fa87fba4e.
- Development preparation4/4 PASS; no actual preparation/network/API/key/hidden reads within that synthetic test. Receipt transport-recovery/mindot_ai/development-runs10/20260908T153657325129Z/receipt.json SHA0e5bad239e1ee5016a7a77c7dec251736e4371e4daa24fca02546c11bcf9f536.

## Last paid canary: retry10 / actual attempt11

Preparation reverified all prior541 locked rows and published fresh608-row lock SHA763ffa292e4a58ee8dea44c543313ab3468529bf60bd920f272141dfd9be3e77. Actual worker25229 ran using supported elevated network execution and canonical finish returned CANARY_BLOCKED/exit0:9 planned,2 attempted,7 not run after the mandatory failure. Generation4, Moderation2, actual observed46802 generation tokens. Inputs/model/temperatures/limits fixed; no automatic case retry.

example-only PASS:20421 tokens=20146 prompt+275 completion. SELECT emitted one valid help signal plus a nonexistent second alias; the preexisting bounded ARGUMENT_REPAIR corrected only the ID binding, then Writer produced two explicit fictional examples and one closer/neither question. The matching request was fulfilled on commit, atoms remained empty and all four coverage statuses NOT_EXPLORED. Limited example diversity remains a quality caveat. This case used3 generation calls including its one permitted extra, no Assessor.

complete-substantive-example FAIL:26381 tokens=26149 prompt+232 completion, SELECT only. The model omitted all four substantive contributions and still placed the latest ACK help clause into FOR_1 slot_000, with its signal target FOR_1. The top-level presentation target pointed to ACK and requestIds to slot_003:signal_0 and slot_003:signal_1, but slot_003 had no signals. Source, signal target and presentation target were internally inconsistent. Exact-span validation rejected invalid_occurrence before Writer or accepted state. No postprocessing moved the clause, inserted contributions or changed the raw result. The source-local catalog relocation therefore did not solve the observed semantic failure.

Official0/368, NOT_RUN; hidden plaintext/release key unread. Cumulative31 generation/16 Moderation dispatch attempts, known305355 generation tokens (300894 prompt+4461 completion). Attempt05 provider execution/usage remains UNKNOWN/null with18806 reserved;324161 is known-plus-reserved accounting, not actual billing. Finalization audit11 independently binds exact raw/journal/source/lock and cumulative figures.

Preserve all historical snapshots, offline failed fixtures as well as later passing fixes, raw responses and canonical statuses. Archive and verified Drive upload are the remaining actions, followed by final local status/link files and user-authorized normal Windows shutdown without force.
